package com.ruoyi.system.service;

import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.mapper.DatingArchiveMapper;
import org.springframework.stereotype.Service;

/** Archive access follows current assignment, falling back to its creator only when unassigned. */
@Service
public class DatingArchiveAccess
{
    private final DatingArchiveMapper mapper;

    public DatingArchiveAccess(DatingArchiveMapper mapper) { this.mapper = mapper; }

    public String owner()
    {
        return SecurityUtils.isAdmin() || SecurityUtils.hasPermi("dating:service:all")
            ? null : SecurityUtils.getUsername();
    }

    public void scope(BaseEntity query)
    {
        // Always overwrite caller-supplied scope, including a forged null value.
        query.getParams().put("archiveOwner", owner());
    }

    public void check(Long id, boolean lock)
    {
        if (id == null || (lock && mapper.lockCustomer(id) == null)
            || mapper.accessible(id, owner()) == 0)
            throw new ServiceException("客户不存在或无权访问");
    }

    public void checkDeletable(Long id)
    {
        check(id, true);
        if (mapper.hasHistory(id) != 0)
            throw new ServiceException("客户已有业务历史，不可删除档案，请使用档案停用");
    }
}
