package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.common.core.domain.entity.SysPayingUser;

/**
 * 相亲档案-自身信息 数据层
 */
public interface SysPayingUserMapper
{
    /**
     * 根据条件查询相亲档案列表
     */
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user);
}
