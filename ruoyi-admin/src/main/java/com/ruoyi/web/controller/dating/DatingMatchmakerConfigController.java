package com.ruoyi.web.controller.dating;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.DatingMatchmakerConfig;
import com.ruoyi.system.service.DatingMatchmakerConfigService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dating/matchmaker-configs")
public class DatingMatchmakerConfigController extends BaseController
{
    private final DatingMatchmakerConfigService service;
    public DatingMatchmakerConfigController(DatingMatchmakerConfigService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('dating:matchmakerConfig:list')")
    public TableDataInfo list(@RequestParam(required = false) String name)
    {
        startPage();
        try { return getDataTable(service.list(name)); }
        finally { clearPage(); }
    }
    @GetMapping("/active")
    @PreAuthorize("@ss.hasAnyPermi('dating:matchmakerConfig:list,system:payingRequirement:add,system:payingRequirement:edit,system:payingUsers:edit')")
    public AjaxResult active() { return success(service.rules()); }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('dating:matchmakerConfig:add')")
    @Log(title = "红娘功能配置", businessType = BusinessType.INSERT)
    public AjaxResult create(@RequestBody DatingMatchmakerConfig config)
    { return success(service.create(config, getUsername())); }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('dating:matchmakerConfig:edit')")
    @Log(title = "红娘功能配置", businessType = BusinessType.UPDATE)
    public AjaxResult update(@PathVariable Long id, @RequestBody DatingMatchmakerConfig config)
    {
        config.setConfigId(id);
        return success(service.update(config, getUsername()));
    }
    public record DefaultRequest(Long expectedDefaultId, Long version) {}
    @PutMapping("/{id}/default")
    @PreAuthorize("@ss.hasPermi('dating:matchmakerConfig:default')")
    @Log(title = "红娘默认配置切换", businessType = BusinessType.UPDATE)
    public AjaxResult setDefault(@PathVariable Long id, @RequestBody DefaultRequest request)
    {
        service.setDefault(id, request.expectedDefaultId(), request.version(), getUsername());
        return success();
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('dating:matchmakerConfig:remove')")
    @Log(title = "红娘功能配置", businessType = BusinessType.DELETE)
    public AjaxResult delete(@PathVariable Long id, @RequestParam Long version)
    {
        service.delete(id, version, getUsername());
        return success();
    }
}
