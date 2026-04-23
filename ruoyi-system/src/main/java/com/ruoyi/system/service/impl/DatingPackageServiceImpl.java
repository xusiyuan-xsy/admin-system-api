package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.system.mapper.DatingPackageMapper;
import com.ruoyi.system.service.IDatingPackageService;

/**
 * 套餐 业务层处理
 */
@Service
public class DatingPackageServiceImpl implements IDatingPackageService
{
    @Autowired
    private DatingPackageMapper datingPackageMapper;

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
    public int insertDatingPackage(DatingPackage datingPackage)
    {
        return datingPackageMapper.insertDatingPackage(datingPackage);
    }

    @Override
    public int updateDatingPackage(DatingPackage datingPackage)
    {
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
