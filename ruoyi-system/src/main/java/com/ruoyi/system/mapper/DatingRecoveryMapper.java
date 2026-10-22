package com.ruoyi.system.mapper;

import java.util.Date;
import java.util.List;
import com.ruoyi.system.domain.DatingRecovery.*;
import org.apache.ibatis.annotations.Param;

public interface DatingRecoveryMapper
{
    int startRun(@Param("id") String id, @Param("instance") String instance);
    int finishRun(@Param("id") String id, @Param("status") String status,
        @Param("result") DispatchResult result, @Param("error") String error);
    Run lastRun(String instance);
    List<Run> runs();
    Queue queue(Date overdueBefore);
    long staleRuns(Date staleBefore);
    List<Issue> issues(@Param("pairId") Long pairId, @Param("owner") String owner,
        @Param("overdueBefore") Date overdueBefore, @Param("kind") String kind,
        @Param("handling") String handling, @Param("mine") String mine, @Param("overdueOnly") boolean overdueOnly);
    List<History> history(Long taskId);
    Followup followup(Long taskId);
    boolean activeIssue(@Param("taskId") Long taskId, @Param("overdueBefore") Date overdueBefore);
    int insertFollowup(@Param("taskId") Long taskId, @Param("actor") String actor, @Param("dueAt") Date dueAt);
    int updateFollowup(@Param("taskId") Long taskId, @Param("version") Long version,
        @Param("handler") String handler, @Param("status") String status,
        @Param("dueAt") Date dueAt, @Param("actor") String actor);
    int insertFollowupHistory(@Param("taskId") Long taskId, @Param("action") String action,
        @Param("note") String note, @Param("actor") String actor);
    List<FollowupHistory> followupHistory(Long taskId);
}
