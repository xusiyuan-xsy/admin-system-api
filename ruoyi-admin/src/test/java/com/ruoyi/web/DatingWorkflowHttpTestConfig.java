package com.ruoyi.web;

import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.aspectj.LogAspect;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.*;
import com.ruoyi.system.service.impl.*;
import com.ruoyi.web.controller.dating.*;
import com.ruoyi.web.controller.system.DatingPackageController;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in real HTTP/MySQL workflow. No production application scan or scheduled recovery. */
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@Import({DatingBillingService.class, DatingPackageRules.class, DatingPackageServiceImpl.class,
    DatingWorkflowService.class, DatingDeductionService.class, DatingDeductionDispatcher.class,
    DatingSuccessService.class, DatingBillingController.class, DatingWorkflowController.class,
    DatingSuccessController.class, DatingPackageController.class, DatingDataExceptionHandler.class,
    DatingRecoveryService.class, DatingRecoveryController.class, DatingExceptionFollowupService.class,
    LogAspect.class, SysOperLogServiceImpl.class})
class DatingWorkflowHttpTestConfig extends DatingAuthTestConfig
{
    static final String URL = "jdbc:mysql://127.0.0.1:3307/dating_workflow_test"
        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    static final String PREFIX = "http_" + UUID.randomUUID().toString().substring(0, 6) + "_";
    static final long ALICE = 910001, BOB = 910002, FINANCE = 910003, MANAGER = 910004, VIEWER = 910005;
    static final List<String> SERVICE_PERMISSIONS = List.of(
        "system:payingUsers:list", "system:payingUsers:query", "system:payingUsers:add", "system:payingUsers:edit",
        "dating:order:add", "dating:order:list", "dating:order:query", "dating:order:cancel", "dating:entitlement:list",
        "dating:assignment:list", "dating:assignment:edit", "dating:match:add", "dating:match:list", "dating:match:query",
        "dating:match:intention", "dating:event:add", "dating:appointment:add", "dating:appointment:edit",
        "dating:deduction:retry", "dating:exception:handle");

    @Override @Bean DataSource dataSource()
    {
        String expected = System.getenv("DATING_HTTP_MYSQL_DATADIR");
        if (!"true".equals(System.getenv("DATING_WORKFLOW_HTTP_TEST")) || !URL.equals(System.getenv("DATING_MYSQL_TEST_URL"))
            || expected == null)
            throw new IllegalStateException("Use Test-DatingMySql.ps1 -IncludeWorkflowHttp");
        Path directory = Path.of(expected).toAbsolutePath().normalize();
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!directory.startsWith(temp) || !directory.getFileName().toString().equals("data")
            || !directory.getParent().getFileName().toString().startsWith("dating-mysql-"))
            throw new IllegalStateException("Only a disposable test data directory is allowed");
        var ds = new DriverManagerDataSource(URL, "root", "");
        var jdbc = new JdbcTemplate(ds);
        if (!Integer.valueOf(3307).equals(jdbc.queryForObject("select @@port", Integer.class))
            || !directory.equals(Path.of(jdbc.queryForObject("select @@datadir", String.class)).toAbsolutePath().normalize()))
            throw new IllegalStateException("MySQL identity mismatch; no fixtures written");
        seedAccounts(jdbc);
        return ds;
    }

    private void seedAccounts(JdbcTemplate jdbc)
    {
        String hash = SecurityUtils.encryptPassword(PASSWORD);
        List<String> finance = List.of("dating:billing:all", "dating:order:list", "dating:order:query",
            "dating:order:confirmPayment", "dating:entitlement:list", "system:payingUsers:query");
        List<String> manager = new ArrayList<>(SERVICE_PERMISSIONS);
        manager.addAll(List.of("dating:service:all", "system:datingPackage:add", "system:datingPackage:edit",
            "system:datingPackage:query", "system:datingPackage:list", "dating:success:close", "dating:recovery:query"));
        Map<Long, List<String>> permissions = new LinkedHashMap<>();
        permissions.put(ALICE, SERVICE_PERMISSIONS); permissions.put(BOB, SERVICE_PERMISSIONS);
        permissions.put(FINANCE, finance); permissions.put(MANAGER, manager); permissions.put(VIEWER, List.of());
        Map<Long, String> names = Map.of(ALICE, "alice", BOB, "bob", FINANCE, "finance", MANAGER, "manager", VIEWER, "viewer");
        Map<String, Long> menus = new HashMap<>();
        long menu = 910000;
        for (var entry : permissions.entrySet())
        {
            long id = entry.getKey();
            String name = names.get(id);
            jdbc.update("insert into sys_user(user_id,user_name,nick_name,password) values(?,?,?,?)",
                id, PREFIX + name, "HTTP " + name, hash);
            jdbc.update("insert into sys_role(role_id,role_name,role_key,role_sort,data_scope,status) values(?,?,?,1,'5','0')",
                id, "HTTP " + name, "http_" + name);
            jdbc.update("insert into sys_user_role(user_id,role_id) values(?,?)", id, id);
            for (String permission : entry.getValue())
            {
                if (!menus.containsKey(permission))
                {
                    menus.put(permission, ++menu);
                    jdbc.update("insert into sys_menu(menu_id,menu_name,menu_type,perms) values(?,'HTTP permission','F',?)",
                        menu, permission);
                }
                jdbc.update("insert into sys_role_menu(role_id,menu_id) values(?,?)", id, menus.get(permission));
            }
        }
        jdbc.update("insert into sys_config(config_name,config_key,config_value) values('HTTP captcha','sys.account.captchaEnabled','true')");
    }

    @Override @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception
    {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity;com.ruoyi.system.domain");
        var page = new com.github.pagehelper.PageInterceptor();
        var properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        page.setProperties(properties);
        factory.setPlugins(page);
        factory.setMapperLocations(List.of("SysUser", "SysRole", "SysMenu", "SysConfig", "SysLogininfor",
            "SysPayingUser", "DatingArchive", "DatingBilling", "DatingWorkflow", "DatingSuccess", "DatingRecovery",
            "DatingPackage", "SysOperLog").stream()
            .map(name -> new ClassPathResource("mapper/system/" + name + "Mapper.xml")).toArray(ClassPathResource[]::new));
        return factory.getObject();
    }
    @Bean DatingBillingMapper billingMapper(SqlSessionTemplate s) { return s.getMapper(DatingBillingMapper.class); }
    @Bean DatingWorkflowMapper workflowMapper(SqlSessionTemplate s) { return s.getMapper(DatingWorkflowMapper.class); }
    @Bean DatingRecoveryMapper recoveryMapper(SqlSessionTemplate s) { return s.getMapper(DatingRecoveryMapper.class); }
    @Bean DatingSuccessMapper successMapper(SqlSessionTemplate s) { return s.getMapper(DatingSuccessMapper.class); }
    @Bean DatingPackageMapper packageMapper(SqlSessionTemplate s) { return s.getMapper(DatingPackageMapper.class); }
    @Bean SysOperLogMapper operLogMapper(SqlSessionTemplate s) { return s.getMapper(SysOperLogMapper.class); }
}
