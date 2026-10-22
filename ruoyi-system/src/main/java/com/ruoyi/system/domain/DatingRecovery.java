package com.ruoyi.system.domain;

import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;

public final class DatingRecovery
{
    private DatingRecovery() {}

    public record DispatchResult(int scanned, int processed, int failed, int recordFailures) {}
    public record Run(String runId, String instanceId,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startedAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date finishedAt,
        String status, Integer scanned, Integer processed, Integer failed, Integer recordFailures, String errorCode) {}
    public record Queue(long ready, long due, long overdue, long errors, long blocked, long pending) {}
    public record Overview(boolean enabled, String health, String instanceId, long staleAfterMs,
        Run lastRun, Queue queue, long staleRuns) {}
    public record History(Long historyId, Long taskId, String action, Integer attempt,
        String errorCode, String createBy,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime, String reason) {}
    public record Issue(Long taskId, Long pairId, Long userId, String status, String outcome,
        Integer attempts, String errorCode, String blockedReason,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date nextAttemptAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date firstFailureAt,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date lastFailureAt,
        String lowName, String highName, String customerName, String ownerName,
        String pairProgress, boolean retryAllowed, String handlerName, String handlingStatus,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date dueAt, boolean followupOverdue) {}
    public record Followup(Long taskId, String handler, String handlerName, String status, Long version,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date dueAt,
        String updateBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date updateTime) {}
    public record FollowupDetail(Followup followup, boolean active, boolean canClaim,
        boolean canUpdate, boolean canRelease, boolean overdue) {}
    public record FollowupHistory(Long historyId, Long taskId, Long version, String action,
        String handler, String status, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date dueAt,
        String note, String createBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
}
