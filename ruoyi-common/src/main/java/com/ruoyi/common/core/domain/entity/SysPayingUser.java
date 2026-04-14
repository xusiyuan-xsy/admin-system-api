package com.ruoyi.common.core.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.annotation.Excel.ColumnType;
import com.ruoyi.common.annotation.Excel.Type;
import com.ruoyi.common.annotation.Excels;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.jackson.JsonStringListDeserializer;
import com.ruoyi.common.jackson.StringOrArrayToStringDeserializer;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.xss.Xss;

/**
 * 相亲档案表（自身信息） sys_paying_user
 */
public class SysPayingUser extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    @Excel(name = "用户ID", type = Type.EXPORT, cellType = ColumnType.NUMERIC, prompt = "用户ID")
    private Long id;

    /** 手机号（登录账号） */
    @Excel(name = "手机号", cellType = ColumnType.TEXT)
    private String phone;

    /** 登录密码（加密存储） */
    @Excel(name = "登录密码", cellType = ColumnType.TEXT)
    private String password;

    /** 最后登录IP */
    private String lastLoginIp;

    /** 最后登录时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "最后登录时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss", type = Type.EXPORT)
    private Date lastLoginTime;

    /** 姓名 */
    @Excel(name = "姓名", cellType = ColumnType.TEXT)
    private String name;

    /** 性别（0男 1女） */
    @Excel(name = "性别", readConverterExp = "0=男,1=女")
    private String sex;

    /** 出生年份 */  
    @Excel(name = "出生年份", cellType = ColumnType.NUMERIC)
    private Integer birthYear;

    /** 属相 */
    @Excel(name = "属相", cellType = ColumnType.TEXT)
    private String zodiac;

    /** 身高(cm) */
    @Excel(name = "身高(cm)", cellType = ColumnType.NUMERIC)
    private Integer height;

    /** 体重(斤) */
    @Excel(name = "体重(斤)", cellType = ColumnType.NUMERIC)
    private Integer weight;

    /** 家乡 */
    @Excel(name = "家乡", cellType = ColumnType.TEXT)
    private String hometown;

    /** 现居城市 */
    @Excel(name = "现居城市", cellType = ColumnType.TEXT)
    private String currentCity;

    /** 学历 */
    @Excel(name = "学历", cellType = ColumnType.TEXT)
    private String education;

    /** 职业 */
    @Excel(name = "职业", cellType = ColumnType.TEXT)
    private String occupation;

    /** 月收入(元) */
    @Excel(name = "月收入(元)", cellType = ColumnType.NUMERIC)
    private BigDecimal monthlyIncome;

    /** 家庭成员描述 */
    @Excel(name = "家庭成员描述", cellType = ColumnType.TEXT)
    @JsonDeserialize(using = StringOrArrayToStringDeserializer.class)
    private String familyMembers;

    /** 是否有车（0无 1有） */
    @Excel(name = "是否有车", readConverterExp = "0=无,1=有")
    private String hasCar;

    /** 是否有房（0无 1有） */
    @Excel(name = "是否有房", readConverterExp = "0=无,1=有")
    private String hasHouse;

    /** 房产情况描述 */
    @Excel(name = "房产情况描述", cellType = ColumnType.TEXT)
    private String houseDesc;

    /** 兴趣爱好 */
    @Excel(name = "兴趣爱好", cellType = ColumnType.TEXT)
    @JsonDeserialize(using = StringOrArrayToStringDeserializer.class)
    private String hobbies;

    /** 个人优点/闪光点 */
    @Excel(name = "个人优点/闪光点", cellType = ColumnType.TEXT)
    @JsonDeserialize(using = StringOrArrayToStringDeserializer.class)
    private String advantages;

    /** 抽烟情况（0无 1偶尔 2经常） */
    @Excel(name = "抽烟情况", readConverterExp = "0=无,1=偶尔,2=经常")
    private String smokeHabit;

    /** 喝酒情况（0无 1偶尔 2经常） */
    @Excel(name = "喝酒情况", readConverterExp = "0=无,1=偶尔,2=经常")
    private String drinkHabit;

    /** 是否有纹身（0无 1有） */
    @Excel(name = "是否有纹身", readConverterExp = "0=无,1=有")
    private String hasTattoo;

    /** 是否接受异地（0否 1是） */
    @Excel(name = "是否接受异地", readConverterExp = "0=否,1=是")
    private String acceptLongDist;

    /** 相册图片 URL 列表 */
    @JsonDeserialize(using = JsonStringListDeserializer.class)
    private List<String> photos;

    /** 状态（0启用 1禁用） */
    @Excel(name = "状态", readConverterExp = "0=启用,1=禁用")
    private String status;

    /** 删除标志（0存在 2删除） */
    private String delFlag;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Size(min = 0, max = 11, message = "手机号码长度不能超过11个字符")
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
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

    public String getHasHouse() { return hasHouse; }
    public void setHasHouse(String hasHouse) { this.hasHouse = hasHouse; }

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

    public List<String> getPhotos() { return photos; }
    public void setPhotos(List<String> photos) { this.photos = photos; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
}
