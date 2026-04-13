package com.ruoyi.system.service.impl;

import java.util.List;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysPayingUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.bean.BeanValidators;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.mapper.SysPayingUserMapper;
import com.ruoyi.system.service.ISysPayingUserService;

/**
 * 档案信息 业务层处理
 */
@Service
public class SysPayingUserServiceImpl implements ISysPayingUserService
{
    private static final Logger log = LoggerFactory.getLogger(SysPayingUserServiceImpl.class);
    
    @Autowired
    private SysPayingUserMapper payingUserMapper;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    protected Validator validator;

    /**
     * 根据条件分页查询用户列表
     * 
     * @param user 档案信息
     * @return 档案信息集合信息
     */
    @Override
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user)
    {
        return payingUserMapper.selectPayingUserList(user);
    }

    /**
     * 校验手机号是否唯一
     * 
     * @param user 档案信息
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
     * 通过用户ID查询用户
     * 
     * @param id 用户ID
     * @return 用户对象信息
     */
    @Override
    public SysPayingUser selectPayingUserById(Long id)
    {
        return payingUserMapper.selectPayingUserById(id);
    }
    /**
     * 新增用户信息
     * 
     * @param user 档案信息
     * @return 结果
     */
    @Override
    @Transactional
    public int insertPayingUser(SysPayingUser user)
    {
        return payingUserMapper.insertPayingUser(user);
    }

    /**
     * 修改用户信息
     * 
     * @param user 档案信息
     * @return 结果
     */
    @Override
    public int updatePayingUser(SysPayingUser user)
    {
        return payingUserMapper.updatePayingUser(user);
    }

    /**
     * 删除用户信息
     * 
     * @param ids 用户ID
     * @return 结果
     */
    @Override
    public int deletePayingUserByIds(Long[] ids)
    {
        return payingUserMapper.deletePayingUserByIds(ids);
    }
    /**
     * 导入用户数据
     * 
     * @param userList 用户数据列表
     * @param isUpdateSupport 是否更新支持，如果已存在，则进行更新数据
     * @param operName 操作用户
     * @return 结果
     */
    @Override
    public String importPayingUser(List<SysPayingUser> userList, Boolean isUpdateSupport, String operName)
    {
        if (StringUtils.isNull(userList) || userList.size() == 0)
        {
            throw new ServiceException("导入用户数据不能为空！");
        }
        int successNum = 0;
        int failureNum = 0;
        StringBuilder successMsg = new StringBuilder();
        StringBuilder failureMsg = new StringBuilder();
        for (SysPayingUser user : userList)
        {
            try
            {
                // 验证是否存在这个用户
                SysPayingUser u = payingUserMapper.selectPayingUserById(user.getId());
                if (StringUtils.isNull(u))
                {
                    BeanValidators.validateWithException(validator, user);
                    String password = configService.selectConfigByKey("sys.user.initPassword");
                    user.setPassword(SecurityUtils.encryptPassword(password));
                    user.setCreateBy(operName);
                    payingUserMapper.insertPayingUser(user);
                    successNum++;
                    successMsg.append("<br/>" + successNum + "、用户 " + user.getPhone() + " 导入成功");
                }
                else if (isUpdateSupport)
                {
                    BeanValidators.validateWithException(validator, user);
                    user.setId(u.getId());
                    user.setUpdateBy(operName);
                    payingUserMapper.updatePayingUser(user);
                    successNum++;
                    successMsg.append("<br/>" + successNum + "、用户 " + user.getPhone() + " 更新成功");
                }
                else
                {
                    failureNum++;
                    failureMsg.append("<br/>" + failureNum + "、用户 " + user.getPhone() + " 已存在");
                }
            }
            catch (Exception e)
            {
                failureNum++;
                String msg = "<br/>" + failureNum + "、用户 " + user.getPhone() + " 导入失败：";
                failureMsg.append(msg + e.getMessage());
                log.error(msg, e);
            }
        }
        if (failureNum > 0)
        {
            failureMsg.insert(0, "很抱歉，导入失败！共 " + failureNum + " 条数据格式不正确，错误如下：");
            throw new ServiceException(failureMsg.toString());
        }
        else
        {
            successMsg.insert(0, "恭喜您，数据已全部导入成功！共 " + successNum + " 条，数据如下：");
        }
        return successMsg.toString();
    }
}