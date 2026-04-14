package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.entity.SysPayingRequirement;

/**
 * 档案-要求 业务层
 */
public interface ISysPayingRequirementService
{
    /**
     * 根据 payingId 查询要求信息
     *
     * @param payingId 档案ID
     * @return 要求信息
     */
    SysPayingRequirement selectRequirementByPayingId(Long payingId);

    /**
     * 新增要求信息
     *
     * @param requirement 要求信息
     * @return 结果
     */
    int insertRequirement(SysPayingRequirement requirement);

    /**
     * 修改要求信息（按 payingId 更新）
     *
     * @param requirement 要求信息
     * @return 结果
     */
    int updateRequirement(SysPayingRequirement requirement);
}
