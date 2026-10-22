package com.ruoyi.system.mapper;

import java.util.Date;
import java.util.List;
import com.ruoyi.system.domain.DatingSuccess.*;
import com.ruoyi.system.domain.DatingSuccess.Case;
import com.ruoyi.system.domain.DatingWorkflow.Key;
import org.apache.ibatis.annotations.Param;

public interface DatingSuccessMapper
{
    List<Long> affectedPairs(@Param("low") Long low, @Param("high") Long high);
    Case findByPair(Long pairId);
    Case selectCase(Long id);
    List<Member> members(Long id);
    Evidence evidence(Long id);
    List<Case> list(@Param("keyword") String keyword, @Param("from") Date from, @Param("until") Date until,
        @Param("matchmakerId") Long matchmakerId, @Param("owner") String owner);
    Stats stats(@Param("keyword") String keyword, @Param("from") Date from, @Param("until") Date until,
        @Param("matchmakerId") Long matchmakerId, @Param("owner") String owner);
    List<Matchmaker> matchmakers(@Param("owner") String owner);
    void insertCase(@Param("key") Key key, @Param("pairId") Long pairId, @Param("recommendationId") Long recommendationId,
        @Param("request") CloseRequest request, @Param("actor") String actor);
    int insertMember(@Param("caseId") Long caseId, @Param("userId") Long userId);
    int closeEntitlements(Long userId);
    int cancelOrders(@Param("userId") Long userId, @Param("reason") String reason, @Param("actor") String actor);
    int stopParticipants(@Param("pairId") Long pairId, @Param("reason") String reason);
    int stopTasks(Long pairId);
}
