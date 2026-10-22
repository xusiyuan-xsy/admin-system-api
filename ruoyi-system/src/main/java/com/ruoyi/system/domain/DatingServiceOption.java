package com.ruoyi.system.domain;

import com.ruoyi.common.core.domain.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class DatingServiceOption extends BaseEntity
{
    private Long optionId;
    private String code;
    @NotBlank
    @Size(max = 100)
    private String name;
    @NotBlank
    @Size(max = 500)
    private String completionStandard;
    @Pattern(regexp = "[01]")
    @NotBlank
    private String status;
    public Long getOptionId() { return optionId; }
    public void setOptionId(Long v) { optionId = v; }
    public String getCode() { return code; }
    public void setCode(String v) { code = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getCompletionStandard() { return completionStandard; }
    public void setCompletionStandard(String v) { completionStandard = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
}
