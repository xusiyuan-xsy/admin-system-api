package com.ruoyi.system.mapper;

import java.util.Date;
import java.util.List;
import com.ruoyi.system.domain.DatingWorkflow.*;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import org.apache.ibatis.annotations.Param;

public interface DatingWorkflowMapper
{
    List<Staff> selectStaff(@Param("keyword") String keyword);
    Staff selectStaffById(Long id);
    List<Customer> selectCustomers(@Param("keyword") String keyword, @Param("owner") String owner,
        @Param("serviceStatus") String serviceStatus);
    Customer selectCustomer(Long id);
    Long lockCustomerRow(Long id);
    default Customer lockCustomer(Long id)
    { return lockCustomerRow(id) == null ? null : selectCustomer(id); }
    int assign(@Param("userId") Long userId, @Param("staffId") Long staffId);
    int insertAssignment(@Param("userId") Long userId, @Param("previous") Long previous,
        @Param("next") Long next, @Param("reason") String reason, @Param("actor") String actor);
    List<Assignment> selectAssignments(Long userId);
    Pair selectPair(Long id);
    Long lockPairRow(Long id);
    default Pair lockPair(Long id)
    { return lockPairRow(id) == null ? null : selectPair(id); }
    Pair findPair(@Param("low") Long low, @Param("high") Long high);
    List<Pair> selectPairs(@Param("keyword") String keyword, @Param("progress") String progress, @Param("owner") String owner);
    int insertPair(@Param("key") Key key, @Param("low") Long low, @Param("high") Long high, @Param("actor") String actor);
    int updateProgress(@Param("id") Long id, @Param("progress") String progress);
    int insertParticipant(@Param("pairId") Long pairId, @Param("userId") Long userId, @Param("entitlementId") Long entitlementId);
    List<Participant> selectParticipants(Long pairId);
    Long lockParticipantRow(@Param("pairId") Long pairId, @Param("userId") Long userId);
    Participant selectParticipant(@Param("pairId") Long pairId, @Param("userId") Long userId);
    default Participant lockParticipant(Long pairId, Long userId)
    { return lockParticipantRow(pairId, userId) == null ? null : selectParticipant(pairId, userId); }
    Recommendation selectRecommendation(Long id);
    Recommendation findRecommendation(@Param("actor") String actor, @Param("key") String key);
    List<Recommendation> selectRecommendations(Long pairId);
    int insertRecommendation(@Param("key") Key key, @Param("pairId") Long pairId,
        @Param("request") Recommend request, @Param("actor") String actor);
    int updateIntention(@Param("id") Long id, @Param("low") boolean low, @Param("value") String value);
    int insertIntention(@Param("id") Long id, @Param("request") Intent request, @Param("actor") String actor);
    List<Intention> selectIntentions(Long pairId);
    List<Event> selectEvents(Long pairId);
    Event findEvent(@Param("pairId") Long pairId, @Param("requestKey") String requestKey);
    int insertEvent(@Param("key") Key key, @Param("pairId") Long pairId, @Param("request") ServiceEvent request,
        @Param("appointmentId") Long appointmentId, @Param("actor") String actor);
    List<Appointment> selectAppointments(Long pairId);
    Appointment selectAppointment(Long id);
    int insertAppointment(@Param("key") Key key, @Param("pairId") Long pairId,
        @Param("request") Schedule request, @Param("actor") String actor);
    int reschedule(@Param("id") Long id, @Param("request") Schedule request);
    int updateAppointment(@Param("id") Long id, @Param("status") String status, @Param("completedAt") Date completedAt);
    int insertAppointmentHistory(@Param("id") Long id, @Param("action") String action, @Param("plannedAt") Date plannedAt,
        @Param("place") String place, @Param("reason") String reason, @Param("actor") String actor);
    List<AppointmentHistory> selectAppointmentHistory(Long pairId);
    int insertTasks(@Param("eventId") Long eventId, @Param("pairId") Long pairId);
    List<Task> selectTasks(Long pairId);
    List<Long> selectReadyTasks(@Param("pairId") Long pairId);
    Task selectTask(Long id);
    Task lockTask(Long id);
    Balance lockBalance(Long id);
    Balance selectBalance(Long id);
    Ledger selectDeduction(@Param("pairId") Long pairId, @Param("userId") Long userId);
    List<Ledger> selectLedger(Long pairId);
    int deduct(Long id);
    int insertDeduction(@Param("key") Key key, @Param("task") Task task,
        @Param("balance") Balance balance, @Param("actor") String actor);
    int updateParticipant(@Param("pairId") Long pairId, @Param("userId") Long userId,
        @Param("status") String status, @Param("reason") String reason, @Param("ledgerId") Long ledgerId);
    int completeTask(@Param("id") Long id, @Param("outcome") String outcome);
    int failTask(@Param("id") Long id, @Param("nextAttempt") Date nextAttempt, @Param("error") String error);
    int insertTaskHistory(@Param("id") Long id, @Param("action") String action,
        @Param("attempt") int attempt, @Param("error") String error, @Param("actor") String actor,
        @Param("reason") String reason);
    int retryTask(Long id);
}
