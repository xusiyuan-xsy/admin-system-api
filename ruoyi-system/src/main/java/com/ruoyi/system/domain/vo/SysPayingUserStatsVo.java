package com.ruoyi.system.domain.vo;

import java.io.Serializable;
import java.util.List;

/**
 * sys_paying_user aggregate stats (active rows: del_flag=0).
 */
public class SysPayingUserStatsVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    private Long delFlag0Count;

    private Long status0Count;

    private Long status1Count;

    private Long maleCount;

    private Long femaleCount;

    private List<SysPayingUserBirthYearStatVo> birthYearStats;

    private Long notStartedCount;
    private Long servingCount;
    private Long notRenewedCount;
    private Long successPeopleCount;
    private Long successCaseCount;
    private Long successClosedCount;

    public Long getSuccessClosedCount() { return successClosedCount; }
    public void setSuccessClosedCount(Long value) { successClosedCount = value; }

    public Long getNotStartedCount() { return notStartedCount; }
    public void setNotStartedCount(Long value) { notStartedCount = value; }
    public Long getServingCount() { return servingCount; }
    public void setServingCount(Long value) { servingCount = value; }
    public Long getNotRenewedCount() { return notRenewedCount; }
    public void setNotRenewedCount(Long value) { notRenewedCount = value; }
    public Long getSuccessPeopleCount() { return successPeopleCount; }
    public void setSuccessPeopleCount(Long value) { successPeopleCount = value; }
    public Long getSuccessCaseCount() { return successCaseCount; }
    public void setSuccessCaseCount(Long value) { successCaseCount = value; }

    public Long getDelFlag0Count()
    {
        return delFlag0Count;
    }

    public void setDelFlag0Count(Long delFlag0Count)
    {
        this.delFlag0Count = delFlag0Count;
    }

    public Long getStatus0Count()
    {
        return status0Count;
    }

    public void setStatus0Count(Long status0Count)
    {
        this.status0Count = status0Count;
    }

    public Long getStatus1Count()
    {
        return status1Count;
    }

    public void setStatus1Count(Long status1Count)
    {
        this.status1Count = status1Count;
    }

    public Long getMaleCount()
    {
        return maleCount;
    }

    public void setMaleCount(Long maleCount)
    {
        this.maleCount = maleCount;
    }

    public Long getFemaleCount()
    {
        return femaleCount;
    }

    public void setFemaleCount(Long femaleCount)
    {
        this.femaleCount = femaleCount;
    }

    public List<SysPayingUserBirthYearStatVo> getBirthYearStats()
    {
        return birthYearStats;
    }

    public void setBirthYearStats(List<SysPayingUserBirthYearStatVo> birthYearStats)
    {
        this.birthYearStats = birthYearStats;
    }
}
