package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.common.core.domain.entity.DatingPackage;

/**
 * 套餐表 数据层
 */
public interface DatingPackageMapper
{
    /**
     * 查询套餐列表
     *
     * @param datingPackage 套餐信息
     * @return 套餐列表
     */
    List<DatingPackage> selectDatingPackageList(DatingPackage datingPackage);

    /**
     * 根据套餐ID查询套餐信息
     *
     * @param packageId 套餐ID
     * @return 套餐信息
     */
    DatingPackage selectDatingPackageById(Long packageId);

    /**
     * 新增套餐
     *
     * @param datingPackage 套餐信息
     * @return 结果
     */
    int insertDatingPackage(DatingPackage datingPackage);

    /**
     * 修改套餐
     *
     * @param datingPackage 套餐信息
     * @return 结果
     */
    int updateDatingPackage(DatingPackage datingPackage);

    /**
     * 删除套餐
     *
     * @param packageId 套餐ID
     * @return 结果
     */
    int deleteDatingPackageById(Long packageId);

    /**
     * 批量删除套餐
     *
     * @param packageIds 套餐ID数组
     * @return 结果
     */
    int deleteDatingPackageByIds(Long[] packageIds);
}
