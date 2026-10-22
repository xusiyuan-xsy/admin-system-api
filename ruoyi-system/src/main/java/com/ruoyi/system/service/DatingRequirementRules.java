package com.ruoyi.system.service;

import java.time.Year;
import java.util.Set;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
import com.ruoyi.common.exception.ServiceException;

public final class DatingRequirementRules
{
    private DatingRequirementRules() {}

    public static void validate(SysPayingRequirement r)
    {
        if (r.getPayingId() == null) throw new ServiceException("请选择客户");
        range(r.getAgeMin(), r.getAgeMax(), 1900, Year.now().getValue(), "出生年份");
        range(r.getHeightMin(), r.getHeightMax(), 100, 250, "身高");
        code(r.getEducation(), Set.of("0","1","2","3","4","5","6","7","8"), "学历");
        code(r.getSmokeHabit(), Set.of("0","1","2"), "吸烟要求");
        code(r.getDrinkHabit(), Set.of("0","1","2"), "饮酒要求");
        code(r.getNoTattoo(), Set.of("0","1"), "无纹身要求");
        code(r.getHasCar(), Set.of("0","1"), "车辆要求");
        code(r.getHasHouse(), Set.of("0","1"), "住房要求");
        code(r.getAcceptLongDist(), Set.of("0","1"), "异地要求");
        if ((r.getRemark() != null && r.getRemark().length() > 500)
            || (r.getOtherRequire() != null && r.getOtherRequire().length() > 500))
            throw new ServiceException("备注和其他要求不得超过500字");
        if ("0".equals(r.getAcceptLongDist())
            && (r.getCityRequire() == null || r.getCityRequire().isBlank()))
            throw new ServiceException("不接受异地时必须填写现居城市要求");
    }

    private static void range(Integer low, Integer high, int min, int max, String label)
    {
        if ((low != null && (low < min || low > max))
            || (high != null && (high < min || high > max))
            || (low != null && high != null && low > high))
            throw new ServiceException(label + "范围不正确");
    }

    private static void code(String value, Set<String> allowed, String label)
    {
        if (value != null && !value.isEmpty() && !allowed.contains(value))
            throw new ServiceException(label + "取值不正确");
    }
}
