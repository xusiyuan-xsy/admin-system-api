package com.ruoyi.system.service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import jakarta.validation.Validator;
import com.ruoyi.system.domain.DatingRecovery.*;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.FollowupAction;
import com.ruoyi.system.mapper.DatingRecoveryMapper;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.system.service.DatingPackageRules.require;

@Service
public class DatingExceptionFollowupService
{
    private final DatingRecoveryMapper mapper;
    private final DatingWorkflowMapper tasks;
    private final DatingWorkflowService workflow;
    private final Validator validator;
    private final long overdueMs;

    public DatingExceptionFollowupService(DatingRecoveryMapper mapper, DatingWorkflowMapper tasks,
        DatingWorkflowService workflow, Validator validator,
        @Value("${dating.recovery.overdue-ms:300000}") long overdueMs)
    {
        require(overdueMs > 0, "异常超时配置无效");
        this.mapper = mapper; this.tasks = tasks; this.workflow = workflow;
        this.validator = validator; this.overdueMs = overdueMs;
    }

    public void checkAccess(Long id, String actor, boolean all)
    {
        var task = tasks.selectTask(id);
        require(task != null, "扣次任务不存在");
        workflow.accessiblePair(task.pairId(), actor, all);
    }

    public FollowupDetail detail(Long id, String actor, boolean all)
    {
        checkAccess(id, actor, all);
        Followup current = mapper.followup(id);
        if (current == null) current = new Followup(id, null, null, "UNASSIGNED", 0L, null, null, null);
        boolean active = mapper.activeIssue(id, new Date(System.currentTimeMillis() - overdueMs));
        boolean mine = Objects.equals(actor, current.handler());
        return new FollowupDetail(current, active, active && current.handler() == null,
            active && mine, current.handler() != null && (mine || all),
            active && current.dueAt() != null && !current.dueAt().after(new Date()));
    }

    // Caller checks access before starting pagination, so authorization reads do not consume PageHelper.
    public List<FollowupHistory> history(Long id) { return mapper.followupHistory(id); }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void act(Long id, FollowupAction request, String actor, boolean all)
    {
        require(request != null && validator.validate(request).isEmpty(), "请填写有效操作、版本及1至500字说明");
        var original = tasks.selectTask(id);
        require(original != null, "扣次任务不存在");
        // Same customer -> pair -> task lock order as retry/deduction; recheck ownership after locking.
        var pair = workflow.accessiblePair(original.pairId(), actor, all);
        require(tasks.lockCustomer(pair.lowUserId()) != null && tasks.lockCustomer(pair.highUserId()) != null, "客户已删除");
        require(tasks.lockPair(pair.pairId()) != null, "配对不存在");
        workflow.accessiblePair(pair.pairId(), actor, all);
        require(tasks.lockTask(id) != null, "扣次任务不存在");
        var current = mapper.followup(id);
        long version = current == null ? 0 : current.version();
        require(version == request.version(), "跟进记录已变化，请刷新后重试");
        boolean release = "RELEASE".equals(request.action());
        if (!release)
            require(mapper.activeIssue(id, new Date(System.currentTimeMillis() - overdueMs)), "异常已恢复、等待正常处理或配对已结束，不可继续认领或跟进");
        String handler = current == null ? null : current.handler();
        String status;
        Date due = request.dueAt();
        if ("CLAIM".equals(request.action()))
        {
            require(handler == null, "异常已被认领，请刷新查看");
            require(request.status() == null, "认领时不接受自定义进度");
            handler = actor;
            status = "IN_PROGRESS";
        }
        else if ("UPDATE".equals(request.action()))
        {
            require(Objects.equals(handler, actor), "仅当前认领人可以更新进度");
            require(request.status() != null && Set.of("IN_PROGRESS", "WAITING").contains(request.status()), "跟进状态无效");
            status = request.status();
        }
        else
        {
            require(handler != null && (Objects.equals(handler, actor) || all), "仅认领人或全局服务管理人员可以释放责任");
            require(due == null && request.status() == null, "释放责任不能同时设置进度或截止时间");
            handler = null;
            status = "UNASSIGNED";
        }
        require(due == null || due.after(new Date()) || (current != null && Objects.equals(due, current.dueAt())),
            "新的跟进截止时间必须晚于当前时间");
        if (current == null)
            require(mapper.insertFollowup(id, actor, due) == 1, "异常认领失败");
        else
            require(mapper.updateFollowup(id, version, handler, status, due, actor) == 1, "跟进记录已变化，请刷新后重试");
        require(mapper.insertFollowupHistory(id, request.action(), request.note().strip(), actor) == 1, "跟进历史保存失败");
    }
}
