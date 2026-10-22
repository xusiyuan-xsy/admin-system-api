package com.ruoyi.system.service;

import java.util.Date;
import java.util.Set;
import java.util.stream.Collectors;
import com.ruoyi.system.domain.DatingWorkflow.*;
import com.ruoyi.system.mapper.DatingBillingMapper;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.ruoyi.system.service.DatingPackageRules.require;

@Service
public class DatingDeductionService
{
    private final DatingWorkflowMapper mapper;
    private final DatingBillingMapper billing;
    public DatingDeductionService(DatingWorkflowMapper mapper, DatingBillingMapper billing)
    { this.mapper = mapper; this.billing = billing; }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public void process(Long id)
    {
        evaluate(id, false);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void settleBeforeClosure(Long id)
    {
        evaluate(id, true);
    }

    private void evaluate(Long id, boolean closing)
    {
        Task original = mapper.selectTask(id);
        require(original != null, "扣次任务不存在");
        Customer customer = mapper.lockCustomer(original.userId());
        Participant participant = mapper.lockParticipant(original.pairId(), original.userId());
        require(participant != null, "扣次参与方不存在");
        Balance balance = mapper.lockBalance(participant.entitlementId());
        Task task = mapper.lockTask(id);
        if (!"READY".equals(task.status()) && !(closing && ("ERROR".equals(task.status())
            || ("DONE".equals(task.status()) && Set.of("PENDING", "BLOCKED").contains(task.outcome()))))) return;
        if (!closing && task.nextAttemptAt().after(new Date())) return;
        Ledger existing = mapper.selectDeduction(task.pairId(), task.userId());
        if (existing != null)
        {
            finish(task, "DEDUCTED", null, existing.ledgerId());
            return;
        }
        Pair pair = mapper.selectPair(task.pairId());
        if (Set.of("SUCCESS_CLOSED", "TERMINATED").contains(pair.progress())
            || (customer != null && "SUCCESS_CLOSED".equals(customer.serviceStatus())))
        {
            finish(task, "STOPPED", "服务已结案或终止，不再扣次", null);
            return;
        }
        Set<String> completed = mapper.selectEvents(task.pairId()).stream().map(Event::optionCode).collect(Collectors.toSet());
        if (!completed.containsAll(DatingWorkflowService.requiredCodes(participant.ruleSnapshot())))
        {
            finish(task, "PENDING", null, null);
            return;
        }
        String blocked = null;
        if (customer == null || !"0".equals(customer.status())) blocked = "客户已删除或停用";
        else if ("SUCCESS_CLOSED".equals(customer.serviceStatus())) blocked = "客户已成功结案";
        else if (balance == null || !task.userId().equals(balance.userId()) || !"PAID".equals(balance.orderStatus()))
            blocked = "绑定权益与付款记录不一致";
        else if (!"ACTIVE".equals(balance.status()) || balance.remainingTimes() < 1)
            blocked = "绑定权益已耗尽或关闭；不会自动改扣续单权益";
        if (blocked != null)
        {
            finish(task, "BLOCKED", blocked, null);
            return;
        }
        require(mapper.deduct(balance.entitlementId()) == 1, "权益余额已变化");
        Key key = new Key();
        mapper.insertDeduction(key, task, balance, "dating-deduction");
        finish(task, "DEDUCTED", null, key.getId());
        if (balance.remainingTimes() == 1)
            require(billing.updateServiceStatus(task.userId(), "NOT_RENEWED") == 1, "服务状态更新失败");
    }

    private void finish(Task task, String outcome, String reason, Long ledgerId)
    {
        require(mapper.updateParticipant(task.pairId(), task.userId(), outcome, reason, ledgerId) == 1, "参与方更新失败");
        mapper.completeTask(task.taskId(), outcome);
    }

    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void failed(Long id, RuntimeException error)
    {
        Task task = mapper.lockTask(id);
        if (task == null) return;
        String code = DatingRecoveryErrors.code(error);
        // A stale failure cannot overwrite a committed success or count the same retry window twice.
        if (mapper.failTask(id, new Date(System.currentTimeMillis() + 30000), code) == 1)
            require(mapper.insertTaskHistory(id, "FAILED", task.attempts() + 1, code, "dating-deduction", null) == 1,
                "扣次异常历史记录失败");
    }
}
