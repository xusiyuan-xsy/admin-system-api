package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.DatingServiceOption;
import com.ruoyi.system.mapper.DatingBillingMapper;
import org.springframework.stereotype.Component;

@Component
public class DatingPackageRules
{
    private final DatingBillingMapper mapper;

    public DatingPackageRules(DatingBillingMapper mapper) { this.mapper = mapper; }

    public List<DatingServiceOption> validate(DatingPackage pkg)
    {
        require(pkg != null, "套餐不存在");
        require(pkg.getPackageName() != null && !pkg.getPackageName().isBlank()
            && pkg.getPackageName().length() <= 100, "套餐名称不能为空且不超过100字");
        require(pkg.getPrice() != null && pkg.getPrice().compareTo(BigDecimal.ZERO) > 0
            && pkg.getPrice().compareTo(new BigDecimal("99999999.99")) <= 0
            && pkg.getPrice().stripTrailingZeros().scale() <= 2, "套餐金额必须大于0且最多两位小数");
        require("0".equals(pkg.getLimitExpire()) && "1".equals(pkg.getLimitServiceTimes()),
            "本阶段仅支持不限时的固定次数套餐");
        require(pkg.getServiceTimes() != null && pkg.getServiceTimes() > 0, "服务次数必须为正整数");
        require(Set.of("0", "1").contains(Objects.toString(pkg.getStatus(), "")), "套餐状态无效");
        String mode = pkg.getSelectionMode();
        require("SINGLE".equals(mode) || "MULTI".equals(mode), "请选择单选或多选规则");
        List<String> codes = pkg.getServiceOptionCodes();
        require(codes != null && !codes.isEmpty() && codes.size() <= 50
            && !codes.contains(null) && new HashSet<>(codes).size() == codes.size(), "服务选项不能为空或重复");
        require(!"SINGLE".equals(mode) || codes.size() == 1, "单选套餐只能选择一个服务选项");
        List<DatingServiceOption> options = mapper.selectOptionsByCodes(codes);
        require(options.size() == codes.size() && options.stream().allMatch(o -> "0".equals(o.getStatus())),
            "服务选项不存在或已停用，请重新配置");
        return options;
    }

    public String snapshot(DatingPackage pkg)
    {
        List<DatingServiceOption> options = validate(pkg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageId", pkg.getPackageId());
        result.put("packageName", pkg.getPackageName());
        result.put("ruleVersion", pkg.getRuleVersion());
        result.put("price", pkg.getPrice());
        result.put("serviceTimes", pkg.getServiceTimes());
        result.put("selectionMode", pkg.getSelectionMode());
        result.put("matchMode", "ALL");
        result.put("options", options.stream().map(o -> Map.of(
            "code", o.getCode(), "name", o.getName(), "completionStandard", o.getCompletionStandard())).toList());
        return JSON.toJSONString(result);
    }

    public static void require(boolean condition, String message)
    {
        if (!condition) throw new ServiceException(message);
    }
}
