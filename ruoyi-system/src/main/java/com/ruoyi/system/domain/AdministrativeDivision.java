package com.ruoyi.system.domain;

/**
 * Administrative division option used by the region cascader.
 */
public class AdministrativeDivision
{
    private String code;

    private String parentCode;

    private String name;

    private Integer level;

    private Boolean leaf;

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getParentCode()
    {
        return parentCode;
    }

    public void setParentCode(String parentCode)
    {
        this.parentCode = parentCode;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public Integer getLevel()
    {
        return level;
    }

    public void setLevel(Integer level)
    {
        this.level = level;
    }

    public Boolean getLeaf()
    {
        return leaf;
    }

    public void setLeaf(Boolean leaf)
    {
        this.leaf = leaf;
    }
}
