package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
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
     * 根据择偶要求搜索用户（用于匹配列表）
     */
    public List<SysPayingUser> selectPayingUserListByRequirement(SysPayingRequirement requirement);

    /**
     * 校验手机号是否唯一
     */
    public SysPayingUser checkPhoneUnique(String phone);
    /**
     * 通过用户ID查询用户
     * 
     * @param id 用户ID
     * @return 用户对象信息
     */
    public SysPayingUser selectPayingUserById(Long id);
    /**
     * 新增用户信息
     */
    public int insertPayingUser(SysPayingUser user);
    /**
     * 修改用户信息
     */
    public int updatePayingUser(SysPayingUser user);
    /**
     * 删除用户信息
     */
    public int deletePayingUserByIds(Long[] ids);
}