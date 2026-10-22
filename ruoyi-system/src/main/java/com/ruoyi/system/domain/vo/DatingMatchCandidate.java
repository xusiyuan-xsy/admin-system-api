package com.ruoyi.system.domain.vo;

import java.util.List;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ruoyi.common.core.domain.entity.SysPayingUser;

public class DatingMatchCandidate extends SysPayingUser
{
    private SysPayingUser sourceArchive;
    private boolean serviceEligible;
    private boolean sourceEligible;
    private boolean reverseMatched;
    private String matchmakerName;
    private Long pairId;
    private String pairProgress;
    private String reciprocalStatus;
    private List<String> matchedConditions;
    private List<String> reverseConflicts;
    private List<String> pendingConditions;

    @JsonIgnore @JSONField(serialize = false)
    public SysPayingUser getSourceArchive() { return sourceArchive; }
    public void setSourceArchive(SysPayingUser value) { sourceArchive = value; }
    public boolean isServiceEligible() { return serviceEligible; }
    public void setServiceEligible(boolean value) { serviceEligible = value; }
    public boolean isSourceEligible() { return sourceEligible; }
    public void setSourceEligible(boolean value) { sourceEligible = value; }
    public boolean isReverseMatched() { return reverseMatched; }
    public void setReverseMatched(boolean value) { reverseMatched = value; }
    public String getMatchmakerName() { return matchmakerName; }
    public void setMatchmakerName(String value) { matchmakerName = value; }
    public Long getPairId() { return pairId; }
    public void setPairId(Long value) { pairId = value; }
    public String getPairProgress() { return pairProgress; }
    public void setPairProgress(String value) { pairProgress = value; }
    public String getReciprocalStatus() { return reciprocalStatus; }
    public void setReciprocalStatus(String value) { reciprocalStatus = value; }
    public List<String> getMatchedConditions() { return matchedConditions; }
    public void setMatchedConditions(List<String> value) { matchedConditions = value; }
    public List<String> getReverseConflicts() { return reverseConflicts; }
    public void setReverseConflicts(List<String> value) { reverseConflicts = value; }
    public List<String> getPendingConditions() { return pendingConditions; }
    public void setPendingConditions(List<String> value) { pendingConditions = value; }
}
