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
