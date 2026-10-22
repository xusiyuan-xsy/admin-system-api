package com.ruoyi.web.controller.dating;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import com.ruoyi.system.service.DatingWorkflowService;
import com.ruoyi.system.service.DatingDeductionDispatcher;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dating")
public class DatingWorkflowController extends BaseController
{
    private static final Logger LOG = LoggerFactory.getLogger(DatingWorkflowController.class);
    private final DatingWorkflowService service;
    private final DatingDeductionDispatcher dispatcher;
    public DatingWorkflowController(DatingWorkflowService service, DatingDeductionDispatcher dispatcher)
    { this.service = service; this.dispatcher = dispatcher; }
    private boolean all() { return SecurityUtils.isAdmin() || SecurityUtils.hasPermi("dating:service:all"); }
    private String owner() { return all() ? null : getUsername(); }

    @GetMapping("/assignments")
    @PreAuthorize("@ss.hasAnyPermi('dating:assignment:list,dating:match:add')")
    public TableDataInfo customers(@RequestParam(required=false) String keyword,
        @RequestParam(required=false) String serviceStatus)
    {
        startPage();
        return getDataTable(service.customers(keyword, serviceStatus, owner()));
    }

    @GetMapping("/staff")
    @PreAuthorize("@ss.hasPermi('dating:assignment:edit')")
    public AjaxResult staff(@RequestParam(required=false) String keyword) { return success(service.staff(keyword)); }

    @GetMapping("/users/{id}/assignments")
    @PreAuthorize("@ss.hasPermi('dating:assignment:list')")
    public AjaxResult assignments(@PathVariable Long id) { return success(service.assignments(id, getUsername(), all())); }

    @PostMapping("/users/{id}/assign")
    @PreAuthorize("@ss.hasPermi('dating:assignment:edit')")
    @Log(title="红娘分配与转交", businessType=BusinessType.UPDATE)
    public AjaxResult assign(@PathVariable Long id, @Valid @RequestBody Assign request)
    { service.assign(id, request, getUsername(), all()); return success(); }

    @GetMapping("/matches")
    @PreAuthorize("@ss.hasPermi('dating:match:list')")
    public TableDataInfo pairs(@RequestParam(required=false) String keyword, @RequestParam(required=false) String progress)
    { startPage(); return getDataTable(service.pairs(keyword, progress, owner())); }

    @GetMapping("/matches/{id}")
    @PreAuthorize("@ss.hasPermi('dating:match:query')")
    public AjaxResult detail(@PathVariable Long id) { return success(service.detail(id, getUsername(), all())); }

    @PostMapping("/recommendations")
    @PreAuthorize("@ss.hasPermi('dating:match:add')")
    @Log(title="婚恋配对推荐", businessType=BusinessType.INSERT)
    public AjaxResult recommend(@Valid @RequestBody Recommend request)
    { return success(service.recommend(request, getUsername(), all())); }

    @PostMapping("/recommendations/{id}/intentions")
    @PreAuthorize("@ss.hasPermi('dating:match:intention')")
    @Log(title="配对意向", businessType=BusinessType.UPDATE)
    public AjaxResult intention(@PathVariable Long id, @Valid @RequestBody Intent request)
    { return success(service.intention(id, request, getUsername(), all())); }

    @PostMapping("/matches/{id}/service-events")
    @PreAuthorize("@ss.hasPermi('dating:event:add')")
    @Log(title="服务事实", businessType=BusinessType.INSERT)
    public AjaxResult event(@PathVariable Long id, @Valid @RequestBody ServiceEvent request)
    {
        service.recordEvent(id, request, getUsername(), all());
        return dispatch(id);
    }

    @PostMapping("/matches/{id}/appointments")
    @PreAuthorize("@ss.hasPermi('dating:appointment:add')")
    @Log(title="安排见面", businessType=BusinessType.INSERT)
    public AjaxResult schedule(@PathVariable Long id, @Valid @RequestBody Schedule request)
    { service.schedule(id, request, getUsername(), all()); return success(id); }

    @PutMapping("/appointments/{id}")
    @PreAuthorize("@ss.hasPermi('dating:appointment:edit')")
    @Log(title="见面改期", businessType=BusinessType.UPDATE)
    public AjaxResult reschedule(@PathVariable Long id, @Valid @RequestBody Schedule request)
    { return success(service.reschedule(id, request, getUsername(), all())); }

    @PostMapping("/appointments/{id}/actions")
    @PreAuthorize("@ss.hasPermi('dating:appointment:edit')")
    @Log(title="见面结果", businessType=BusinessType.UPDATE)
    public AjaxResult action(@PathVariable Long id, @Valid @RequestBody AppointmentAction request)
    { return dispatch(service.appointmentAction(id, request, getUsername(), all())); }

    @PostMapping("/deduction-tasks/{id}/retry")
    @PreAuthorize("@ss.hasPermi('dating:deduction:retry')")
    @Log(title="扣次重试", businessType=BusinessType.UPDATE)
    public AjaxResult retry(@PathVariable Long id, @Valid @RequestBody Retry request)
    { return dispatch(service.retry(id, request, getUsername(), all())); }

    private AjaxResult dispatch(Long pairId)
    {
        // Business facts have already committed. A dispatcher outage must not report them as unsaved.
        try { dispatcher.dispatch(pairId); }
        catch (RuntimeException ex) { LOG.error("Saved dating facts await recovery for pair {}", pairId, ex); }
        return success(pairId);
    }
}
