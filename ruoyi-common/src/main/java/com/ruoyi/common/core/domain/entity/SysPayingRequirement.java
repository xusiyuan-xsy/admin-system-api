package com.ruoyi.common.core.domain.entity;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 档案-要求列表 sys_paying_requirement
 */
public class SysPayingRequirement extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;

    /** 关联档案ID */
    private Long payingId;

    /** 要求对方最小出生年份 */
    private Integer ageMin;

    /** 要求对方最大出生年份 */
    private Integer ageMax;

    /** 要求对方家乡 */
    private String hometownRequire;

    /** 要求对方所在城市 */
    private String cityRequire;

    /** 要求对方学历 */
    private String education;

    /** 要求对方最低身高(cm) */
    private Integer heightMin;

    /** 要求对方最高身高(cm) */
    private Integer heightMax;

    /** 要求无纹身（0不限 1要求无纹身） */
    private String noTattoo;

    /** 是否接受异地（0否 1是） */
    private String acceptLongDist;

    /** 抽烟情况（0无 1偶尔 2经常） */
    private String smokeHabit;

    /** 喝酒情况（0无 1偶尔 2经常） */
    private String drinkHabit;

    /** 是否有车（0无 1有） */
    private String hasCar;

    /** 是否有房（0无 1有） */
    private String hasHouse;

    /** 其他要求 */
    private String otherRequire;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPayingId() { return payingId; }
    public void setPayingId(Long payingId) { this.payingId = payingId; }

    public Integer getAgeMin() { return ageMin; }
    public void setAgeMin(Integer ageMin) { this.ageMin = ageMin; }

    public Integer getAgeMax() { return ageMax; }
    public void setAgeMax(Integer ageMax) { this.ageMax = ageMax; }

    public String getHometownRequire() { return hometownRequire; }
    public void setHometownRequire(String hometownRequire) { this.hometownRequire = hometownRequire; }

    public String getCityRequire() { return cityRequire; }
    public void setCityRequire(String cityRequire) { this.cityRequire = cityRequire; }

    public String getEducation() { return education; }
    public void setEducation(String education) { this.education = education; }

    public Integer getHeightMin() { return heightMin; }
    public void setHeightMin(Integer heightMin) { this.heightMin = heightMin; }

    public Integer getHeightMax() { return heightMax; }
    public void setHeightMax(Integer heightMax) { this.heightMax = heightMax; }

    public String getNoTattoo() { return noTattoo; }
    public void setNoTattoo(String noTattoo) { this.noTattoo = noTattoo; }

    public String getAcceptLongDist() { return acceptLongDist; }
    public void setAcceptLongDist(String acceptLongDist) { this.acceptLongDist = acceptLongDist; }

    public String getSmokeHabit() { return smokeHabit; }
    public void setSmokeHabit(String smokeHabit) { this.smokeHabit = smokeHabit; }

    public String getDrinkHabit() { return drinkHabit; }
    public void setDrinkHabit(String drinkHabit) { this.drinkHabit = drinkHabit; }

    public String getHasCar() { return hasCar; }
    public void setHasCar(String hasCar) { this.hasCar = hasCar; }

    public String getHasHouse() { return hasHouse; }
    public void setHasHouse(String hasHouse) { this.hasHouse = hasHouse; }

    public String getOtherRequire() { return otherRequire; }
    public void setOtherRequire(String otherRequire) { this.otherRequire = otherRequire; }
}
