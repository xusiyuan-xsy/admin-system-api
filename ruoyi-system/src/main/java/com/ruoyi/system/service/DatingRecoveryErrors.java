package com.ruoyi.system.service;

import java.sql.SQLException;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.dao.*;

/** Only allowlisted categories may be persisted or returned to clients. */
public final class DatingRecoveryErrors
{
    private DatingRecoveryErrors() {}
    public static String code(RuntimeException error)
    {
        if (error instanceof DataIntegrityViolationException) return "DATA_CONFLICT";
        if (error instanceof TransientDataAccessException) return "DATABASE_TRANSIENT";
        if (error instanceof DataAccessException)
        {
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause())
                if (cause instanceof SQLException sql
                    && ((sql.getSQLState() != null && sql.getSQLState().startsWith("23"))
                        || ("HY000".equals(sql.getSQLState()) && sql.getErrorCode() == 3819)))
                    return "DATA_CONFLICT";
            return "DATABASE_ERROR";
        }
        if (error instanceof ServiceException) return "BUSINESS_INVARIANT";
        return "PROCESSING_ERROR";
    }
}
