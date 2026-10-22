package com.ruoyi.system.domain;

public class DatingCustomer
{
    private Long userId;
    private String name;
    private String phone;
    private String status;
    private String createBy;
    private String serviceStatus;
    private String effectiveOwner;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getEffectiveOwner() { return effectiveOwner; }
    public void setEffectiveOwner(String v) { effectiveOwner = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { userId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { phone = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String v) { createBy = v; }
    public String getServiceStatus() { return serviceStatus; }
    public void setServiceStatus(String v) { serviceStatus = v; }
}
