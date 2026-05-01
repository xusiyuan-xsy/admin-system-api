package com.ruoyi.system.domain.vo;

import java.io.Serializable;

/**
 * Paying user counts by birth year and sex (del_flag=0 only in query).
 */
public class SysPayingUserBirthYearStatVo implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** Birth year */
    private Integer year;

    /** Count with sex = 0 (male) */
    private Long maleCount;

    /** Count with sex = 1 (female) */
    private Long femaleCount;

    public Integer getYear()
    {
        return year;
    }

    public void setYear(Integer year)
    {
        this.year = year;
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
}
