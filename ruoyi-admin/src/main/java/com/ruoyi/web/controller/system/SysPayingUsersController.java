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
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.BlindDateProfile;
import com.ruoyi.system.service.IBlindDateProfileService;

/**
 * Paying users / blind_date_profile CRUD
 */
@RestController
@RequestMapping("/system/payingUsers")
public class SysPayingUsersController extends BaseController
{
    @Autowired
    private IBlindDateProfileService blindDateProfileService;

    @PreAuthorize("@ss.hasPermi('system:payingUsers:list')")
    @GetMapping("/list")
    public TableDataInfo list(BlindDateProfile profile)
    {
        startPage();
        List<BlindDateProfile> list = blindDateProfileService.selectBlindDateProfileList(profile);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('system:payingUsers:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable Long id)
    {
        return success(blindDateProfileService.selectBlindDateProfileById(id));
    }

    @PreAuthorize("@ss.hasPermi('system:payingUsers:add')")
    @Log(title = "Paying user profile", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody BlindDateProfile profile)
    {
        if (StringUtils.isEmpty(profile.getPassword()))
        {
            return error("password required");
        }
        if (!blindDateProfileService.checkPhoneUnique(profile))
        {
            return error("phone already exists: " + profile.getPhone());
        }
        profile.setCreateBy(getUsername());
        return toAjax(blindDateProfileService.insertBlindDateProfile(profile));
    }

    @PreAuthorize("@ss.hasPermi('system:payingUsers:edit')")
    @Log(title = "Paying user profile", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody BlindDateProfile profile)
    {
        if (profile.getId() == null)
        {
            return error("id required");
        }
        if (!blindDateProfileService.checkPhoneUnique(profile))
        {
            return error("phone already exists: " + profile.getPhone());
        }
        profile.setUpdateBy(getUsername());
        return toAjax(blindDateProfileService.updateBlindDateProfile(profile));
    }

    @PreAuthorize("@ss.hasPermi('system:payingUsers:remove')")
    @Log(title = "Paying user profile", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(blindDateProfileService.deleteBlindDateProfileByIds(ids));
    }
}
