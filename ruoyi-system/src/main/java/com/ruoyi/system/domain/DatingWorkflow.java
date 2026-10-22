package com.ruoyi.system.domain;

import java.util.Date;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;

public final class DatingWorkflow
{
    private DatingWorkflow() {}

    public static class Key
    {
        private Long id;
        public Long getId() { return id; }
        public void setId(Long value) { id = value; }
    }

    public record Staff(Long userId, String userName, String nickName) {}
    public record Customer(Long userId, String name, String phone, String status, String serviceStatus,
        Long matchmakerId, String matchmakerName, @JsonIgnore String owner, Long entitlementId, Integer remainingTimes) {}
    public record Assignment(Long id, Long userId, String previousName, String nextName, String reason,
        String createBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record Pair(Long pairId, Long lowUserId, Long highUserId, String lowName, String highName,
        String progress, @JsonIgnore String lowOwner, @JsonIgnore String highOwner,
        String createBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record Participant(Long pairId, Long userId, String name, Long entitlementId,
        String ruleSnapshot, String deductStatus, String blockedReason, Long ledgerId,
        Integer remainingTimes, String serviceStatus) {}
    public record Recommendation(Long id, Long pairId, String reason, String lowIntention, String highIntention,
        String createBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record Intention(Long id, Long recommendationId, Long userId, String intention, String reason,
        String createBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record Event(Long eventId, Long pairId, String optionCode,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date occurredAt, String note, String evidence,
        String requestKey, Long appointmentId, String createBy,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record Appointment(Long appointmentId, Long pairId,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date plannedAt, String place, String status,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date completedAt,
        String createBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record AppointmentHistory(Long id, Long appointmentId, String action,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date plannedAt, String place, String reason,
        String createBy, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record Task(Long taskId, Long eventId, Long pairId, Long userId, String status, String outcome,
        Integer attempts, String lastError, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date nextAttemptAt) {}
    public record Ledger(Long ledgerId, Long pairId, Long userId, Long entitlementId, Long orderId,
        Long eventId, Integer delta, Integer balanceBefore, Integer balanceAfter, String createBy,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date createTime) {}
    public record Balance(Long entitlementId, Long userId, Long orderId, String orderStatus, String status,
        Integer remainingTimes, @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date exhaustedAt) {}
    public record Detail(Pair pair, List<Participant> participants, List<Recommendation> recommendations,
        List<Intention> intentions, List<Event> events, List<Appointment> appointments,
        List<AppointmentHistory> appointmentHistory, List<Task> tasks, List<Ledger> ledger) {}
}
