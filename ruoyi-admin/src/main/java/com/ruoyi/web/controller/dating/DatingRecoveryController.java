package com.ruoyi.web.controller.dating;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.FollowupAction;
import com.ruoyi.system.service.DatingExceptionFollowupService;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import com.ruoyi.system.service.DatingRecoveryService;
import com.ruoyi.system.service.DatingWorkflowService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.ruoyi.system.service.DatingPackageRules.require;

@RestController
@RequestMapping("/dating")
public class DatingRecoveryController extends BaseController
{
    private final DatingRecoveryService recovery;
    private final DatingWorkflowService workflow;
    private final DatingWorkflowMapper tasks;
    private final DatingExceptionFollowupService followup;
    public DatingRecoveryController(DatingRecoveryService recovery, DatingWorkflowService workflow, DatingWorkflowMapper tasks,
        DatingExceptionFollowupService followup)
    { this.recovery = recovery; this.workflow = workflow; this.tasks = tasks; this.followup = followup; }
    private boolean all() { return SecurityUtils.isAdmin() || SecurityUtils.hasPermi("dating:service:all"); }

    @GetMapping("/recovery/overview")
    @PreAuthorize("@ss.hasPermi('dating:recovery:query')")
    public AjaxResult overview() { return success(recovery.overview()); }

    @GetMapping("/recovery/runs")
    @PreAuthorize("@ss.hasPermi('dating:recovery:query')")
    public TableDataInfo runs() { startPage(); return getDataTable(recovery.runs()); }

    @GetMapping("/deduction-exceptions")
    @PreAuthorize("@ss.hasPermi('dating:match:query')")
    public TableDataInfo issues(@RequestParam(required=false) Long pairId,
        @RequestParam(required=false) String kind, @RequestParam(required=false) String handling,
        @RequestParam(defaultValue="false") boolean mine, @RequestParam(defaultValue="false") boolean overdueOnly)
    {
        startPage();
        return getDataTable(recovery.issues(pairId, all() ? null : getUsername(), kind, handling, mine ? getUsername() : null, overdueOnly));
    }

    @GetMapping("/deduction-tasks/{id}/history")
    @PreAuthorize("@ss.hasPermi('dating:match:query')")
    public TableDataInfo history(@PathVariable Long id)
    {
        var task = tasks.selectTask(id);
        require(task != null, "扣次任务不存在");
        workflow.accessiblePair(task.pairId(), getUsername(), all());
        startPage();
        return getDataTable(recovery.history(id));
    }

    @GetMapping("/deduction-tasks/{id}/follow-up")
    @PreAuthorize("@ss.hasPermi('dating:match:query')")
    public AjaxResult followup(@PathVariable Long id)
    { return success(followup.detail(id, getUsername(), all())); }

    @GetMapping("/deduction-tasks/{id}/follow-up/history")
    @PreAuthorize("@ss.hasPermi('dating:match:query')")
    public TableDataInfo followupHistory(@PathVariable Long id)
    {
        followup.checkAccess(id, getUsername(), all());
        startPage();
        return getDataTable(followup.history(id));
    }

    @PostMapping("/deduction-tasks/{id}/follow-up")
    @PreAuthorize("@ss.hasPermi('dating:exception:handle')")
    @Log(title = "异常责任跟进", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    public AjaxResult act(@PathVariable Long id, @RequestBody FollowupAction request)
    {
        followup.act(id, request, getUsername(), all());
        return success();
    }
}
