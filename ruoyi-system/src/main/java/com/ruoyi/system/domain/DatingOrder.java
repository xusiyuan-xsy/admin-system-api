package com.ruoyi.system.domain;

import java.math.BigDecimal;
import java.util.Date;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.ruoyi.common.core.domain.BaseEntity;

public class DatingOrder extends BaseEntity
{
    private Long orderId;
    private String orderNo;
    private Long userId;
    private String userName;
    private Long packageId;
    private String packageName;
    private BigDecimal amount;
    private Integer serviceTimes;
    private String ruleSnapshot;
    private String status;
    private String requestKey;
    private BigDecimal paidAmount;
    private String paymentMethod;
    private String paymentReference;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date paidAt;
    private String confirmedBy;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date confirmedAt;
    private String cancelReason;
    private Long entitlementId;
    private String effectiveOwner;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getEffectiveOwner() { return effectiveOwner; }
    public void setEffectiveOwner(String v) { effectiveOwner = v; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long v) { orderId = v; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String v) { orderNo = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { userId = v; }
    public String getUserName() { return userName; }
    public void setUserName(String v) { userName = v; }
    public Long getPackageId() { return packageId; }
    public void setPackageId(Long v) { packageId = v; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String v) { packageName = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public Integer getServiceTimes() { return serviceTimes; }
    public void setServiceTimes(Integer v) { serviceTimes = v; }
    public String getRuleSnapshot() { return ruleSnapshot; }
    public void setRuleSnapshot(String v) { ruleSnapshot = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String v) { requestKey = v; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal v) { paidAmount = v; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String v) { paymentMethod = v; }
    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String v) { paymentReference = v; }
    public Date getPaidAt() { return paidAt; }
    public void setPaidAt(Date v) { paidAt = v; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String v) { confirmedBy = v; }
    public Date getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Date v) { confirmedAt = v; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String v) { cancelReason = v; }
    public Long getEntitlementId() { return entitlementId; }
    public void setEntitlementId(Long v) { entitlementId = v; }
}
