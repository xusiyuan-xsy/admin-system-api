package com.ruoyi.web.controller.system;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.service.IDatingPackageService;

/**
 * 套餐管理
 * 路由前缀：/system/datingPackage
 */
@RestController
@RequestMapping("/system/datingPackage")
public class DatingPackageController extends BaseController
{
    @Autowired
    private IDatingPackageService datingPackageService;

    /**
     * 查询套餐列表（分页）
     * 支持筛选：packageName、packageType、status
     */
    @PreAuthorize("@ss.hasPermi('system:datingPackage:list')")
    @GetMapping("/list")
    public TableDataInfo list(DatingPackage datingPackage)
    {
        startPage();
        List<DatingPackage> list = datingPackageService.selectDatingPackageList(datingPackage);
        return getDataTable(list);
    }

    /**
     * 根据套餐ID查询套餐详情
     */
    @PreAuthorize("@ss.hasPermi('system:datingPackage:query')")
    @GetMapping("/{packageId}")
    public AjaxResult getInfo(@PathVariable Long packageId)
    {
        return success(datingPackageService.selectDatingPackageById(packageId));
    }

    /**
     * 新增套餐
     */
    @PreAuthorize("@ss.hasPermi('system:datingPackage:add')")
    @Log(title = "套餐管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody DatingPackage datingPackage)
    {
        datingPackage.setCreateBy(getUsername());
        return toAjax(datingPackageService.insertDatingPackage(datingPackage));
    }

    /**
     * 修改套餐
     */
    @PreAuthorize("@ss.hasPermi('system:datingPackage:edit')")
    @Log(title = "套餐管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody DatingPackage datingPackage)
    {
        datingPackage.setUpdateBy(getUsername());
        return toAjax(datingPackageService.updateDatingPackage(datingPackage));
    }

    /**
     * 删除套餐（单个或批量）
     */
    @PreAuthorize("@ss.hasPermi('system:datingPackage:remove')")
    @Log(title = "套餐管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{packageIds}")
    public AjaxResult remove(@PathVariable Long[] packageIds)
    {
        return toAjax(datingPackageService.deleteDatingPackageByIds(packageIds));
    }
}
