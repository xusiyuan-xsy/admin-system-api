package com.ruoyi.common.core.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 相亲档案信息 sys_paying_user
 */
public class SysPayingUser extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;

    /** 手机号（登录账号） */
    private String phone;

    /** 登录密码（加密存储） */
    private String password;

    /** 最后登录IP */
    private String lastLoginIp;

    /** 最后登录时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date lastLoginTime;

    /** 姓名 */
    private String name;

    /** 性别（0男 1女） */
    private String sex;

    /** 出生年份 */
    private Integer birthYear;

    /** 属相 */
    private String zodiac;

    /** 身高(cm) */
    private Integer height;

    /** 体重(斤) */
    private Integer weight;

    /** 家乡 */
    private String hometown;

    /** 现居城市 */
    private String currentCity;

    /** 学历 */
    private String education;

    /** 职业 */
    private String occupation;

    /** 月收入(元) */
    private BigDecimal monthlyIncome;

    /** 家庭成员描述 */
    private String familyMembers;

    /** 是否有车（0无 1有） */
    private String hasCar;

    /** 房产情况描述 */
    private String houseDesc;

    /** 兴趣爱好 */
    private String hobbies;

    /** 个人优点/闪光点 */
    private String advantages;

    /** 抽烟情况（0无 1偶尔 2经常） */
    private String smokeHabit;

    /** 喝酒情况（0无 1偶尔 2经常） */
    private String drinkHabit;

    /** 是否有纹身（0无 1有） */
    private String hasTattoo;

    /** 是否接受异地（0否 1是） */
    private String acceptLongDist;

    /** 相册图片URL数组，如["url1","url2"] */
    private String photos;

    /** 状态（0启用 1禁用） */
    private String status;

    /** 删除标志（0存在 2删除） */
    private String delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }

    public Date getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(Date lastLoginTime) { this.lastLoginTime = lastLoginTime; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }

    public Integer getBirthYear() { return birthYear; }
    public void setBirthYear(Integer birthYear) { this.birthYear = birthYear; }

    public String getZodiac() { return zodiac; }
    public void setZodiac(String zodiac) { this.zodiac = zodiac; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }

    public String getHometown() { return hometown; }
    public void setHometown(String hometown) { this.hometown = hometown; }

    public String getCurrentCity() { return currentCity; }
    public void setCurrentCity(String currentCity) { this.currentCity = currentCity; }

    public String getEducation() { return education; }
    public void setEducation(String education) { this.education = education; }

    public String getOccupation() { return occupation; }
    public void setOccupation(String occupation) { this.occupation = occupation; }

    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public void setMonthlyIncome(BigDecimal monthlyIncome) { this.monthlyIncome = monthlyIncome; }

    public String getFamilyMembers() { return familyMembers; }
    public void setFamilyMembers(String familyMembers) { this.familyMembers = familyMembers; }

    public String getHasCar() { return hasCar; }
    public void setHasCar(String hasCar) { this.hasCar = hasCar; }

    public String getHouseDesc() { return houseDesc; }
    public void setHouseDesc(String houseDesc) { this.houseDesc = houseDesc; }

    public String getHobbies() { return hobbies; }
    public void setHobbies(String hobbies) { this.hobbies = hobbies; }

    public String getAdvantages() { return advantages; }
    public void setAdvantages(String advantages) { this.advantages = advantages; }

    public String getSmokeHabit() { return smokeHabit; }
    public void setSmokeHabit(String smokeHabit) { this.smokeHabit = smokeHabit; }

    public String getDrinkHabit() { return drinkHabit; }
    public void setDrinkHabit(String drinkHabit) { this.drinkHabit = drinkHabit; }

    public String getHasTattoo() { return hasTattoo; }
    public void setHasTattoo(String hasTattoo) { this.hasTattoo = hasTattoo; }

    public String getAcceptLongDist() { return acceptLongDist; }
    public void setAcceptLongDist(String acceptLongDist) { this.acceptLongDist = acceptLongDist; }

    public String getPhotos() { return photos; }
    public void setPhotos(String photos) { this.photos = photos; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
}
