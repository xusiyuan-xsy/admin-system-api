package com.ruoyi.web;

import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.vo.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.*;
import com.ruoyi.system.service.impl.*;
import jakarta.validation.Validator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatingArchiveTest
{
    @Configuration
    @EnableTransactionManagement(proxyTargetClass=true)
    @Import({SysPayingUserServiceImpl.class,SysPayingRequirementServiceImpl.class,DatingArchiveAccess.class,DatingMatchingService.class,DatingMatchmakerConfigService.class})
    static class Config
    {
        @Bean DataSource dataSource()
        {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
            new ResourceDatabasePopulator(new ClassPathResource("dating-archive-schema.sql"),
                new ClassPathResource("dating-matchmaker-config-schema.sql")).execute(ds);
            return ds;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory factory(DataSource ds) throws Exception
        {
            SqlSessionFactoryBean f = new SqlSessionFactoryBean();
            f.setDataSource(ds);
            var pagination = new com.github.pagehelper.PageInterceptor();
            Properties properties = new Properties();
            properties.setProperty("helperDialect","mysql");
            pagination.setProperties(properties);
            f.setPlugins(pagination);
            f.setTypeAliases(SysPayingUser.class,SysPayingRequirement.class,SysPayingUserStatsVo.class,SysPayingUserBirthYearStatVo.class);
            f.setMapperLocations(new ClassPathResource("mapper/system/SysPayingUserMapper.xml"),
                new ClassPathResource("mapper/system/SysPayingRequirementMapper.xml"),
                new ClassPathResource("mapper/system/DatingArchiveMapper.xml"),
                new ClassPathResource("mapper/system/DatingMatchingMapper.xml"),
                new ClassPathResource("mapper/system/DatingMatchmakerConfigMapper.xml"));
            return f.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory f) { return new SqlSessionTemplate(f); }
        @Bean SysPayingUserMapper users(SqlSessionTemplate s) { return s.getMapper(SysPayingUserMapper.class); }
        @Bean SysPayingRequirementMapper requirements(SqlSessionTemplate s) { return s.getMapper(SysPayingRequirementMapper.class); }
        @Bean DatingArchiveMapper access(SqlSessionTemplate s) { return s.getMapper(DatingArchiveMapper.class); }
        @Bean DatingMatchingMapper matching(SqlSessionTemplate s) { return s.getMapper(DatingMatchingMapper.class); }
        @Bean DatingMatchmakerConfigMapper matchmakerConfigs(SqlSessionTemplate s) { return s.getMapper(DatingMatchmakerConfigMapper.class); }
        @Bean Validator validator() { return new LocalValidatorFactoryBean(); }
        @Bean ISysConfigService configs()
        {
            ISysConfigService config = mock(ISysConfigService.class);
            when(config.selectConfigByKey("sys.user.initPassword")).thenReturn("test-password");
            return config;
        }
        @Bean DatingPhotoStorage photoStorage() { return mock(DatingPhotoStorage.class); }
    }
    AnnotationConfigApplicationContext context;
    ISysPayingUserService users;
    ISysPayingRequirementService requirements;
    JdbcTemplate jdbc;

    @BeforeEach void setup()
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        users = context.getBean(ISysPayingUserService.class);
        requirements = context.getBean(ISysPayingRequirementService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        login("alice",10L,Set.of());
    }
    @AfterEach void close()
    {
        com.github.pagehelper.PageHelper.clearPage();
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }
    void login(String name,long id,Set<String> permissions)
    {
        SysUser user = new SysUser();
        user.setUserId(id); user.setUserName(name);
        var principal = new LoginUser(id,1L,user,permissions);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal,null,List.of()));
    }
    SysPayingRequirement query()
    {
        SysPayingRequirement r = new SysPayingRequirement();
        r.setPayingId(1L);
        return r;
    }
    @Test void requirementWritesEnforceActiveConfigOnInsertAndUpdate()
    {
        var config = new com.ruoyi.system.domain.DatingMatchmakerConfig();
        config.setName("Required remarks");
        config.setRequiredFields(List.of("remark"));
        context.getBean(DatingMatchmakerConfigService.class).create(config, "admin");
        var r = query();
        assertThrows(ServiceException.class, () -> requirements.insertRequirement(r));
        r.setRemark("Confirmed");
        assertEquals(1, requirements.insertRequirement(r));
        r.setRemark(" ");
        assertThrows(ServiceException.class, () -> requirements.updateRequirement(r));
        assertEquals("Confirmed", requirements.selectRequirementByPayingId(1L).getRemark());
    }
    List<Long> results(SysPayingRequirement r)
    { return users.selectPayingUserListByRequirement(r).stream().map(SysPayingUser::getId).toList(); }

    @Test void listDetailExportScopeAndForgedParameters()
    {
        SysPayingUser q = new SysPayingUser();
        q.getParams().put("archiveOwner",null);
        assertEquals(List.of(4L,3L,1L),users.selectPayingUserList(q).stream().map(SysPayingUser::getId).toList());
        assertThrows(ServiceException.class,()->users.selectPayingUserById(2L));
        q.getParams().put("archiveOwner","bob");
        assertEquals(3,users.selectPayingUserList(q).size());
        login("finance",30L,Set.of("dating:billing:all"));
        assertTrue(users.selectPayingUserList(q).isEmpty());
        login("manager",40L,Set.of("dating:service:all"));
        assertEquals(4,users.selectPayingUserList(q).size());
    }
    @Test void transferTakesEffectAndForgeryCannotModifyAnotherCustomersRequirements()
    {
        SysPayingRequirement r=query();
        requirements.insertRequirement(r);
        jdbc.update("update sys_paying_user set matchmaker_id=20 where id=1");
        assertThrows(ServiceException.class,()->requirements.selectRequirementByPayingId(1L));
        assertThrows(ServiceException.class,()->requirements.updateRequirement(r));
        assertTrue(results(r).isEmpty());
        login("bob",20L,Set.of());
        assertNotNull(requirements.selectRequirementByPayingId(1L));
        assertThrows(ServiceException.class,()->requirements.insertRequirement(r));
    }
    @Test void matchingPaginationKeepsOwnerScopeAndSourceAuthorizationInTheSameQuery()
    {
        var page=com.github.pagehelper.PageHelper.<SysPayingUser>startPage(1,1);
        assertEquals(List.of(4L),results(query()));
        assertEquals(2,page.getTotal());
        jdbc.update("update sys_paying_user set matchmaker_id=20 where id=1");
        page=com.github.pagehelper.PageHelper.startPage(1,1);
        assertTrue(results(query()).isEmpty());
        assertEquals(0,page.getTotal());
    }
    @Test void tattooUnknownEnumsAndInclusiveBoundaries()
    {
        jdbc.update("update sys_paying_user set has_tattoo='0',smoke_habit='0',education='5' where id=3");
        jdbc.update("update sys_paying_user set has_tattoo='1',smoke_habit='x',education='x' where id=4");
        SysPayingRequirement r=query();
        r.setNoTattoo("1");
        r.setAgeMin(1990);r.setAgeMax(1990);
        r.setHeightMin(170);r.setHeightMax(170);
        assertEquals(List.of(3L),results(r));
        r.setNoTattoo("0");
        assertEquals(List.of(4L,3L),results(r));
        r.setSmokeHabit("2");
        assertEquals(List.of(3L),results(r));
        r.setSmokeHabit(null);r.setEducation("5");
        assertEquals(List.of(3L),results(r));
        r.setEducation("0");
        assertEquals(2,results(r).size());
        r.setAgeMax(1989);
        assertThrows(ServiceException.class,()->results(r));
        r.setAgeMax(1990);r.setNoTattoo("x");
        assertThrows(ServiceException.class,()->results(r));
    }
    @Test void regionMatchesHierarchyNotSubstringsOrWildcards()
    {
        jdbc.update("update sys_paying_user set current_city='Province/City/District' where id=3");
        jdbc.update("update sys_paying_user set current_city='OtherProvince/City' where id=4");
        SysPayingRequirement r=query();
        r.setAcceptLongDist("0");r.setCityRequire("Province/City");
        assertEquals(List.of(3L),results(r));
        r.setCityRequire("%");
        assertTrue(results(r).isEmpty());
        r.setCityRequire(null);
        assertThrows(ServiceException.class,()->results(r));
    }
    @Test void omittedAndClearedRequirementsStayUnrestricted()
    {
        jdbc.execute("alter table sys_paying_requirement alter column no_tattoo set default '1'");
        jdbc.execute("alter table sys_paying_requirement alter column smoke_habit set default '0'");
        SysPayingRequirement r=query();
        requirements.insertRequirement(r);
        SysPayingRequirement saved=requirements.selectRequirementByPayingId(1L);
        assertNull(saved.getNoTattoo());
        assertNull(saved.getSmokeHabit());
        assertEquals(2,results(saved).size());
        r.setEducation("5");r.setAgeMin(1980);r.setCityRequire("Province/City");r.setAcceptLongDist("0");
        requirements.updateRequirement(r);
        requirements.updateRequirement(query());
        saved=requirements.selectRequirementByPayingId(1L);
        assertNull(saved.getEducation());assertNull(saved.getAgeMin());assertNull(saved.getCityRequire());
        assertEquals(2,results(saved).size());
    }
    @Test void ordinaryEditsCannotChangePasswordServiceStateOrDeleteBusinessHistory()
    {
        SysPayingUser u=new SysPayingUser();
        u.setId(1L);u.setName("Changed");u.setPassword("PLAINTEXT");u.setServiceStatus("SUCCESS_CLOSED");
        users.updatePayingUser(u);
        assertEquals("HASH",jdbc.queryForObject("select password from sys_paying_user where id=1",String.class));
        assertEquals("NOT_STARTED",users.selectPayingUserById(1L).getServiceStatus());
        jdbc.update("insert into dating_order values(1,1)");
        assertThrows(ServiceException.class,()->users.deletePayingUserByIds(new Long[]{3L,1L}));
        assertEquals("0",jdbc.queryForObject("select del_flag from sys_paying_user where id=3",String.class));
        u.setId(2L);
        assertThrows(ServiceException.class,()->users.updatePayingUser(u));
    }
    @Test void importsAreScopedAndAllOrNothing()
    {
        SysPayingUser fresh=new SysPayingUser();
        fresh.setPhone("13800000005");fresh.setName("new");
        SysPayingUser foreign=new SysPayingUser();
        foreign.setPhone("13800000002");foreign.setName("overwritten");
        assertThrows(ServiceException.class,()->users.importPayingUser(List.of(fresh,foreign),true,"alice"));
        assertEquals(4,jdbc.queryForObject("select count(*) from sys_paying_user",Integer.class));
        assertEquals("B",jdbc.queryForObject("select name from sys_paying_user where id=2",String.class));
    }
    @Test void disabledArchivesDoNotBecomeSuccessAndCasesAreDistinct()
    {
        jdbc.update("update sys_paying_user set status='1' where id=1");
        assertEquals(0L,users.selectPayingUserStats().getSuccessPeopleCount());
        jdbc.update("insert into dating_success_case values(10)");
        jdbc.update("insert into dating_success_case_member values(10,1),(10,3)");
        assertEquals(2L,users.selectPayingUserStats().getSuccessPeopleCount());
        assertEquals(1L,users.selectPayingUserStats().getSuccessCaseCount());
        login("bob",20L,Set.of());
        assertEquals(0L,users.selectPayingUserStats().getSuccessCaseCount());
    }
    @Test void authFieldsAreExcludedAndServiceStatusIsReadOnly() throws Exception
    {
        ObjectMapper mapper=new ObjectMapper();
        SysPayingUser u=new SysPayingUser();
        u.setPassword("secret");u.setLastLoginIp("private");u.setLastLoginTime(new Date());
        String json=mapper.writeValueAsString(u);
        assertFalse(json.contains("secret"));assertFalse(json.contains("lastLogin"));
        String auditJson = com.alibaba.fastjson2.JSON.toJSONString(u);
        assertFalse(auditJson.contains("secret"));assertFalse(auditJson.contains("lastLogin"));
        assertNull(mapper.readValue("{\"serviceStatus\":\"SERVING\"}",SysPayingUser.class).getServiceStatus());
        assertNull(SysPayingUser.class.getDeclaredField("password").getAnnotation(com.ruoyi.common.annotation.Excel.class));
    }

    @Test void legacyPhotosAreHiddenButPreservedAndForeignReferencesRejected()
    {
        String legacy = "https://public.example.test/sensitive.png";
        String ref = com.ruoyi.common.utils.DatingPhotoReferences.prefix(1L) + "a".repeat(32) + ".png";
        jdbc.update("update sys_paying_user set photos=? where id=1", "[\"" + legacy + "\",\"" + ref + "\"]");
        var detail = users.selectPayingUserById(1L);
        assertEquals(List.of(ref), detail.getPhotos());
        assertEquals(1, detail.getLegacyPhotoCount());
        assertTrue(users.selectPayingUserList(new SysPayingUser()).stream()
            .flatMap(u -> u.getPhotos() == null ? java.util.stream.Stream.empty() : u.getPhotos().stream())
            .noneMatch(legacy::equals));
        detail.setPhotos(List.of());
        users.updatePayingUser(detail);
        assertEquals("[\"" + legacy + "\"]", jdbc.queryForObject("select photos from sys_paying_user where id=1", String.class));
        for (String invalid : List.of(legacy, ref.replace("/1/", "/2/"), "../secret"))
        {
            detail.setPhotos(List.of(invalid));
            assertThrows(ServiceException.class, () -> users.updatePayingUser(detail));
        }
        detail.setPhotos(Collections.nCopies(10, ref));
        assertThrows(ServiceException.class, () -> users.updatePayingUser(detail));
        detail.setPhotos(List.of(ref, ref));
        assertThrows(ServiceException.class, () -> users.updatePayingUser(detail));
        detail.setId(null); detail.setPhotos(List.of(ref));
        assertThrows(ServiceException.class, () -> users.insertPayingUser(detail));
    }

    @Test void archiveAuditsDoNotCaptureSensitiveBodies() throws Exception
    {
        for (var method : com.ruoyi.web.controller.system.SysPayingUsersController.class.getDeclaredMethods())
        {
            var annotation = method.getAnnotation(com.ruoyi.common.annotation.Log.class);
            if (annotation != null && !method.getName().equals("remove"))
            {
                assertFalse(annotation.isSaveRequestData(), method.getName());
                assertFalse(annotation.isSaveResponseData(), method.getName());
            }
        }
    }
}
