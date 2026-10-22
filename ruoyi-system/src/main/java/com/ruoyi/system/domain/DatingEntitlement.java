package com.ruoyi.system.domain;

import com.ruoyi.common.core.domain.BaseEntity;

public class DatingEntitlement extends BaseEntity
{
    private Long entitlementId;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private String userName;
    private String packageName;
    private Integer totalTimes;
    private Integer usedTimes;
    private Integer remainingTimes;
    private String status;
    private String ruleSnapshot;
    public Long getEntitlementId() { return entitlementId; }
    public void setEntitlementId(Long v) { entitlementId = v; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long v) { orderId = v; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String v) { orderNo = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { userId = v; }
    public String getUserName() { return userName; }
    public void setUserName(String v) { userName = v; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String v) { packageName = v; }
    public Integer getTotalTimes() { return totalTimes; }
    public void setTotalTimes(Integer v) { totalTimes = v; }
    public Integer getUsedTimes() { return usedTimes; }
    public void setUsedTimes(Integer v) { usedTimes = v; }
    public Integer getRemainingTimes() { return remainingTimes; }
    public void setRemainingTimes(Integer v) { remainingTimes = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String v) { ruleSnapshot = v; }
}
