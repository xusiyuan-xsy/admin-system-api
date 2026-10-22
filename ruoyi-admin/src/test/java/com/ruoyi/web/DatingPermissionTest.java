package com.ruoyi.web;

import java.util.*;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.service.DatingBillingService;
import com.ruoyi.web.controller.dating.DatingBillingController;
import com.ruoyi.web.controller.system.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real method-security proxies and archive SQL; not a login/Redis acceptance test. */
class DatingPermissionTest
{
    @Configuration
    @EnableMethodSecurity
    @Import({DatingArchiveTest.Config.class, PermissionService.class, SysPayingUsersController.class,
        com.ruoyi.system.service.DatingArchiveExportService.class,
        SysPayingRequirementController.class, DatingBillingController.class,
        com.ruoyi.web.controller.dating.DatingMatchmakerConfigController.class,
        com.ruoyi.web.controller.dating.DatingPhotoController.class})
    static class Config
    {
        @Bean DatingBillingService billing() { return mock(DatingBillingService.class); }
    }
    AnnotationConfigApplicationContext context;
    SysPayingUsersController archives;
    SysPayingRequirementController requirements;
    DatingBillingController billing;
    DatingBillingService billingService;

    @BeforeEach void setup()
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        archives = context.getBean(SysPayingUsersController.class);
        requirements = context.getBean(SysPayingRequirementController.class);
        billing = context.getBean(DatingBillingController.class);
        billingService = context.getBean(DatingBillingService.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
    @AfterEach void close()
    {
        com.github.pagehelper.PageHelper.clearPage();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }
    void login(String... permissions)
    {
        SysUser user = new SysUser();
        user.setUserId(10L); user.setUserName("alice");
        LoginUser principal = new LoginUser(10L, 1L, user, Set.of(permissions));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    @Test void archiveFunctionPermissionDoesNotGrantAnotherOwnersData()
    {
        login();
        assertThrows(AccessDeniedException.class, () -> archives.getInfo(1L));
        assertThrows(AccessDeniedException.class, () -> archives.stats());
        login("system:payingUsers:query");
        assertNotNull(archives.getInfo(1L).get("data"));
        assertThrows(ServiceException.class, () -> archives.getInfo(2L));
        login("system:payingUsers:list", "dating:billing:all");
        assertEquals(3, archives.list(new SysPayingUser()).getRows().size());
    }
    @Test void legacyButtonAndUnrelatedUserPermissionsCannotBypassSearchPermission()
    {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        jdbc.execute("alter table sys_user add status char(1) default '0'");
        jdbc.execute("alter table sys_user add del_flag char(1) default '0'");
        jdbc.execute("alter table sys_user add nick_name varchar(64)");
        jdbc.execute("alter table dating_order add status varchar(20)");
        jdbc.execute("alter table dating_entitlement add order_id bigint");
        jdbc.execute("alter table dating_entitlement add status varchar(20)");
        jdbc.execute("alter table dating_entitlement add remaining_times int");
        jdbc.execute("alter table dating_pair add progress varchar(30)");
        var query = new com.ruoyi.system.domain.dto.DatingMatchSearch();
        query.setCandidateScope("ALL");
        query.setPayingId(1L);
        login("system:payingUsers:queryMatching", "system:user:list");
        assertThrows(AccessDeniedException.class, () -> requirements.search(query));
        login("system:payingRequirement:search");
        assertFalse(requirements.search(query).getRows().isEmpty());
        query.setPayingId(2L);
        assertTrue(requirements.search(query).getRows().isEmpty());
    }
    @Test void requirementWriteAndReadPermissionsRemainSeparate()
    {
        var r = new SysPayingRequirement();
        r.setPayingId(1L);
        login("system:payingRequirement:add");
        requirements.add(r);
        assertThrows(AccessDeniedException.class, () -> requirements.edit(r));
        assertThrows(AccessDeniedException.class, () -> requirements.getInfo(1L));
        login("system:payingRequirement:edit");
        requirements.edit(r);
        r.setPayingId(2L);
        assertThrows(ServiceException.class, () -> requirements.edit(r));
    }
    @Test void invalidSearchDoesNotLeavePaginationOnTheRequestThread()
    {
        login("system:payingRequirement:search");
        var query = new com.ruoyi.system.domain.dto.DatingMatchSearch();
        query.setPayingId(1L);
        query.setMatchMode("INVALID");
        assertThrows(ServiceException.class, () -> requirements.search(query));
        assertNull(com.github.pagehelper.PageHelper.getLocalPage());
    }
    @Test void createOrderPermissionCannotConfirmPaymentOrReadEntitlement()
    {
        login("dating:order:add");
        assertThrows(AccessDeniedException.class, () -> billing.confirm(1L, null));
        assertThrows(AccessDeniedException.class, () -> billing.entitlement(1L));
        assertThrows(AccessDeniedException.class, () -> billing.integrityIssues(null));
        verifyNoInteractions(billingService);
        login("dating:entitlement:list");
        billing.entitlement(1L);
        verify(billingService).entitlementDetail(1L, "alice");
        login("dating:entitlement:list", "dating:billing:all");
        billing.entitlement(1L);
        verify(billingService).entitlementDetail(1L, null);
    }
    @Test void photoReadAndUploadRequireFunctionAndCurrentArchiveScope()
    {
        var photos = context.getBean(com.ruoyi.web.controller.dating.DatingPhotoController.class);
        var storage = context.getBean(com.ruoyi.system.service.DatingPhotoStorage.class);
        String file = "a".repeat(32) + ".png";
        login();
        assertThrows(AccessDeniedException.class, () -> photos.read(1L, file));
        assertThrows(AccessDeniedException.class, () -> photos.upload(1L, null));
        login("system:payingRequirement:search");
        when(storage.read(1L, "/dating/archives/1/photos/" + file)).thenReturn(new byte[]{1});
        var response = photos.read(1L, file);
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertThrows(ServiceException.class, () -> photos.read(2L, file));
        assertThrows(AccessDeniedException.class, () -> photos.upload(1L, null));
        login("system:payingUsers:edit");
        assertThrows(ServiceException.class, () -> photos.upload(2L, null));
        verify(storage, never()).save(any(), any(), any());
    }

    @Test void matchmakerConfigSeparatesConsumptionFromAdministration()
    {
        var configs = context.getBean(com.ruoyi.web.controller.dating.DatingMatchmakerConfigController.class);
        var c = new com.ruoyi.system.domain.DatingMatchmakerConfig();
        c.setName("Config");
        login();
        assertThrows(AccessDeniedException.class, configs::active);
        assertThrows(AccessDeniedException.class, () -> configs.list(null));
        login("system:payingRequirement:edit");
        assertNotNull(configs.active());
        assertThrows(AccessDeniedException.class, () -> configs.create(c));
        login("dating:matchmakerConfig:add");
        assertNotNull(configs.create(c));
        assertThrows(AccessDeniedException.class, () -> configs.update(c.getConfigId(), c));
        assertThrows(AccessDeniedException.class, () -> configs.delete(c.getConfigId(), 1L));
        assertThrows(AccessDeniedException.class, () -> configs.setDefault(c.getConfigId(),
            new com.ruoyi.web.controller.dating.DatingMatchmakerConfigController.DefaultRequest(c.getConfigId(), 1L)));
        login("dating:matchmakerConfig:list");
        assertEquals(1, configs.list(null).getRows().size());
    }

    @Test void photoExportRequiresExportPermissionAndPreservesArchiveScope() throws Exception
    {
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        login("system:payingUsers:list", "system:payingUsers:query");
        assertThrows(AccessDeniedException.class, () -> archives.export(response, new SysPayingUser()));
        assertThrows(AccessDeniedException.class, () -> archives.estimateExport(new SysPayingUser()));
        login("system:payingUsers:export");
        var query = new SysPayingUser();
        query.getParams().put("archiveOwner", "bob");
        var estimate = (com.ruoyi.system.service.DatingArchiveExportService.Estimate) archives.estimateExport(query).get("data");
        assertEquals(3, estimate.userCount());
        archives.export(response, query);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(response.getContentAsByteArray())))
        {
            var photos = workbook.getSheet("用户数据");
            assertEquals(3, photos.getLastRowNum());
            assertEquals(List.of("4", "3", "1"), java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(i -> photos.getRow(i).getCell(0).getStringCellValue()).toList());
        }
        query = new SysPayingUser();
        query.setName("A");
        estimate = (com.ruoyi.system.service.DatingArchiveExportService.Estimate) archives.estimateExport(query).get("data");
        assertEquals(1, estimate.userCount());
        var filtered = new org.springframework.mock.web.MockHttpServletResponse();
        archives.export(filtered, query);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            new java.io.ByteArrayInputStream(filtered.getContentAsByteArray())))
        {
            assertEquals(1, workbook.getSheet("用户数据").getLastRowNum());
            assertEquals("1", workbook.getSheet("用户数据").getRow(1).getCell(0).getStringCellValue());
        }
    }
}
