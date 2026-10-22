package com.ruoyi.web;

import java.time.Year;
import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.DatingMatchmakerConfig;
import com.ruoyi.system.service.DatingMatchmakerConfigService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class DatingMatchmakerConfigTest
{
    AnnotationConfigApplicationContext context;
    DatingMatchmakerConfigService service;
    @BeforeEach void setup()
    {
        context = new AnnotationConfigApplicationContext(DatingArchiveTest.Config.class);
        service = context.getBean(DatingMatchmakerConfigService.class);
    }
    @AfterEach void close() { context.close(); }
    DatingMatchmakerConfig config(String name)
    {
        var c = new DatingMatchmakerConfig();
        c.setName(name);
        return c;
    }
    @Test void noConfigKeepsExistingBehaviorAndFirstIsAutomaticallyDefault()
    {
        assertNull(service.active());
        service.validateRequirement(new SysPayingRequirement());
        var a = service.create(config("First"), "admin");
        var b = service.create(config("Second"), "admin");
        assertTrue(a.isDefaultConfig());
        assertFalse(b.isDefaultConfig());
        assertEquals(a.getConfigId(), service.active().getConfigId());
        assertEquals(1, service.list(null).stream().filter(DatingMatchmakerConfig::isDefaultConfig).count());
        assertEquals(1, service.list("Second").size());
    }
    @Test void switchRequiresCurrentDefaultAndTargetVersion()
    {
        var a = service.create(config("A"), "admin");
        var b = service.create(config("B"), "admin");
        assertThrows(ServiceException.class, () -> service.setDefault(b.getConfigId(), null, b.getVersion(), "admin"));
        assertThrows(ServiceException.class, () -> service.setDefault(b.getConfigId(), a.getConfigId(), 9L, "admin"));
        service.setDefault(b.getConfigId(), a.getConfigId(), b.getVersion(), "admin");
        assertEquals(b.getConfigId(), service.active().getConfigId());
        assertThrows(ServiceException.class, () -> service.setDefault(a.getConfigId(), a.getConfigId(), a.getVersion(), "admin"));
    }
    @Test void updateUsesVersionAndCannotSetDefaultThroughPayload()
    {
        var a = service.create(config("A"), "admin");
        var b = service.create(config("B"), "admin");
        b.setDefaultConfig(true);
        b.setName("Updated");
        var saved = service.update(b, "editor");
        assertEquals(2, saved.getVersion());
        assertFalse(saved.isDefaultConfig());
        assertThrows(ServiceException.class, () -> service.update(b, "editor"));
        assertEquals(a.getConfigId(), service.active().getConfigId());
    }
    @Test void deletionRequiresSwitchAndLastDeletionRestoresNoConfig()
    {
        var a = service.create(config("A"), "admin");
        var b = service.create(config("B"), "admin");
        assertThrows(ServiceException.class, () -> service.delete(a.getConfigId(), a.getVersion(), "admin"));
        service.delete(b.getConfigId(), b.getVersion(), "admin");
        assertTrue(service.active().isDefaultConfig());
        service.delete(a.getConfigId(), a.getVersion(), "admin");
        assertNull(service.active());
        assertTrue(service.list(null).isEmpty());
        assertTrue(service.create(config("New first"), "admin").isDefaultConfig());
    }
    @Test void invalidFieldsAndRangesAreRejectedWithoutCreatingRows()
    {
        var c = config(" ");
        assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        c.setName("Test"); c.setMinAge(1988);
        assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        c.setMaxAge(1987);
        assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        c.setMaxAge(2004); c.setMinHeight(180); c.setMaxHeight(150);
        assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        c.setMinHeight(150); c.setMaxHeight(180); c.setRequiredFields(List.of("unknown"));
        assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        c.setRequiredFields(null);
        assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        c.setRequiredFields(List.of()); c.setWatermarkText("line\nbreak");
        assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        assertTrue(service.list(null).isEmpty());
    }
    @Test void activeBoundsUseBirthYearsAndAcceptInclusiveEndpoints()
    {
        var c = config("Bounds");
        c.setMinAge(1988); c.setMaxAge(2004);
        c.setMinHeight(155); c.setMaxHeight(185);
        service.create(c, "admin");
        var r = new SysPayingRequirement();
        r.setAgeMin(1988); r.setAgeMax(2004);
        r.setHeightMin(155); r.setHeightMax(185);
        assertDoesNotThrow(() -> service.validateRequirement(r));
        r.setAgeMin(1987);
        assertThrows(ServiceException.class, () -> service.validateRequirement(r));
        r.setAgeMin(1988); r.setAgeMax(2005);
        assertThrows(ServiceException.class, () -> service.validateRequirement(r));
        r.setAgeMax(2004); r.setHeightMin(154);
        assertThrows(ServiceException.class, () -> service.validateRequirement(r));
        r.setHeightMin(155); r.setHeightMax(186);
        assertThrows(ServiceException.class, () -> service.validateRequirement(r));
    }
    @Test void configurationRejectsAgesFutureYearsAndYearsBefore1900()
    {
        for (int[] pair : List.of(new int[]{22, 38}, new int[]{1899, 2000},
            new int[]{2000, Year.now().getValue() + 1}, new int[]{2004, 1988}))
        {
            var c = config("Invalid years");
            c.setMinAge(pair[0]); c.setMaxAge(pair[1]);
            assertThrows(ServiceException.class, () -> service.create(c, "admin"));
        }
        var c = config("Full years");
        c.setMinAge(1900); c.setMaxAge(Year.now().getValue());
        var saved = service.create(c, "admin");
        assertEquals(1900, saved.getMinAge());
        assertEquals(Year.now().getValue(), saved.getMaxAge());
    }
    @Test void requiredFieldsRejectNullWhitespaceAndAllowZeroCodes()
    {
        var c = config("Required");
        c.setRequiredFields(List.of("hasCar", "remark", "acceptLongDist"));
        var active = service.create(c, "admin");
        var r = new SysPayingRequirement();
        assertThrows(ServiceException.class, () -> service.validateRequirement(r));
        r.setHasCar("0"); r.setRemark("  "); r.setAcceptLongDist("0");
        assertThrows(ServiceException.class, () -> service.validateRequirement(r));
        r.setRemark("Confirmed");
        assertDoesNotThrow(() -> service.validateRequirement(r));
        active.setRequiredFields(List.of());
        service.update(active, "admin");
        assertDoesNotThrow(() -> service.validateRequirement(new SysPayingRequirement()));
    }
    @Test void allSupportedRequiredFieldsAreEnforced()
    {
        var c = service.create(config("Required"), "admin");
        for (String field : DatingMatchmakerConfigService.FIELDS.keySet())
        {
            c.setRequiredFields(List.of(field));
            c = service.update(c, "admin");
            assertThrows(ServiceException.class, () -> service.validateRequirement(new SysPayingRequirement()), field);
        }
    }
    @Test void parallelFirstCreatesHaveExactlyOneDefault() throws Exception
    {
        try (var executor = Executors.newFixedThreadPool(4))
        {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<DatingMatchmakerConfig>>();
            for (int i = 0; i < 4; i++)
            {
                final int id = i;
                futures.add(executor.submit(() -> { gate.await(); return service.create(config("C" + id), "admin"); }));
            }
            gate.countDown();
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
        }
        assertEquals(4, service.list(null).size());
        assertEquals(1, service.list(null).stream().filter(DatingMatchmakerConfig::isDefaultConfig).count());
    }
    @Test void parallelSwitchesWithSameExpectedDefaultCannotBothSucceed() throws Exception
    {
        var a = service.create(config("A"), "admin");
        var b = service.create(config("B"), "admin");
        var c = service.create(config("C"), "admin");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2))
        {
            var tasks = new ArrayList<Future<Boolean>>();
            for (var target : List.of(b, c))
                tasks.add(executor.submit(() -> {
                    gate.await();
                    try { service.setDefault(target.getConfigId(), a.getConfigId(), target.getVersion(), "admin"); return true; }
                    catch (ServiceException expected) { return false; }
                }));
            gate.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(1, service.list(null).stream().filter(DatingMatchmakerConfig::isDefaultConfig).count());
    }
}
