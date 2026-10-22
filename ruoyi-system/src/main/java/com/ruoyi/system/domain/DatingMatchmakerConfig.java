package com.ruoyi.system.domain;

import java.util.List;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ruoyi.common.core.domain.BaseEntity;

public class DatingMatchmakerConfig extends BaseEntity
{
    private Long configId;
    private String name;
    private String watermarkText;
    // Historical API names; values are inclusive birth years, not ages.
    private Integer minAge;
    private Integer maxAge;
    private Integer minHeight;
    private Integer maxHeight;
    private List<String> requiredFields = List.of();
    private boolean defaultConfig;
    private Long version;

    public Long getConfigId() { return configId; }
    public void setConfigId(Long v) { configId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getWatermarkText() { return watermarkText; }
    public void setWatermarkText(String v) { watermarkText = v; }
    public Integer getMinAge() { return minAge; }
    public void setMinAge(Integer v) { minAge = v; }
    public Integer getMaxAge() { return maxAge; }
    public void setMaxAge(Integer v) { maxAge = v; }
    public Integer getMinHeight() { return minHeight; }
    public void setMinHeight(Integer v) { minHeight = v; }
    public Integer getMaxHeight() { return maxHeight; }
    public void setMaxHeight(Integer v) { maxHeight = v; }
    public List<String> getRequiredFields() { return requiredFields; }
    public void setRequiredFields(List<String> v) { requiredFields = v; }
    @JsonIgnore
    public String getRequiredFieldsJson() { return JSON.toJSONString(requiredFields); }
    public void setRequiredFieldsJson(String v) { requiredFields = JSON.parseArray(v, String.class); }
    public boolean isDefaultConfig() { return defaultConfig; }
    public void setDefaultConfig(boolean v) { defaultConfig = v; }
    public Long getVersion() { return version; }
    public void setVersion(Long v) { version = v; }
}
