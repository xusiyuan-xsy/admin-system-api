package com.ruoyi.system.service;

import java.util.*;
import com.ruoyi.system.domain.DatingSuccess.*;
import com.ruoyi.system.domain.DatingSuccess.Case;
import com.ruoyi.system.domain.DatingWorkflow.*;
import com.ruoyi.system.mapper.*;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.ruoyi.system.service.DatingPackageRules.require;

@Service
public class DatingSuccessService
{
    private final DatingSuccessMapper mapper;
    private final DatingWorkflowMapper workflow;
    private final DatingBillingMapper billing;
    private final DatingWorkflowService access;
    private final DatingDeductionService deduction;
    private final Validator validator;
    private final boolean enabled;

    public DatingSuccessService(DatingSuccessMapper mapper, DatingWorkflowMapper workflow, DatingBillingMapper billing,
        DatingWorkflowService access, DatingDeductionService deduction, Validator validator,
        @Value("${dating.success.enabled:false}") boolean enabled)
    {
        this.mapper=mapper; this.workflow=workflow; this.billing=billing; this.access=access;
        this.deduction=deduction; this.validator=validator; this.enabled=enabled;
    }

    public boolean enabled() { return enabled; }
    public List<Case> list(String keyword, Date from, Date until, Long matchmakerId, String owner)
    { return mapper.list(keyword, from, until, matchmakerId, owner); }
    public Stats stats(String keyword, Date from, Date until, Long matchmakerId, String owner)
    { return mapper.stats(keyword, from, until, matchmakerId, owner); }
    public List<Matchmaker> matchmakers(String owner) { return mapper.matchmakers(owner); }
    public com.ruoyi.system.domain.DatingSuccess.Detail detail(Long id, String actor, boolean all)
    {
        Case item=mapper.selectCase(id);
        require(item!=null, "成功案例不存在");
        access.accessiblePair(item.pairId(), actor, all);
        return new com.ruoyi.system.domain.DatingSuccess.Detail(item, mapper.members(id), mapper.evidence(id));
    }

    @Transactional(rollbackFor=Exception.class, isolation=Isolation.READ_COMMITTED)
    public Case close(Long pairId, CloseRequest request, String actor, boolean all)
    {
        require(enabled, "成功结案尚未启用，请先确认业务核实标准");
        var errors=validator.validate(request);
        require(errors.isEmpty(), errors.isEmpty() ? "" : errors.iterator().next().getMessage());
        Pair original=access.accessiblePair(pairId, actor, all);
        List<Long> affected=mapper.affectedPairs(original.lowUserId(), original.highUserId());
        SortedSet<Long> users=new TreeSet<>();
        for (Long id:affected)
        {
            Pair p=workflow.selectPair(id);
            users.add(p.lowUserId()); users.add(p.highUserId());
        }
        // Settlement also touches counterparties. Lock the complete set in global ID order.
        for (Long id:users) require(workflow.lockCustomer(id)!=null, "关联客户已删除，需要管理员核查");
        require(affected.equals(mapper.affectedPairs(original.lowUserId(), original.highUserId())),
            "关联配对已变化，请刷新后重新结案");
        for (Long id:affected) workflow.lockPair(id);
        Pair pair=access.accessiblePair(pairId, actor, all);
        Case existing=mapper.findByPair(pairId);
        if (existing!=null) return existing;
        require(!Set.of("SUCCESS_CLOSED","TERMINATED").contains(pair.progress()), "配对已结案或终止");
        require(!request.successAt().before(pair.createTime()), "脱单确认时间不能早于配对建立时间");
        List<Recommendation> records=workflow.selectRecommendations(pairId);
        require(!records.isEmpty(), "缺少红娘推荐服务记录");
        Recommendation source=records.get(0);
        require("WILLING".equals(source.lowIntention()) && "WILLING".equals(source.highIntention()),
            "请先核实并登记双方愿意继续的意向");
        require(workflow.selectParticipants(pairId).size()==2, "配对参与方记录不完整");
        for (Participant p:workflow.selectParticipants(pairId))
        {
            Customer c=workflow.selectCustomer(p.userId());
            require("0".equals(c.status()) && !"SUCCESS_CLOSED".equals(c.serviceStatus()), "客户停用或已成功结案");
            Balance b=workflow.selectBalance(p.entitlementId());
            require(b!=null && p.userId().equals(b.userId()) && "PAID".equals(b.orderStatus())
                && Set.of("ACTIVE","EXHAUSTED").contains(b.status()), "绑定权益缺少有效收款记录或已关闭");
        }
        // Evaluate all recorded facts, including delayed/error tasks, before any terminal state.
        for (Long id:affected)
        {
            if (terminal(workflow.selectPair(id))) continue;
            List<Task> tasks=new ArrayList<>(workflow.selectTasks(id));
            tasks.sort(Comparator.comparing(Task::taskId));
            for (Task task:tasks) deduction.settleBeforeClosure(task.taskId());
        }
        Key key=new Key();
        mapper.insertCase(key, pairId, source.id(), request, actor);
        String reason="成功结案 #" + key.getId() + "，终止后续服务";
        for (Long id:List.of(pair.lowUserId(), pair.highUserId()))
        {
            require(mapper.insertMember(key.getId(), id)==1, "结案快照保存失败");
            mapper.closeEntitlements(id);
            require(billing.updateServiceStatus(id, "SUCCESS_CLOSED")==1, "服务状态更新失败");
            mapper.cancelOrders(id, reason, actor);
        }
        for (Long id:affected)
        {
            if (terminal(workflow.selectPair(id))) continue;
            for (Appointment appointment:workflow.selectAppointments(id))
            {
                if (!"SCHEDULED".equals(appointment.status())) continue;
                workflow.updateAppointment(appointment.appointmentId(), "TERMINATED", null);
                workflow.insertAppointmentHistory(appointment.appointmentId(), "TERMINATE",
                    appointment.plannedAt(), appointment.place(), reason, actor);
            }
            workflow.updateProgress(id, id.equals(pairId) ? "SUCCESS_CLOSED" : "TERMINATED");
            mapper.stopParticipants(id, reason);
            mapper.stopTasks(id);
        }
        return mapper.findByPair(pairId);
    }

    private boolean terminal(Pair pair) { return Set.of("SUCCESS_CLOSED","TERMINATED").contains(pair.progress()); }
}
