package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.core.domain.entity.SysPayingUser;
import com.ruoyi.system.mapper.SysPayingUserMapper;
import com.ruoyi.system.service.ISysPayingUserService;

/**
 * 相亲档案-自身信息 业务层实现
 */
@Service
public class SysPayingUserServiceImpl implements ISysPayingUserService
{
    @Autowired
    private SysPayingUserMapper payingUserMapper;

    @Override
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user)
    {
        return payingUserMapper.selectPayingUserList(user);
    }
}