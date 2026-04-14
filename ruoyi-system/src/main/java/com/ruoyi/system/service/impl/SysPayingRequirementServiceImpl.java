package com.ruoyi.system.service.impl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
import com.ruoyi.system.mapper.SysPayingRequirementMapper;
import com.ruoyi.system.service.ISysPayingRequirementService;
/**
 * 档案-要求 业务层处理
 */
@Service
public class SysPayingRequirementServiceImpl implements ISysPayingRequirementService
{
    @Autowired
    private SysPayingRequirementMapper requirementMapper;
    @Override
    public SysPayingRequirement selectRequirementByPayingId(Long payingId)
    {
        return requirementMapper.selectRequirementByPayingId(payingId);
    }
    @Override
    public int insertRequirement(SysPayingRequirement requirement)
    {
        return requirementMapper.insertRequirement(requirement);
    }
    @Override
    public int updateRequirement(SysPayingRequirement requirement)
    {
        return requirementMapper.updateRequirement(requirement);
    }
}