package com.ruoyi.system.service;

import java.lang.reflect.*;
import java.nio.file.Path;
import javax.sql.DataSource;
import com.ruoyi.system.mapper.DatingRecoveryMapper;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Dedicated disposable JVM: never load the production application or its datasource configuration. */
public class DatingRecoveryCrashProbe
{
    static String mode;

    @Configuration
    static class Config extends DatingWorkflowMySqlTest.Config
    {
        @Override @Bean DatingWorkflowMapper workflowMapper(SqlSessionTemplate session)
        {
            var mapper = session.getMapper(DatingWorkflowMapper.class);
            return (DatingWorkflowMapper) Proxy.newProxyInstance(DatingWorkflowMapper.class.getClassLoader(),
                new Class<?>[] {DatingWorkflowMapper.class}, (p, method, args) -> {
                    // The actual service already updated the balance, but has not inserted its ledger or committed.
                    if ("BEFORE_LEDGER".equals(mode) && method.getName().equals("insertDeduction"))
                        Runtime.getRuntime().halt(77);
                    try { return method.invoke(mapper, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                });
        }
    }

    public static void main(String[] args)
    {
        if (args.length != 4 || !java.util.Set.of("BEFORE_LEDGER", "AFTER_COMMIT").contains(args[0]))
            throw new IllegalArgumentException("Expected isolated crash probe arguments");
        mode = args[0];
        try (var context = new AnnotationConfigApplicationContext(Config.class))
        {
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            Path expected = Path.of(args[3]).toAbsolutePath().normalize();
            if (!expected.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
                || !expected.getFileName().toString().equals("data")
                || !expected.getParent().getFileName().toString().startsWith("dating-mysql-")
                || !expected.equals(Path.of(jdbc.queryForObject("select @@datadir", String.class)).toAbsolutePath().normalize())
                || !Integer.valueOf(3307).equals(jdbc.queryForObject("select @@port", Integer.class)))
                throw new IllegalStateException("Refusing crash test outside disposable MySQL");
            context.getBean(DatingRecoveryMapper.class).startRun(args[2], "crash-probe");
            context.getBean(DatingDeductionService.class).process(Long.valueOf(args[1]));
            if ("AFTER_COMMIT".equals(mode)) Runtime.getRuntime().halt(78);
            throw new IllegalStateException("Crash hook did not execute");
        }
    }
}
