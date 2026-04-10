package com.ruoyi.system.service;

import java.util.Date;
import java.util.List;
import com.ruoyi.common.core.domain.entity.SysPayingUser;


/**
 * 用户 业务层
 * 
 * @author ruoyi
 */
public interface ISysPayingUserService
{
     /**
     * 根据条件分页查询用户列表
     * 
     * @param user 相亲档案信息
     * @return 相亲档案信息集合信息
     */
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user);
}