package com.ruoyi.system.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysPayingUser;
import com.ruoyi.common.utils.StringUtils;
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

    /**
     * 根据条件分页查询用户列表
     * 
     * @param user 相亲档案信息
     * @return 相亲档案信息集合信息
     */
    @Override
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user)
    {
        return payingUserMapper.selectPayingUserList(user);
    }

    /**
     * 校验手机号是否唯一
     * 
     * @param user 相亲档案信息
     * @return 结果
     */
    @Override
    public boolean checkPhoneUnique(SysPayingUser user)
    {
        Long userId = StringUtils.isNull(user.getId()) ? -1L : user.getId();
        SysPayingUser info = payingUserMapper.checkPhoneUnique(user.getPhone());
        if (StringUtils.isNotNull(info) && info.getId().longValue() != userId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * 新增用户信息
     * 
     * @param user 相亲档案信息
     * @return 结果
     */
    @Override
    @Transactional
    public int insertPayingUser(SysPayingUser user)
    {
        return payingUserMapper.insertPayingUser(user);
    }
}