package com.ruoyi.system.mapper;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
/**
 * 档案-要求表 数据层
 */
public interface SysPayingRequirementMapper
{
    /**
     * 根据 payingId 查询要求信息
     */
    SysPayingRequirement selectRequirementByPayingId(Long payingId);
    /**
     * 新增要求信息
     */
    int insertRequirement(SysPayingRequirement requirement);
    /**
     * 修改要求信息
     */
    int updateRequirement(SysPayingRequirement requirement);
}