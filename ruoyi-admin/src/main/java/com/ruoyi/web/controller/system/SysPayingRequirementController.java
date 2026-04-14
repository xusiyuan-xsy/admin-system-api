package com.ruoyi.web.controller.system;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
import com.ruoyi.system.service.ISysPayingRequirementService;
/**
 * 档案-要求 CRUD
 * 路由前缀：/system/payingRequirement
 */
@RestController
@RequestMapping("/system/payingRequirement")
public class SysPayingRequirementController extends BaseController
{
    @Autowired
    private ISysPayingRequirementService requirementService;
    /**
     * 根据 payingId 查询要求信息
     */
    @PreAuthorize("@ss.hasPermi('system:payingRequirement:query')")
    @GetMapping("/{payingId}")
    public AjaxResult getInfo(@PathVariable Long payingId)
    {
        return success(requirementService.selectRequirementByPayingId(payingId));
    }
     /**
     * 新增要求信息
     */
    @PreAuthorize("@ss.hasPermi('system:payingRequirement:add')")
    @Log(title = "档案要求管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysPayingRequirement requirement)
    {
        requirement.setCreateBy(getUsername());
        return toAjax(requirementService.insertRequirement(requirement));
    }
    /**
     * 修改要求信息（按 payingId 更新）
     */
    @PreAuthorize("@ss.hasPermi('system:payingRequirement:edit')")
    @Log(title = "档案要求管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysPayingRequirement requirement)
    {
        requirement.setUpdateBy(getUsername());
        return toAjax(requirementService.updateRequirement(requirement));
    }
}