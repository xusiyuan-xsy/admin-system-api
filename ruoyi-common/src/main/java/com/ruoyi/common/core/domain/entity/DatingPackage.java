package com.ruoyi.common.core.domain.entity;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 套餐表 dating_package
 */
public class DatingPackage extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 套餐ID */
    private Long packageId;

    /** 套餐名称 */
    private String packageName;

    /** 套餐类型（0基础套餐 1VIP套餐） */
    private String packageType;

    /** 套餐价格 */
    private BigDecimal price;

    /** 是否限制服务天数（0否 1是） */
    private String limitExpire;

    /** 服务时长（天） */
    private Integer serviceDuration;

    /** 是否限制服务次数（0否 1是） */
    private String limitServiceTimes;

    /** 服务次数（次） */
    private Integer serviceTimes;

    /** 套餐介绍 */
    private String description;

    /** 套餐封面 */
    private String coverImage;

    /** 排序权重(越小越靠前) */
    private Integer sortOrder;

    /** 套餐状态（0启用 1停用，与 sys_normal_disable 一致） */
    private String status;

    /** 删除标志（0存在 2删除） */
    private String delFlag;

    private String selectionMode;
    private String serviceOptionCodesJson;
    private Integer ruleVersion;

    public String getSelectionMode() { return selectionMode; }
    public void setSelectionMode(String selectionMode) { this.selectionMode = selectionMode; }
    @JsonIgnore
    public String getServiceOptionCodesJson() { return serviceOptionCodesJson; }
    public void setServiceOptionCodesJson(String value) { this.serviceOptionCodesJson = value; }
    public List<String> getServiceOptionCodes()
    {
        return serviceOptionCodesJson == null ? List.of() : JSON.parseArray(serviceOptionCodesJson, String.class);
    }
    public void setServiceOptionCodes(List<String> codes)
    {
        this.serviceOptionCodesJson = codes == null ? null : JSON.toJSONString(codes);
    }
    public Integer getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(Integer ruleVersion) { this.ruleVersion = ruleVersion; }

    public Long getPackageId() { return packageId; }
    public void setPackageId(Long packageId) { this.packageId = packageId; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getPackageType() { return packageType; }
    public void setPackageType(String packageType) { this.packageType = packageType; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public String getLimitExpire() { return limitExpire; }
    public void setLimitExpire(String limitExpire) { this.limitExpire = limitExpire; }

    public Integer getServiceDuration() { return serviceDuration; }
    public void setServiceDuration(Integer serviceDuration) { this.serviceDuration = serviceDuration; }

    public String getLimitServiceTimes() { return limitServiceTimes; }
    public void setLimitServiceTimes(String limitServiceTimes) { this.limitServiceTimes = limitServiceTimes; }

    public Integer getServiceTimes() { return serviceTimes; }
    public void setServiceTimes(Integer serviceTimes) { this.serviceTimes = serviceTimes; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCoverImage() { return coverImage; }
    public void setCoverImage(String coverImage) { this.coverImage = coverImage; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDelFlag() { return delFlag; }
    public void setDelFlag(String delFlag) { this.delFlag = delFlag; }
}
