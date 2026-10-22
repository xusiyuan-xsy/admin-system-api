package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AdministrativeDivision;

/**
 * Administrative division data access.
 */
public interface AdministrativeDivisionMapper
{
    List<AdministrativeDivision> selectRegionList(
            @Param("countryCode") String countryCode,
            @Param("parentCode") String parentCode);

    AdministrativeDivision selectRegionByCode(
            @Param("countryCode") String countryCode,
            @Param("code") String code);
}
