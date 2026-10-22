package com.ruoyi.system.service.impl;

import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import com.ruoyi.common.utils.DatingPhotoReferences;
import com.ruoyi.system.service.DatingPhotoStorage;
import com.ruoyi.system.service.DatingArchiveAccess;
import com.ruoyi.system.service.DatingRequirementRules;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
import com.ruoyi.common.core.domain.entity.SysPayingUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.bean.BeanValidators;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.system.mapper.SysPayingUserMapper;
import com.ruoyi.system.domain.vo.SysPayingUserBirthYearStatVo;
import com.ruoyi.system.domain.vo.SysPayingUserStatsVo;
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

    @Autowired
    private DatingArchiveAccess access;

    @Autowired
    private DatingPhotoStorage photoStorage;

    private SysPayingUser protectPhotos(SysPayingUser user)
    {
        if (user != null && user.getPhotos() != null)
        {
            List<String> visible = user.getPhotos().stream()
                .filter(ref -> DatingPhotoReferences.belongsTo(ref, user.getId())).toList();
            user.setLegacyPhotoCount(user.getPhotos().size() - visible.size());
            user.setPhotos(visible);
        }
        return user;
    }

    private void validatePhotos(SysPayingUser user)
    {
        if (user.getPhotos() == null) return;
        if (user.getPhotos().size() > 9
            || new HashSet<>(user.getPhotos()).size() != user.getPhotos().size())
            throw new ServiceException("最多保存9张照片且不得重复");
        for (String reference : user.getPhotos())
        {
            if (!DatingPhotoReferences.belongsTo(reference, user.getId()))
                throw new ServiceException("照片不存在或无权访问");
            photoStorage.validate(user.getId(), reference);
        }
    }

    /**
     * 根据条件分页查询用户列表
     * 
     * @param user 档案信息
     * @return 档案信息集合信息
     */
    @Override
    public List<SysPayingUser> selectPayingUserList(SysPayingUser user)
    {
        access.scope(user);
        List<SysPayingUser> list = payingUserMapper.selectPayingUserList(user);
        list.forEach(this::protectPhotos);
        return list;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SysPayingUserStatsVo selectPayingUserStats()
    {
        SysPayingUser query = new SysPayingUser();
        access.scope(query);
        SysPayingUserStatsVo vo = payingUserMapper.selectPayingUserStatsOverview(query);
        if (vo == null)
        {
            vo = new SysPayingUserStatsVo();
        }
        List<SysPayingUserBirthYearStatVo> byYear = payingUserMapper.selectPayingUserBirthYearStats(query);
        vo.setSuccessCaseCount(payingUserMapper.selectSuccessCaseCount(query));
        vo.setBirthYearStats(byYear != null ? byYear : Collections.emptyList());
        return vo;
    }

    /**
     * 根据择偶要求搜索用户（用于匹配列表）
     *
     * @param requirement 择偶要求
     * @return 用户列表
     */
    @Override
    public List<SysPayingUser> selectPayingUserListByRequirement(SysPayingRequirement requirement)
    {
        DatingRequirementRules.validate(requirement);
        access.scope(requirement);
        List<SysPayingUser> list = payingUserMapper.selectPayingUserListByRequirement(requirement);
        list.forEach(this::protectPhotos);
        return list;
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
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SysPayingUser selectPayingUserById(Long id)
    {
        access.check(id, false);
        return protectPhotos(payingUserMapper.selectPayingUserById(id));
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
        if (user.getPhotos() != null && !user.getPhotos().isEmpty())
            throw new ServiceException("请先保存档案再上传照片");
        user.setCreateBy(SecurityUtils.getUsername());
        String password = user.getPassword();
        if (password == null || password.isBlank())
            password = configService.selectConfigByKey("sys.user.initPassword");
        user.setPassword(SecurityUtils.encryptPassword(password));
        if (!checkPhoneUnique(user)) throw new ServiceException("手机号码已存在");
        return payingUserMapper.insertPayingUser(user);
    }

    /**
     * 修改用户信息
     * 
     * @param user 档案信息
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updatePayingUser(SysPayingUser user)
    {
        access.check(user.getId(), true);
        validatePhotos(user);
        if (user.getPhotos() != null)
        {
            // Keep legacy references for a deliberate migration, never silently erase them.
            List<String> photos = new ArrayList<>(user.getPhotos());
            List<String> existing = payingUserMapper.selectPayingUserById(user.getId()).getPhotos();
            if (existing != null) existing.stream()
                .filter(ref -> !DatingPhotoReferences.belongsTo(ref, user.getId())).forEach(photos::add);
            user.setPhotos(photos);
        }
        user.setUpdateBy(SecurityUtils.getUsername());
        if (!checkPhoneUnique(user)) throw new ServiceException("手机号码已存在");
        return payingUserMapper.updatePayingUser(user);
    }

    /**
     * 删除用户信息
     * 
     * @param ids 用户ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deletePayingUserByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0 || Arrays.stream(ids).anyMatch(java.util.Objects::isNull))
            throw new ServiceException("请选择客户");
        Arrays.stream(ids).distinct().sorted().forEach(access::checkDeletable);
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
    @Transactional(rollbackFor = Exception.class)
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
                SysPayingUser u = payingUserMapper.checkPhoneUnique(user.getPhone());
                if (StringUtils.isNull(u))
                {
                    BeanValidators.validateWithException(validator, user);
                    user.setId(null);
                    user.setPassword(null);
                    insertPayingUser(user);
                    successNum++;
                    successMsg.append("<br/>第 " + successNum + " 行导入成功");
                }
                else if (Boolean.TRUE.equals(isUpdateSupport))
                {
                    BeanValidators.validateWithException(validator, user);
                    user.setId(u.getId());
                    user.setUpdateBy(operName);
                    updatePayingUser(user);
                    successNum++;
                    successMsg.append("<br/>第 " + successNum + " 行更新成功");
                }
                else
                {
                    failureNum++;
                    failureMsg.append("<br/>第 " + (successNum + failureNum) + " 行手机号已存在");
                }
            }
            catch (Exception e)
            {
                failureNum++;
                String msg = "<br/>第 " + (successNum + failureNum) + " 行导入失败，请检查格式、重复手机号及客户权限";
                failureMsg.append(msg);
                log.warn("Archive import rejected at row {}", successNum + failureNum);
            }
        }
        if (failureNum > 0)
        {
            failureMsg.insert(0, "导入已全部回滚，共 " + failureNum + " 条失败：");
            throw new ServiceException(failureMsg.toString());
        }
        else
        {
            successMsg.insert(0, "恭喜您，数据已全部导入成功！共 " + successNum + " 条，数据如下：");
        }
        return successMsg.toString();
    }
}
