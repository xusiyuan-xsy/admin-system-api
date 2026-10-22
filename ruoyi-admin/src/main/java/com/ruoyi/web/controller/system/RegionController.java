package com.ruoyi.web.controller.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.service.IAdministrativeDivisionService;

/**
 * Region options for province/city cascaders.
 */
@RestController
@RequestMapping("/system/region")
public class RegionController extends BaseController
{
    @Autowired
    private IAdministrativeDivisionService administrativeDivisionService;

    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) String parentCode)
    {
        return success(administrativeDivisionService.selectRegionList(parentCode));
    }

    @GetMapping("/path/{code}")
    public AjaxResult path(@PathVariable String code)
    {
        return success(administrativeDivisionService.selectRegionPath(code));
    }
}
