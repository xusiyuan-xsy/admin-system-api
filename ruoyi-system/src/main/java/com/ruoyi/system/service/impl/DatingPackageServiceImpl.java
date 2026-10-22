package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.system.mapper.DatingPackageMapper;
import com.ruoyi.system.service.IDatingPackageService;
import com.ruoyi.system.service.DatingPackageRules;
import org.springframework.transaction.annotation.Transactional;
import static com.ruoyi.system.service.DatingPackageRules.require;

/**
 * 套餐 业务层处理
 */
@Service
public class DatingPackageServiceImpl implements IDatingPackageService
{
    @Autowired
    private DatingPackageMapper datingPackageMapper;
    @Autowired
    private DatingPackageRules rules;

    @Override
    public List<DatingPackage> selectDatingPackageList(DatingPackage datingPackage)
    {
        return datingPackageMapper.selectDatingPackageList(datingPackage);
    }

    @Override
    public DatingPackage selectDatingPackageById(Long packageId)
    {
        return datingPackageMapper.selectDatingPackageById(packageId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertDatingPackage(DatingPackage datingPackage)
    {
        rules.validate(datingPackage);
        datingPackage.setRuleVersion(1);
        return datingPackageMapper.insertDatingPackage(datingPackage);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateDatingPackage(DatingPackage datingPackage)
    {
        DatingPackage old = datingPackageMapper.lockDatingPackageById(datingPackage.getPackageId());
        require(old != null, "套餐不存在");
        // The existing table switch sends only the identifier and status.
        if (datingPackage.getPackageName() == null && datingPackage.getPrice() == null
            && datingPackage.getSelectionMode() == null && datingPackage.getServiceOptionCodesJson() == null
            && datingPackage.getServiceTimes() == null && datingPackage.getLimitExpire() == null
            && datingPackage.getLimitServiceTimes() == null && datingPackage.getStatus() != null)
        {
            require("0".equals(datingPackage.getStatus()) || "1".equals(datingPackage.getStatus()), "状态无效");
            if ("0".equals(datingPackage.getStatus())) rules.validate(old);
            // Do not allow partial requests to bypass validation of other package fields.
            DatingPackage statusUpdate = new DatingPackage();
            statusUpdate.setPackageId(old.getPackageId());
            statusUpdate.setStatus(datingPackage.getStatus());
            statusUpdate.setUpdateBy(datingPackage.getUpdateBy());
            statusUpdate.setRuleVersion(old.getRuleVersion() == null ? 1 : old.getRuleVersion() + 1);
            return datingPackageMapper.updateDatingPackage(statusUpdate);
        }
        require(java.util.Objects.equals(old.getRuleVersion(), datingPackage.getRuleVersion()),
            "套餐已被修改，请刷新后重试");
        rules.validate(datingPackage);
        datingPackage.setRuleVersion(old.getRuleVersion() == null ? 1 : old.getRuleVersion() + 1);
        return datingPackageMapper.updateDatingPackage(datingPackage);
    }

    @Override
    public int deleteDatingPackageById(Long packageId)
    {
        return datingPackageMapper.deleteDatingPackageById(packageId);
    }

    @Override
    public int deleteDatingPackageByIds(Long[] packageIds)
    {
        return datingPackageMapper.deleteDatingPackageByIds(packageIds);
    }
}
