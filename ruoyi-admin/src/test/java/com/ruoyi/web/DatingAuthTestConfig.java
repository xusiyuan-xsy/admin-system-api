package com.ruoyi.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.framework.config.*;
import com.ruoyi.framework.config.properties.PermitAllUrlProperties;
import com.ruoyi.framework.security.filter.JwtAuthenticationTokenFilter;
import com.ruoyi.framework.security.handle.*;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.framework.web.service.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.*;
import com.ruoyi.system.service.impl.*;
import com.ruoyi.web.controller.common.CaptchaController;
import com.ruoyi.web.controller.system.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.*;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.*;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.cors.*;
import org.springframework.web.filter.CorsFilter;
import static org.mockito.Mockito.*;

/** Explicit imports prevent production datasource, scheduled jobs and application scanning. */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true)
@ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
    WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
    org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration.class,
    SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
@Import({SecurityConfig.class, PermitAllUrlProperties.class, CaptchaConfig.class,
    AuthenticationEntryPointImpl.class, LogoutSuccessHandlerImpl.class, JwtAuthenticationTokenFilter.class,
    SysLoginService.class, UserDetailsServiceImpl.class, SysPasswordService.class, SysPermissionService.class,
    TokenService.class, PermissionService.class, RedisCache.class,
    SysUserServiceImpl.class, SysRoleServiceImpl.class, SysMenuServiceImpl.class,
    SysConfigServiceImpl.class, SysLogininforServiceImpl.class,
    SysPayingUserServiceImpl.class, DatingArchiveAccess.class, DatingPhotoStorage.class, DatingArchiveExportService.class,
    com.ruoyi.web.controller.dating.DatingPhotoController.class,
    DatingMatchmakerConfigService.class, com.ruoyi.web.controller.dating.DatingMatchmakerConfigController.class,
    SysLoginController.class, CaptchaController.class, SysPayingUsersController.class, GlobalExceptionHandler.class})
class DatingAuthTestConfig
{
    static final String PASSWORD = UUID.randomUUID().toString().substring(0, 20);

    @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties()
    {
        return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
    }
    @Bean static SpringUtils springUtils() { return new SpringUtils(); }
    @Bean RuoYiConfig ruoYiConfig()
    {
        var config = new RuoYiConfig();
        config.setCaptchaType("math");
        config.setAddressEnabled(false);
        return config;
    }
    @Bean ResourceBundleMessageSource messageSource()
    {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }
    @Bean(destroyMethod = "shutdown")
    AuditScheduler scheduledExecutorService() { return new AuditScheduler(); }
    @Bean LocalValidatorFactoryBean validator() { return new LocalValidatorFactoryBean(); }
    @Bean CorsFilter corsFilter()
    {
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", new CorsConfiguration().applyPermitDefaultValues());
        return new CorsFilter(source);
    }
    @Bean DataSource dataSource()
    {
        if (!"true".equals(System.getenv("DATING_AUTH_HTTP_TEST")))
            throw new IllegalStateException("Isolated authentication tests require explicit opt-in");
        var ds = new JdbcDataSource();
        // Legacy mode supports the existing login-audit SYSDATE() without rewriting mapper SQL.
        ds.setURL("jdbc:h2:mem:dating_auth_" + UUID.randomUUID()
            + ";MODE=LEGACY;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("dating-archive-schema.sql"),
            new ClassPathResource("dating-auth-schema.sql"),
            new ClassPathResource("dating-matchmaker-config-schema.sql")).execute(ds);
        new JdbcTemplate(ds).update("update sys_user set password=?", SecurityUtils.encryptPassword(PASSWORD));
        return ds;
    }
    @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception
    {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity;com.ruoyi.system.domain");
        var page = new com.github.pagehelper.PageInterceptor();
        var props = new Properties();
        props.setProperty("helperDialect", "mysql");
        page.setProperties(props);
        factory.setPlugins(page);
        factory.setMapperLocations(
            new ClassPathResource("mapper/system/SysUserMapper.xml"),
            new ClassPathResource("mapper/system/SysRoleMapper.xml"),
            new ClassPathResource("mapper/system/SysMenuMapper.xml"),
            new ClassPathResource("mapper/system/SysConfigMapper.xml"),
            new ClassPathResource("mapper/system/SysLogininforMapper.xml"),
            new ClassPathResource("mapper/system/SysPayingUserMapper.xml"),
            new ClassPathResource("mapper/system/DatingArchiveMapper.xml"),
            new ClassPathResource("mapper/system/DatingMatchmakerConfigMapper.xml"));
        return factory.getObject();
    }
    @Bean SqlSessionTemplate session(SqlSessionFactory f) { return new SqlSessionTemplate(f); }
    @Bean SysUserMapper userMapper(SqlSessionTemplate s) { return s.getMapper(SysUserMapper.class); }
    @Bean SysRoleMapper roleMapper(SqlSessionTemplate s) { return s.getMapper(SysRoleMapper.class); }
    @Bean SysMenuMapper menuMapper(SqlSessionTemplate s) { return s.getMapper(SysMenuMapper.class); }
    @Bean SysConfigMapper configMapper(SqlSessionTemplate s) { return s.getMapper(SysConfigMapper.class); }
    @Bean SysLogininforMapper logininforMapper(SqlSessionTemplate s) { return s.getMapper(SysLogininforMapper.class); }
    @Bean SysPayingUserMapper payingMapper(SqlSessionTemplate s) { return s.getMapper(SysPayingUserMapper.class); }
    @Bean DatingArchiveMapper archiveMapper(SqlSessionTemplate s) { return s.getMapper(DatingArchiveMapper.class); }
    @Bean DatingMatchmakerConfigMapper matchmakerConfigMapper(SqlSessionTemplate s) { return s.getMapper(DatingMatchmakerConfigMapper.class); }

    // Account/department administration is outside this test; fail if these collaborators are invoked.
    static <T> T unused(Class<T> type)
    {
        return mock(type, invocation -> { throw new AssertionError("Unexpected admin dependency: " + type.getSimpleName()); });
    }
    @Bean SysPostMapper postMapper() { return unused(SysPostMapper.class); }
    @Bean SysUserPostMapper userPostMapper() { return unused(SysUserPostMapper.class); }
    @Bean SysUserRoleMapper userRoleMapper() { return unused(SysUserRoleMapper.class); }
    @Bean SysRoleMenuMapper roleMenuMapper() { return unused(SysRoleMenuMapper.class); }
    @Bean SysRoleDeptMapper roleDeptMapper() { return unused(SysRoleDeptMapper.class); }
    @Bean ISysDeptService deptService() { return unused(ISysDeptService.class); }

    static class AuditScheduler extends ScheduledThreadPoolExecutor
    {
        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        AuditScheduler() { super(1); }
        @Override protected void afterExecute(Runnable task, Throwable error)
        {
            super.afterExecute(task, error);
            if (error != null) failures.add(error);
            if (task instanceof Future<?> future && future.isDone())
            {
                try { future.get(); }
                catch (ExecutionException e) { failures.add(e.getCause()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); failures.add(e); }
                catch (CancellationException e) { failures.add(e); }
            }
        }
    }

    @Bean LettuceConnectionFactory redisConnectionFactory()
    {
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(3)).build();
        return new LettuceConnectionFactory(
            new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1", 6379), client);
    }
    @Bean(destroyMethod = "close")
    IsolatedRedisTemplate redisTemplate(LettuceConnectionFactory connection)
    {
        return new IsolatedRedisTemplate(connection);
    }

    /** Only exact keys generated by this test can be read or deleted; never scan or flush Redis. */
    static class IsolatedRedisTemplate extends RedisTemplate<Object, Object>
        implements AutoCloseable, org.springframework.context.SmartLifecycle
    {
        final String prefix = "dating-auth-test:" + UUID.randomUUID() + ":";
        final Set<String> keys = ConcurrentHashMap.newKeySet();
        volatile boolean running = true;

        IsolatedRedisTemplate(LettuceConnectionFactory connection)
        {
            setConnectionFactory(connection);
            setKeySerializer(new RedisSerializer<String>()
            {
                public byte[] serialize(String key)
                {
                    if (key == null || key.matches(".*[\\*?\\[\\]].*"))
                        throw new IllegalArgumentException("Only exact test keys are allowed");
                    keys.add(key);
                    return (prefix + key).getBytes(StandardCharsets.UTF_8);
                }
                public String deserialize(byte[] bytes)
                {
                    String key = new String(bytes, StandardCharsets.UTF_8);
                    if (!key.startsWith(prefix)) throw new IllegalArgumentException("Foreign Redis key");
                    return key.substring(prefix.length());
                }
            });
            setHashKeySerializer(new StringRedisSerializer());
            setValueSerializer(new FastJson2JsonRedisSerializer<>(Object.class));
            setHashValueSerializer(new FastJson2JsonRedisSerializer<>(Object.class));
        }
        @Override public void start() { running = true; }
        @Override public boolean isRunning() { return running; }
        @Override public int getPhase() { return Integer.MAX_VALUE; }
        @Override public void stop() { close(); }
        @Override public synchronized void close()
        {
            if (!running) return;
            for (String key : Set.copyOf(keys)) delete(key);
            running = false;
        }
    }
}
