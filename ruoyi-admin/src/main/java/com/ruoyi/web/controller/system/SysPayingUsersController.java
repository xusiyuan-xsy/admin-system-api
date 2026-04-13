package com.ruoyi.web.controller.system;

import java.util.List;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.ArrayUtils;
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
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.core.domain.entity.SysPayingUser;
import com.ruoyi.system.service.ISysPayingUserService;

/**
 * Paying users / sys_paying_user CRUD
 */
@RestController
@RequestMapping("/system/payingUsers")
public class SysPayingUsersController extends BaseController
{
    @Autowired
    private ISysPayingUserService payingUserService;


     /**
     * 获取用户列表
     */
    @PreAuthorize("@ss.hasPermi('system:payingUsers:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysPayingUser user)
    {
        startPage();
        List<SysPayingUser> list = payingUserService.selectPayingUserList(user);
        return getDataTable(list);
    }

    @Log(title = "用户管理", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('system:payingUsers:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysPayingUser user)
    {
        List<SysPayingUser> list = payingUserService.selectPayingUserList(user);
        ExcelUtil<SysPayingUser> util = new ExcelUtil<SysPayingUser>(SysPayingUser.class);
        util.exportExcel(response, list, "用户数据");
    }

    @Log(title = "用户管理", businessType = BusinessType.IMPORT)
    @PreAuthorize("@ss.hasPermi('system:payingUsers:import')")
    @PostMapping("/importData")
    public AjaxResult importData(MultipartFile file, boolean updateSupport) throws Exception
    {
        ExcelUtil<SysPayingUser> util = new ExcelUtil<SysPayingUser>(SysPayingUser.class);
        List<SysPayingUser> userList = util.importExcel(file.getInputStream());
        String operName = getUsername();
        String message = payingUserService.importPayingUser(userList, updateSupport, operName);
        return success(message);
    }
    
    @PostMapping("/importTemplate")
    public void importTemplate(HttpServletResponse response)
    {
        ExcelUtil<SysPayingUser> util = new ExcelUtil<SysPayingUser>(SysPayingUser.class);
        util.importTemplateExcel(response, "用户数据");
    }

    /**
     * 根据用户编号获取详细信息
     */
    @PreAuthorize("@ss.hasPermi('system:payingUsers:query')")
    @GetMapping(value = { "/", "/{id}" })
    public AjaxResult getInfo(@PathVariable(value = "id", required = false) Long id)
    {
        AjaxResult ajax = AjaxResult.success();
        if (StringUtils.isNotNull(id))
        {
            SysPayingUser user = payingUserService.selectPayingUserById(id);
            ajax.put(AjaxResult.DATA_TAG, user);
        }
        return ajax;
    }

    /**
     * 新增用户
     */
    @PreAuthorize("@ss.hasPermi('system:payingUsers:add')")
    @Log(title = "用户管理", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysPayingUser user)
    {
        if (StringUtils.isNotEmpty(user.getPhone()) && !payingUserService.checkPhoneUnique(user))
        {
            return error("新增用户失败，手机号码已存在");
        }
        user.setCreateBy(getUsername());
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        return toAjax(payingUserService.insertPayingUser(user));
    }

    /**
     * 修改用户
     */
    @PreAuthorize("@ss.hasPermi('system:payingUsers:edit')")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysPayingUser user)
    {
        if (StringUtils.isNotEmpty(user.getPhone()) && !payingUserService.checkPhoneUnique(user))
        {
            return error("修改用户失败，手机号码已存在");
        }
        user.setUpdateBy(getUsername());
        return toAjax(payingUserService.updatePayingUser(user));
    }

    /**
     * 删除用户
     */
    @PreAuthorize("@ss.hasPermi('system:payingUsers:remove')")
    @Log(title = "用户管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(payingUserService.deletePayingUserByIds(ids));
    }
}
