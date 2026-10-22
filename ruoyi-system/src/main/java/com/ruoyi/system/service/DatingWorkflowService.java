package com.ruoyi.system.service;

import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.domain.DatingEntitlement;
import com.ruoyi.system.domain.DatingWorkflow.*;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import com.ruoyi.system.mapper.DatingBillingMapper;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.system.service.DatingPackageRules.require;

@Service
public class DatingWorkflowService
{
    private final DatingWorkflowMapper mapper;
    private final DatingBillingMapper billing;
    private final Validator validator;

    public DatingWorkflowService(DatingWorkflowMapper mapper, DatingBillingMapper billing, Validator validator)
    { this.mapper = mapper; this.billing = billing; this.validator = validator; }

    private void validate(Object request)
    {
        var violations = validator.validate(request);
        require(violations.isEmpty(), violations.isEmpty() ? "" : violations.iterator().next().getMessage());
    }

    public List<Staff> staff(String keyword) { return mapper.selectStaff(keyword); }

    public List<Customer> customers(String keyword, String status, String owner)
    {
        // Preserve PageHelper's Page instance so pagination totals survive masking.
        List<Customer> rows = mapper.selectCustomers(keyword, owner, status);
        for (int i = 0; i < rows.size(); i++)
        {
            Customer c = rows.get(i);
            String phone = c.phone() == null || c.phone().length() < 7 ? "***"
                : c.phone().substring(0, 3) + "****" + c.phone().substring(c.phone().length() - 4);
            rows.set(i, new Customer(c.userId(), c.name(), phone, c.status(), c.serviceStatus(),
                c.matchmakerId(), c.matchmakerName(), c.owner(), c.entitlementId(), c.remainingTimes()));
        }
        return rows;
    }

    private void customerAccess(Customer c, String actor, boolean all)
    {
        require(c != null, "客户不存在或已删除");
        require(all || Objects.equals(c.owner(), actor), "无权操作该客户");
    }

    public List<Assignment> assignments(Long id, String actor, boolean all)
    {
        customerAccess(mapper.selectCustomer(id), actor, all);
        return mapper.selectAssignments(id);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void assign(Long id, Assign request, String actor, boolean all)
    {
        validate(request);
        Customer c = mapper.lockCustomer(id);
        customerAccess(c, actor, all);
        require("0".equals(c.status()), "停用客户不能分配");
        require(mapper.selectStaffById(request.matchmakerId()) != null, "负责红娘账号不存在或已停用");
        require(Objects.equals(c.matchmakerId(), request.expectedMatchmakerId()), "客户归属已变化，请刷新后重试");
        require(!Objects.equals(c.matchmakerId(), request.matchmakerId()), "该账号已是当前负责人");
        mapper.assign(id, request.matchmakerId());
        mapper.insertAssignment(id, c.matchmakerId(), request.matchmakerId(), request.reason(), actor);
    }

    public List<Pair> pairs(String keyword, String progress, String owner)
    { return mapper.selectPairs(keyword, progress, owner); }

    public Pair accessiblePair(Long id, String actor, boolean all)
    {
        Pair pair = mapper.selectPair(id);
        pairAccess(pair, actor, all);
        return pair;
    }

    private void pairAccess(Pair pair, String actor, boolean all)
    {
        require(pair != null, "配对不存在");
        require(all || Objects.equals(actor, pair.lowOwner()) || Objects.equals(actor, pair.highOwner()), "无权访问该配对");
    }

    public Detail detail(Long id, String actor, boolean all)
    {
        Pair pair = accessiblePair(id, actor, all);
        return new Detail(pair, mapper.selectParticipants(id), mapper.selectRecommendations(id),
            mapper.selectIntentions(id), mapper.selectEvents(id), mapper.selectAppointments(id),
            mapper.selectAppointmentHistory(id), mapper.selectTasks(id), mapper.selectLedger(id));
    }

    private DatingEntitlement canStart(Customer c)
    {
        require(c != null && "0".equals(c.status()), "客户不存在或已停用");
        require(c.matchmakerId() != null && mapper.selectStaffById(c.matchmakerId()) != null, "请先分配有效的负责红娘");
        require("SERVING".equals(c.serviceStatus()), "客户未开通、未续单或已结案，不能新增服务");
        DatingEntitlement e = billing.selectActiveEntitlement(c.userId());
        require(e != null && e.getRemainingTimes() > 0, "客户没有可用服务次数");
        Balance b = mapper.selectBalance(e.getEntitlementId());
        require(b != null && "PAID".equals(b.orderStatus()), "服务权益没有有效的已收款订单");
        return e;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long recommend(Recommend request, String actor, boolean all)
    {
        validate(request);
        require(!request.firstUserId().equals(request.secondUserId()), "不能与自己配对");
        long low = Math.min(request.firstUserId(), request.secondUserId());
        long high = Math.max(request.firstUserId(), request.secondUserId());
        Customer a = mapper.lockCustomer(low);
        Customer b = mapper.lockCustomer(high);
        customerAccess(a, actor, all);
        customerAccess(b, actor, all);
        Recommendation replay = mapper.findRecommendation(actor, request.requestKey());
        if (replay != null)
        {
            Pair p = mapper.selectPair(replay.pairId());
            require(p.lowUserId() == low && p.highUserId() == high && Objects.equals(request.reason(), replay.reason()),
                "请求标识已被其他推荐占用");
            return p.pairId();
        }
        DatingEntitlement ea = canStart(a);
        DatingEntitlement eb = canStart(b);
        Pair pair = mapper.findPair(low, high);
        Long pairId;
        if (pair == null)
        {
            Key key = new Key();
            mapper.insertPair(key, low, high, actor);
            pairId = key.getId();
            require(mapper.insertParticipant(pairId, low, ea.getEntitlementId()) == 1, "客户权益绑定失败");
            require(mapper.insertParticipant(pairId, high, eb.getEntitlementId()) == 1, "客户权益绑定失败");
        }
        else
        {
            require(!Set.of("SUCCESS_CLOSED", "TERMINATED").contains(pair.progress()), "配对已结案或终止");
            pairId = pair.pairId();
            mapper.updateProgress(pairId, "COMMUNICATING");
        }
        mapper.insertRecommendation(new Key(), pairId, request, actor);
        return pairId;
    }

    private Pair lockPairForAction(Long id, String actor, boolean all)
    {
        Pair original = accessiblePair(id, actor, all);
        // Use the same customer-first, ascending-ID order as billing and deductions.
        require(mapper.lockCustomer(original.lowUserId()) != null, "客户已删除");
        require(mapper.lockCustomer(original.highUserId()) != null, "客户已删除");
        Pair pair = mapper.lockPair(id);
        pairAccess(pair, actor, all);
        require(!Set.of("SUCCESS_CLOSED", "TERMINATED").contains(pair.progress()), "配对已结案或终止");
        require(!"SUCCESS_CLOSED".equals(mapper.selectCustomer(pair.lowUserId()).serviceStatus())
            && !"SUCCESS_CLOSED".equals(mapper.selectCustomer(pair.highUserId()).serviceStatus()), "客户已成功结案");
        return pair;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long intention(Long recommendationId, Intent request, String actor, boolean all)
    {
        validate(request);
        Recommendation original = mapper.selectRecommendation(recommendationId);
        require(original != null, "推荐不存在");
        Pair pair = lockPairForAction(original.pairId(), actor, all);
        require(request.userId().equals(pair.lowUserId()) || request.userId().equals(pair.highUserId()), "用户不属于该配对");
        require(mapper.selectRecommendations(pair.pairId()).get(0).id().equals(recommendationId), "仅能确认最新推荐的意向");
        mapper.updateIntention(recommendationId, request.userId().equals(pair.lowUserId()), request.intention());
        mapper.insertIntention(recommendationId, request, actor);
        Recommendation updated = mapper.selectRecommendation(recommendationId);
        String progress = "REJECTED".equals(updated.lowIntention()) || "REJECTED".equals(updated.highIntention())
            ? "REJECTED" : "WILLING".equals(updated.lowIntention()) && "WILLING".equals(updated.highIntention())
            ? "IN_PROGRESS" : "COMMUNICATING";
        mapper.updateProgress(pair.pairId(), progress);
        return pair.pairId();
    }

    public static Set<String> requiredCodes(String snapshot)
    {
        JSONObject rule = JSON.parseObject(snapshot);
        require(rule != null && "ALL".equals(rule.getString("matchMode")) && rule.getJSONArray("options") != null,
            "绑定服务规则异常，需要管理员核查");
        Set<String> codes = new HashSet<>();
        for (Object item : rule.getJSONArray("options"))
        {
            String code = ((JSONObject) item).getString("code");
            require(code != null && !code.isBlank(), "绑定服务选项无效");
            codes.add(code);
        }
        require(!codes.isEmpty(), "服务条件不能为空");
        return codes;
    }

    private void historicalTime(Pair pair, Date occurredAt)
    {
        require(occurredAt != null && !occurredAt.after(new Date()), "实际发生时间不能为空或晚于当前时间");
        require(!occurredAt.before(pair.createTime()), "不能把建立配对前的事实用于本次服务");
        for (Participant p : mapper.selectParticipants(pair.pairId()))
        {
            Customer c = mapper.selectCustomer(p.userId());
            require(c != null && "0".equals(c.status()) && !"SUCCESS_CLOSED".equals(c.serviceStatus()), "客户停用或已结案");
            Balance b = mapper.selectBalance(p.entitlementId());
            require(b != null && !"SUCCESS_CLOSED".equals(b.status()), "绑定权益已关闭");
            if ("EXHAUSTED".equals(b.status()) && (b.exhaustedAt() == null || occurredAt.after(b.exhaustedAt())))
            {
                require("DEDUCTED".equals(p.deductStatus()), "仅能补录绑定权益耗尽前已发生的服务事实");
                // A renewed customer may continue the same pair, never deduct the renewed balance again.
                canStart(c);
            }
        }
    }

    private Long saveEvent(Pair pair, ServiceEvent request, Long appointmentId, String actor)
    {
        Event existing = mapper.findEvent(pair.pairId(), request.requestKey());
        if (existing != null)
        {
            require(Objects.equals(existing.optionCode(), request.optionCode())
                && existing.occurredAt().getTime() / 1000 == request.occurredAt().getTime() / 1000
                && Objects.equals(existing.note(), request.note()) && Objects.equals(existing.evidence(), request.evidence())
                && Objects.equals(existing.appointmentId(), appointmentId), "请求标识已用于其他服务记录");
            return existing.eventId();
        }
        require(Set.of("IN_PROGRESS", "FOLLOWING").contains(pair.progress()), "请先分别确认双方愿意继续服务");
        historicalTime(pair, request.occurredAt());
        if (appointmentId == null)
        {
            require(!"MEETING_COMPLETED".equals(request.optionCode()), "完成见面须通过见面记录确认");
            require(mapper.selectParticipants(pair.pairId()).stream()
                .anyMatch(p -> requiredCodes(p.ruleSnapshot()).contains(request.optionCode())), "选项不属于双方购买时的服务条件");
        }
        Key key = new Key();
        mapper.insertEvent(key, pair.pairId(), request, appointmentId, actor);
        require(mapper.insertTasks(key.getId(), pair.pairId()) == 2, "配对参与方记录异常");
        if ("IN_PROGRESS".equals(pair.progress())) mapper.updateProgress(pair.pairId(), "FOLLOWING");
        return key.getId();
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long recordEvent(Long id, ServiceEvent request, String actor, boolean all)
    {
        validate(request);
        Pair pair = lockPairForAction(id, actor, all);
        return saveEvent(pair, request, null, actor);
    }

    private void canArrange(Pair pair)
    {
        require(Set.of("IN_PROGRESS", "FOLLOWING").contains(pair.progress()), "双方尚未同意继续，不能安排见面");
        canStart(mapper.selectCustomer(pair.lowUserId()));
        canStart(mapper.selectCustomer(pair.highUserId()));
        // Renewals do not silently replace either participant's original entitlement.
        for (Participant p : mapper.selectParticipants(pair.pairId()))
        {
            Balance b = mapper.selectBalance(p.entitlementId());
            require("DEDUCTED".equals(p.deductStatus()) || "ACTIVE".equals(b.status()),
                "存在未扣次但已耗尽的旧权益绑定，请先由主管核查，不能自动改绑续单");
        }
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long schedule(Long pairId, Schedule request, String actor, boolean all)
    {
        validate(request);
        Pair pair = lockPairForAction(pairId, actor, all);
        canArrange(pair);
        Key key = new Key();
        mapper.insertAppointment(key, pairId, request, actor);
        mapper.insertAppointmentHistory(key.getId(), "SCHEDULE", request.plannedAt(), request.place(), request.reason(), actor);
        return key.getId();
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long reschedule(Long id, Schedule request, String actor, boolean all)
    {
        validate(request);
        Appointment original = mapper.selectAppointment(id);
        require(original != null, "见面记录不存在");
        Pair pair = lockPairForAction(original.pairId(), actor, all);
        canArrange(pair);
        Appointment current = mapper.selectAppointment(id);
        require("SCHEDULED".equals(current.status()), "只能调整待见面的预约");
        mapper.reschedule(id, request);
        mapper.insertAppointmentHistory(id, "RESCHEDULE", request.plannedAt(), request.place(), request.reason(), actor);
        return pair.pairId();
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long appointmentAction(Long id, AppointmentAction request, String actor, boolean all)
    {
        validate(request);
        Appointment original = mapper.selectAppointment(id);
        require(original != null, "见面记录不存在");
        Pair pair = lockPairForAction(original.pairId(), actor, all);
        Appointment current = mapper.selectAppointment(id);
        if ("COMPLETE".equals(request.action()))
        {
            require(request.occurredAt() != null, "请填写实际完成时间");
            require(!request.occurredAt().before(current.createTime()), "完成时间不能早于预约建立时间");
            ServiceEvent event = new ServiceEvent("MEETING_COMPLETED", request.occurredAt(), request.reason(),
                request.evidence(), "appointment-complete-" + id);
            if ("COMPLETED".equals(current.status()))
            {
                saveEvent(pair, event, id, actor);
                return pair.pairId();
            }
            require("SCHEDULED".equals(current.status()), "仅待见面记录可确认完成");
            saveEvent(pair, event, id, actor);
            mapper.updateAppointment(id, "COMPLETED", request.occurredAt());
        }
        else
        {
            require("SCHEDULED".equals(current.status()), "仅待见面记录可以取消或登记爽约");
            mapper.updateAppointment(id, "CANCEL".equals(request.action()) ? "CANCELLED" : "NO_SHOW", null);
        }
        mapper.insertAppointmentHistory(id, request.action(), current.plannedAt(), current.place(), request.reason(), actor);
        return pair.pairId();
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Long retry(Long id, Retry request, String actor, boolean all)
    {
        require(request != null, "请填写重试原因");
        validate(request);
        Task task = mapper.selectTask(id);
        require(task != null, "扣次任务不存在");
        lockPairForAction(task.pairId(), actor, all);
        task = mapper.lockTask(id);
        require(mapper.retryTask(id) == 1, "只有处理异常或业务阻塞的任务允许重试");
        require(mapper.insertTaskHistory(id, "RETRY", task.attempts(), "MANUAL_RETRY", actor, request.reason().strip()) == 1,
            "扣次重试历史记录失败");
        return task.pairId();
    }
}
