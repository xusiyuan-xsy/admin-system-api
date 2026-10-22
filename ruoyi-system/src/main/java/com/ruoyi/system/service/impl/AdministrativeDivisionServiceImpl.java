package com.ruoyi.system.service.impl;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.AdministrativeDivision;
import com.ruoyi.system.mapper.AdministrativeDivisionMapper;
import com.ruoyi.system.service.IAdministrativeDivisionService;

/**
 * Administrative division service implementation.
 */
@Service
public class AdministrativeDivisionServiceImpl implements IAdministrativeDivisionService
{
    private static final String COUNTRY_CODE = "CN";

    private static final int MAX_PATH_DEPTH = 10;

    @Autowired
    private AdministrativeDivisionMapper administrativeDivisionMapper;

    @Override
    public List<AdministrativeDivision> selectRegionList(String parentCode)
    {
        String normalizedParentCode = StringUtils.trimToEmpty(parentCode);
        return administrativeDivisionMapper.selectRegionList(COUNTRY_CODE, normalizedParentCode);
    }

    @Override
    public List<AdministrativeDivision> selectRegionPath(String code)
    {
        if (StringUtils.isBlank(code))
        {
            return Collections.emptyList();
        }

        LinkedList<AdministrativeDivision> path = new LinkedList<>();
        String currentCode = code.trim();
        for (int depth = 0; depth < MAX_PATH_DEPTH && !COUNTRY_CODE.equals(currentCode); depth++)
        {
            AdministrativeDivision region = administrativeDivisionMapper.selectRegionByCode(COUNTRY_CODE, currentCode);
            if (region == null)
            {
                break;
            }
            path.addFirst(region);
            currentCode = region.getParentCode();
            if (StringUtils.isBlank(currentCode))
            {
                break;
            }
        }
        return path;
    }
}
