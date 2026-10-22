package com.ruoyi.system.domain.dto;

import com.ruoyi.common.core.domain.entity.SysPayingRequirement;

/** Search-only controls; never persisted as a customer's requirements. */
public class DatingMatchSearch extends SysPayingRequirement
{
    private String matchMode = "ONE_WAY";
    private String candidateScope = "ELIGIBLE";
    private String historyFilter = "ALL";
    private String sortBy = "MATCH";

    public String getMatchMode() { return matchMode; }
    public void setMatchMode(String value) { matchMode = value; }
    public String getCandidateScope() { return candidateScope; }
    public void setCandidateScope(String value) { candidateScope = value; }
    public String getHistoryFilter() { return historyFilter; }
    public void setHistoryFilter(String value) { historyFilter = value; }
    public String getSortBy() { return sortBy; }
    public void setSortBy(String value) { sortBy = value; }
}
