package com.ruoyi.web.controller.dating;

import java.sql.SQLException;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.controller.system.DatingPackageController;
import com.ruoyi.web.controller.system.SysPayingRequirementController;
import com.ruoyi.web.controller.system.SysPayingUsersController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {DatingBillingController.class, DatingWorkflowController.class,
    DatingSuccessController.class, DatingPackageController.class, DatingRecoveryController.class,
    SysPayingUsersController.class, SysPayingRequirementController.class})
public class DatingDataExceptionHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(DatingDataExceptionHandler.class);

    @ExceptionHandler(DataAccessException.class)
    public AjaxResult handle(DataAccessException error, HttpServletRequest request)
    {
        LOG.error("Dating database operation failed at {}", request.getRequestURI(), error);
        for (Throwable cause = error; cause != null; cause = cause.getCause())
        {
            if (cause instanceof SQLException sql
                && ("42S02".equals(sql.getSQLState()) || "42S22".equals(sql.getSQLState())
                    || sql.getErrorCode() == 1146 || sql.getErrorCode() == 1054))
                return AjaxResult.error("婚恋数据库结构缺失或与当前版本不兼容，请管理员检查增量迁移");
        }
        if (error instanceof DataIntegrityViolationException)
            return AjaxResult.error("业务记录冲突，请刷新核对订单、凭证和服务状态后再操作");
        return AjaxResult.error("婚恋数据操作暂未完成，请刷新核对处理结果后重试");
    }
}
