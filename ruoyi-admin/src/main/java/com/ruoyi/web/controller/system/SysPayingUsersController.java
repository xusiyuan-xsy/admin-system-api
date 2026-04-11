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
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
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
            return error("新增用户'" + user.getPhone() + "'失败，手机号码已存在");
        }
        user.setCreateBy(getUsername());
        user.setPassword(SecurityUtils.encryptPassword(user.getPassword()));
        return toAjax(payingUserService.insertPayingUser(user));
    }

   
}
