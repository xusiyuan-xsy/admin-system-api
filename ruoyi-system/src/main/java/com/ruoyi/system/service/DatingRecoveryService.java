package com.ruoyi.system.service;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import com.ruoyi.system.domain.DatingRecovery.*;
import com.ruoyi.system.mapper.DatingRecoveryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatingRecoveryService
{
    private static final Logger LOG = LoggerFactory.getLogger(DatingRecoveryService.class);
    private final DatingRecoveryMapper mapper;
    private final DatingDeductionDispatcher dispatcher;
    private final String instance = UUID.randomUUID().toString();
    private final long startedAt = System.currentTimeMillis();
    private final boolean enabled;
    private final long staleMs;
    private final long overdueMs;

    public DatingRecoveryService(DatingRecoveryMapper mapper, DatingDeductionDispatcher dispatcher,
        @Value("${dating.recovery.enabled:true}") boolean enabled,
        @Value("${dating.recovery.stale-ms:120000}") long staleMs,
        @Value("${dating.recovery.overdue-ms:300000}") long overdueMs)
    {
        if (staleMs <= 0 || overdueMs <= 0) throw new IllegalArgumentException("Recovery thresholds must be positive");
        this.mapper = mapper; this.dispatcher = dispatcher; this.enabled = enabled;
        this.staleMs = staleMs; this.overdueMs = overdueMs;
    }

    // The start marker must commit before dispatch; a crashed process leaves RUNNING evidence.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void recover()
    {
        if (!enabled) return;
        String id = UUID.randomUUID().toString();
        boolean recorded = false;
        try { mapper.startRun(id, instance); recorded = true; }
        catch (RuntimeException ex) { LOG.error("Could not start dating recovery record {}", id, ex); }
        DispatchResult result = new DispatchResult(0, 0, 0, 0);
        String status = "FAILED", error = null;
        try
        {
            result = dispatcher.dispatch(null);
            status = result.failed() == 0 ? "SUCCEEDED" : "PARTIAL";
        }
        catch (RuntimeException ex)
        {
            error = DatingRecoveryErrors.code(ex);
            LOG.error("Dating recovery scan {} failed", id, ex);
        }
        if (recorded)
        {
            try { mapper.finishRun(id, status, result, error); }
            catch (RuntimeException ex) { LOG.error("Could not finish dating recovery record {}", id, ex); }
        }
    }

    public Overview overview()
    {
        Run last = mapper.lastRun(instance);
        long now = System.currentTimeMillis();
        String health;
        if (!enabled) health = "DISABLED";
        else if (last == null) health = now - startedAt >= staleMs ? "STALE" : "NOT_STARTED";
        else if ((last.finishedAt() == null ? last.startedAt() : last.finishedAt()).getTime() <= now - staleMs) health = "STALE";
        else health = last.status();
        return new Overview(enabled, health, instance, staleMs, last,
            mapper.queue(new Date(now - overdueMs)), mapper.staleRuns(new Date(now - staleMs)));
    }

    public List<Run> runs() { return mapper.runs(); }
    public List<Issue> issues(Long pairId, String owner)
    { return issues(pairId, owner, null); }
    public List<Issue> issues(Long pairId, String owner, String kind)
    { return issues(pairId, owner, kind, null, null, false); }
    public List<Issue> issues(Long pairId, String owner, String kind, String handling, String mine, boolean overdueOnly)
    {
        DatingPackageRules.require(pairId == null || pairId > 0, "配对编号必须为正整数");
        DatingPackageRules.require(kind == null || java.util.Set.of("ERROR", "BLOCKED", "READY").contains(kind),
            "异常类型无效");
        DatingPackageRules.require(handling == null || java.util.Set.of("UNASSIGNED", "IN_PROGRESS", "WAITING").contains(handling),
            "跟进状态无效");
        return mapper.issues(pairId, owner, new Date(System.currentTimeMillis() - overdueMs), kind, handling, mine, overdueOnly);
    }
    public List<History> history(Long taskId) { return mapper.history(taskId); }
}
