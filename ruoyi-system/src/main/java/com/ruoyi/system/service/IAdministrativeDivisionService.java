package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AdministrativeDivision;

/**
 * Administrative division service.
 */
public interface IAdministrativeDivisionService
{
    List<AdministrativeDivision> selectRegionList(String parentCode);

    List<AdministrativeDivision> selectRegionPath(String code);
}
