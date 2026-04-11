package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysPayingUser;

/**
 * 相亲档案 业务层
 */
public interface ISysPayingUserService
{
    /**
     * 根据条件分页查询用户列表
     */
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user);

    /**
     * 校验手机号是否唯一
     * @return true=唯一可用 false=已存在
     */
    public boolean checkPhoneUnique(SysPayingUser user);

    /**
     * 新增用户信息
     */
    public int insertPayingUser(SysPayingUser user);
}