package com.ruoyi.system.service;

import java.util.Date;
import java.util.List;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
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
     * 根据择偶要求搜索用户（用于匹配列表）
     */
    public List<SysPayingUser> selectPayingUserListByRequirement(SysPayingRequirement requirement);

    /**
     * 校验手机号是否唯一
     * @return true=唯一可用 false=已存在
     */
    public boolean checkPhoneUnique(SysPayingUser user);
    /**
     * 通过用户ID查询用户
     * 
     * @param userId 用户ID
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
     * 批量删除用户信息
     * 
     * @param ids 需要删除的用户ID
     * @return 结果
     */
    public int deletePayingUserByIds(Long[] ids);


    /**
     * 导入用户数据
     * 
     * @param userList 用户数据列表
     * @param isUpdateSupport 是否更新支持，如果已存在，则进行更新数据
     * @param operName 操作用户
     * @return 结果
     */
    public String importPayingUser(List<SysPayingUser> userList, Boolean isUpdateSupport, String operName);
}