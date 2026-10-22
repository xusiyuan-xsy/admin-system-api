package com.ruoyi.web.controller.dating;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.DatingSuccess.CloseRequest;
import com.ruoyi.system.service.DatingSuccessService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.ruoyi.system.service.DatingPackageRules.require;

@RestController
@RequestMapping("/dating")
public class DatingSuccessController extends BaseController
{
    private final DatingSuccessService service;
    public DatingSuccessController(DatingSuccessService service) { this.service=service; }
    private boolean all() { return SecurityUtils.isAdmin() || SecurityUtils.hasPermi("dating:service:all"); }
    private String owner() { return all() ? null : getUsername(); }
    private Date start(LocalDate date) { return date==null ? null : Date.from(date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant()); }
    private void range(LocalDate from, LocalDate to)
    { require(from==null || to==null || !from.isAfter(to), "开始日期不能晚于结束日期"); }

    @GetMapping("/success-policy")
    @PreAuthorize("@ss.hasPermi('dating:success:close')")
    public AjaxResult policy() { return success(Map.of("enabled",service.enabled())); }

    @PostMapping("/matches/{id}/close-success")
    @PreAuthorize("@ss.hasPermi('dating:success:close')")
    @Log(title="婚恋成功结案",businessType=BusinessType.UPDATE)
    public AjaxResult close(@PathVariable Long id, @Valid @RequestBody CloseRequest request)
    { return success(service.close(id, request, getUsername(), all())); }

    @GetMapping("/success-cases")
    @PreAuthorize("@ss.hasPermi('dating:success:list')")
    public TableDataInfo list(@RequestParam(required=false) String keyword,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) Long matchmakerId)
    {
        range(from,to);
        startPage();
        return getDataTable(service.list(keyword,start(from),start(to==null ? null : to.plusDays(1)),matchmakerId,owner()));
    }

    @GetMapping("/success-cases/stats")
    @PreAuthorize("@ss.hasPermi('dating:success:list')")
    public AjaxResult stats(@RequestParam(required=false) String keyword,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) Long matchmakerId)
    {
        range(from,to);
        return success(service.stats(keyword,start(from),start(to==null ? null : to.plusDays(1)),matchmakerId,owner()));
    }

    @GetMapping("/success-cases/matchmakers")
    @PreAuthorize("@ss.hasPermi('dating:success:list')")
    public AjaxResult matchmakers() { return success(service.matchmakers(owner())); }

    @GetMapping("/success-cases/{id}")
    @PreAuthorize("@ss.hasPermi('dating:success:query')")
    public AjaxResult detail(@PathVariable Long id) { return success(service.detail(id,getUsername(),all())); }
}
