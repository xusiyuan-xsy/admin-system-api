package com.ruoyi.web;

import java.sql.SQLException;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.system.service.ISysPayingUserService;
import com.ruoyi.web.controller.dating.DatingDataExceptionHandler;
import com.ruoyi.web.controller.system.SysPayingUsersController;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests MVC error rendering only; method-security is covered separately. */
class DatingDataExceptionTest
{
    ISysPayingUserService service;
    MockMvc mvc;
    @BeforeEach void setup()
    {
        service = mock(ISysPayingUserService.class);
        var controller = new SysPayingUsersController();
        ReflectionTestUtils.setField(controller, "payingUserService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new DatingDataExceptionHandler(), new GlobalExceptionHandler()).build();
    }
    @Test void missingTablesAndColumnsGiveMigrationMessageWithoutSql() throws Exception
    {
        for (String state : new String[]{"42S02", "42S22"})
        {
            when(service.selectPayingUserStats()).thenThrow(
                new BadSqlGrammarException("private task", "select private_fields", new SQLException("private detail", state)));
            mvc.perform(get("/system/payingUsers/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("婚恋数据库结构缺失或与当前版本不兼容，请管理员检查增量迁移"));
            reset(service);
        }
    }
    @Test void duplicateRecordsDoNotLeakDatabaseDetails() throws Exception
    {
        when(service.selectPayingUserStats()).thenThrow(new DuplicateKeyException("private receipt number and SQL"));
        mvc.perform(get("/system/payingUsers/stats"))
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.msg").value("业务记录冲突，请刷新核对订单、凭证和服务状态后再操作"));
    }
    @Test void databaseOutageDoesNotPretendTheOperationSucceeded() throws Exception
    {
        when(service.selectPayingUserStats()).thenThrow(new DataAccessResourceFailureException("private connection URL"));
        mvc.perform(get("/system/payingUsers/stats"))
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.msg").value("婚恋数据操作暂未完成，请刷新核对处理结果后重试"));
    }
}
