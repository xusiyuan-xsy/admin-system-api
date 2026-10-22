package com.ruoyi.web.controller.dating;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.DatingServiceOption;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import com.ruoyi.system.service.DatingBillingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dating")
public class DatingBillingController extends BaseController
{
    private final DatingBillingService service;
    public DatingBillingController(DatingBillingService service) { this.service = service; }
    private boolean all() { return SecurityUtils.isAdmin() || SecurityUtils.hasPermi("dating:billing:all"); }
    private String owner() { return all() ? null : getUsername(); }

    @GetMapping("/service-options")
    @PreAuthorize("@ss.hasAnyPermi('dating:option:list,system:datingPackage:add,system:datingPackage:edit,dating:order:add')")
    public AjaxResult options(@RequestParam(required = false) String status)
    {
        return success(service.options(status));
    }

    @PostMapping("/service-options")
    @PreAuthorize("@ss.hasPermi('dating:option:add')")
    @Log(title = "服务选项", businessType = BusinessType.INSERT)
    public AjaxResult addOption(@Valid @RequestBody DatingServiceOption option)
    {
        option.setOptionId(null);
        service.saveOption(option, getUsername());
        return success();
    }

    @PutMapping("/service-options/{id}")
    @PreAuthorize("@ss.hasPermi('dating:option:edit')")
    @Log(title = "服务选项", businessType = BusinessType.UPDATE)
    public AjaxResult editOption(@PathVariable Long id, @Valid @RequestBody DatingServiceOption option)
    {
        option.setOptionId(id);
        service.saveOption(option, getUsername());
        return success();
    }

    @GetMapping("/customers")
    @PreAuthorize("@ss.hasPermi('dating:order:add')")
    public TableDataInfo customers(@RequestParam(required = false) String keyword)
    {
        startPage();
        return getDataTable(service.customers(keyword, owner()));
    }

    @GetMapping("/packages")
    @PreAuthorize("@ss.hasPermi('dating:order:add')")
    public AjaxResult packages() { return success(service.availablePackages()); }

    @GetMapping("/orders")
    @PreAuthorize("@ss.hasPermi('dating:order:list')")
    public TableDataInfo orders(@RequestParam(required = false) String orderNo,
        @RequestParam(required = false) String status, @RequestParam(required = false) Long userId)
    {
        startPage();
        return getDataTable(service.orders(orderNo, status, userId, owner()));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("@ss.hasPermi('dating:order:query')")
    public AjaxResult detail(@PathVariable Long id) { return success(service.detail(id, getUsername(), all())); }

    @PostMapping("/orders")
    @PreAuthorize("@ss.hasPermi('dating:order:add')")
    @Log(title = "婚恋订单", businessType = BusinessType.INSERT)
    public AjaxResult create(@Valid @RequestBody CreateOrder request)
    {
        return success(service.create(request, getUsername(), all()));
    }

    @PostMapping("/orders/{id}/confirm-payment")
    @PreAuthorize("@ss.hasPermi('dating:order:confirmPayment')")
    @Log(title = "确认收款并开通权益", businessType = BusinessType.UPDATE)
    public AjaxResult confirm(@PathVariable Long id, @Valid @RequestBody ConfirmPayment request)
    {
        return success(service.confirm(id, request, getUsername(), all()));
    }

    @PostMapping("/orders/{id}/cancel")
    @PreAuthorize("@ss.hasPermi('dating:order:cancel')")
    @Log(title = "取消婚恋订单", businessType = BusinessType.UPDATE)
    public AjaxResult cancel(@PathVariable Long id, @Valid @RequestBody CancelOrder request)
    {
        service.cancel(id, request, getUsername(), all());
        return success();
    }

    @GetMapping("/entitlements")
    @PreAuthorize("@ss.hasPermi('dating:entitlement:list')")
    public TableDataInfo entitlements(@RequestParam(required = false) Long userId)
    {
        startPage();
        return getDataTable(service.entitlements(userId, owner()));
    }

    @GetMapping("/entitlements/{id}")
    @PreAuthorize("@ss.hasPermi('dating:entitlement:list')")
    public AjaxResult entitlement(@PathVariable Long id)
    {
        return success(service.entitlementDetail(id, owner()));
    }

    @GetMapping("/integrity-issues")
    @PreAuthorize("@ss.hasPermi('dating:entitlement:list')")
    public TableDataInfo integrityIssues(@RequestParam(required = false) Long userId)
    {
        startPage();
        return getDataTable(service.integrityIssues(userId, owner()));
    }
}
