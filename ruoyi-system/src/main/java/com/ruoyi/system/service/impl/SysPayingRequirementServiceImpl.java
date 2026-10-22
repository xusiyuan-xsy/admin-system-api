package com.ruoyi.system.service.impl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
import com.ruoyi.system.mapper.SysPayingRequirementMapper;
import com.ruoyi.system.service.ISysPayingRequirementService;
import com.ruoyi.system.service.DatingArchiveAccess;
import com.ruoyi.system.service.DatingRequirementRules;
import com.ruoyi.system.service.DatingMatchmakerConfigService;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
/**
 * 档案-要求 业务层处理
 */
@Service
public class SysPayingRequirementServiceImpl implements ISysPayingRequirementService
{
    @Autowired
    private SysPayingRequirementMapper requirementMapper;
    @Autowired
    private DatingArchiveAccess access;
    @Autowired
    private DatingMatchmakerConfigService matchmakerConfig;
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SysPayingRequirement selectRequirementByPayingId(Long payingId)
    {
        access.check(payingId, false);
        return requirementMapper.selectRequirementByPayingId(payingId);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertRequirement(SysPayingRequirement requirement)
    {
        DatingRequirementRules.validate(requirement);
        access.check(requirement.getPayingId(), true);
        matchmakerConfig.validateRequirement(requirement);
        if (requirementMapper.selectRequirementByPayingId(requirement.getPayingId()) != null)
            throw new ServiceException("择偶需求已存在，请刷新后修改");
        requirement.setCreateBy(SecurityUtils.getUsername());
        return requirementMapper.insertRequirement(requirement);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRequirement(SysPayingRequirement requirement)
    {
        DatingRequirementRules.validate(requirement);
        access.check(requirement.getPayingId(), true);
        matchmakerConfig.validateRequirement(requirement);
        requirement.setUpdateBy(SecurityUtils.getUsername());
        return requirementMapper.updateRequirement(requirement);
    }
}
