package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysPayingUser;

/**
 * 相亲档案信息 数据层
 */
public interface SysPayingUserMapper
{
    /**
     * 根据条件分页查询用户列表
     */
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user);

    /**
     * 校验手机号是否唯一
     */
    public SysPayingUser checkPhoneUnique(String phone);

    /**
     * 新增用户信息
     */
    public int insertPayingUser(SysPayingUser user);
}