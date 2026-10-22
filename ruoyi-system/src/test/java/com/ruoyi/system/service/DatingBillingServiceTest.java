package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.DatingPackageServiceImpl;
import jakarta.validation.Validator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.junit.jupiter.api.Assertions.*;

class DatingBillingServiceTest
{
    private AnnotationConfigApplicationContext context;
    private DatingBillingService service;
    private IDatingPackageService packageService;
    private DatingPackage pkg;
    private JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({DatingBillingService.class, DatingPackageRules.class, DatingPackageServiceImpl.class})
    static class Config
    {
        @Bean DataSource dataSource()
        {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            new ResourceDatabasePopulator(new ClassPathResource("dating-billing-schema.sql")).execute(source);
            return source;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source)
        { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory factory(DataSource source) throws Exception
        {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setTypeAliases(DatingPackage.class);
            factory.setMapperLocations(new ClassPathResource("mapper/system/DatingBillingMapper.xml"),
                new ClassPathResource("mapper/system/DatingWorkflowMapper.xml"),
                new ClassPathResource("mapper/system/DatingRecoveryMapper.xml"),
                new ClassPathResource("mapper/system/DatingSuccessMapper.xml"),
                new ClassPathResource("mapper/system/DatingPackageMapper.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean DatingBillingMapper billingMapper(SqlSessionTemplate session) { return session.getMapper(DatingBillingMapper.class); }
        @Bean DatingWorkflowMapper workflowMapper(SqlSessionTemplate session) { return session.getMapper(DatingWorkflowMapper.class); }
        @Bean DatingRecoveryMapper recoveryMapper(SqlSessionTemplate session) { return session.getMapper(DatingRecoveryMapper.class); }
        @Bean DatingSuccessMapper successMapper(SqlSessionTemplate session) { return session.getMapper(DatingSuccessMapper.class); }
        @Bean DatingPackageMapper packageMapper(SqlSessionTemplate session) { return session.getMapper(DatingPackageMapper.class); }
        @Bean Validator validator() { return new LocalValidatorFactoryBean(); }
    }

    @BeforeEach void setup()
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(DatingBillingService.class);
        packageService = context.getBean(IDatingPackageService.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        pkg = new DatingPackage();
        pkg.setPackageName("Standard");
        pkg.setPrice(new BigDecimal("2999.00"));
        pkg.setLimitExpire("0");
        pkg.setLimitServiceTimes("1");
        pkg.setServiceTimes(3);
        pkg.setStatus("0");
        pkg.setSelectionMode("MULTI");
        pkg.setServiceOptionCodes(List.of("FRIEND_ADDED", "MEETING_COMPLETED"));
        pkg.setCreateBy("alice");
        packageService.insertDatingPackage(pkg);
    }
    @AfterEach void close() { if (context != null) context.close(); }
    CreateOrder request(long user) { return new CreateOrder(user, pkg.getPackageId(), UUID.randomUUID().toString(), "order note"); }
    ConfirmPayment payment() { return new ConfirmPayment(new BigDecimal("2999.00"), "WECHAT", "REF-001", new Date(1700000000000L), "payment note"); }
    DatingOrder create() { return service.create(request(1), "alice", false); }
    int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }

    @Test void unpaidDoesNotOpenAndPaymentIsAtomicAndIdempotent()
    {
        CreateOrder request = request(1);
        DatingOrder order = service.create(request, "alice", false);
        assertEquals(order.getOrderId(), service.create(request, "alice", false).getOrderId());
        assertEquals("UNPAID", order.getStatus());
        assertEquals("Customer A", order.getUserName());
        assertEquals(0, count("dating_entitlement"));
        assertEquals("NOT_STARTED", jdbc.queryForObject("select service_status from sys_paying_user where id=1", String.class));
        DatingOrder paid = service.confirm(order.getOrderId(), payment(), "alice", false);
        assertNotNull(paid.getEntitlementId());
        assertEquals("PAID", paid.getStatus());
        assertEquals(paid.getEntitlementId(), service.confirm(order.getOrderId(), payment(), "alice", false).getEntitlementId());
        assertEquals(1, count("dating_payment_record"));
        assertEquals(1, count("dating_entitlement"));
        assertEquals(1, count("dating_entitlement_ledger"));
        DatingEntitlement entitlement = service.entitlements(1L, "alice").get(0);
        assertEquals(3, entitlement.getRemainingTimes());
        assertEquals(0, entitlement.getUsedTimes());
        assertEquals("SERVING", jdbc.queryForObject("select service_status from sys_paying_user where id=1", String.class));
        assertEquals("payment note", jdbc.queryForObject("select remark from dating_payment_record", String.class));
        assertEquals("order note", paid.getRemark());
        assertThrows(ServiceException.class, () -> service.create(request(1), "alice", false));
    }

    @Test void snapshotsAreFrozenAndRequireAllOptions()
    {
        DatingOrder order = create();
        pkg.setPrice(new BigDecimal("4000"));
        pkg.setServiceTimes(6);
        pkg.setServiceOptionCodes(List.of("FRIEND_ADDED"));
        packageService.updateDatingPackage(pkg);
        jdbc.update("update dating_service_option set name='Changed', status='1'");
        jdbc.update("update dating_package set status='1'");
        DatingOrder paid = service.confirm(order.getOrderId(), payment(), "alice", false);
        var snapshot = JSON.parseObject(paid.getRuleSnapshot());
        assertEquals("ALL", snapshot.getString("matchMode"));
        assertEquals(2, snapshot.getJSONArray("options").size());
        assertEquals("Friends", snapshot.getJSONArray("options").getJSONObject(0).getString("name"));
        assertEquals(3, service.entitlements(1L, null).get(0).getTotalTimes());
    }

    @Test void rejectsInvalidRulesAndStalePackageWrites()
    {
        pkg.setSelectionMode("SINGLE");
        assertThrows(ServiceException.class, () -> packageService.updateDatingPackage(pkg));
        pkg.setServiceOptionCodes(List.of("FRIEND_ADDED"));
        packageService.updateDatingPackage(pkg);
        pkg.setRuleVersion(1);
        assertThrows(ServiceException.class, () -> packageService.updateDatingPackage(pkg));
        pkg.setRuleVersion(2);
        pkg.setServiceOptionCodes(List.of());
        assertThrows(ServiceException.class, () -> packageService.updateDatingPackage(pkg));
        pkg.setServiceOptionCodes(List.of("MISSING"));
        assertThrows(ServiceException.class, () -> packageService.updateDatingPackage(pkg));
        pkg.setServiceOptionCodes(List.of("FRIEND_ADDED"));
        pkg.setServiceTimes(0);
        assertThrows(ServiceException.class, () -> packageService.updateDatingPackage(pkg));
    }

    @Test void statusToggleInvalidatesStaleEditorAndIgnoresUnvalidatedFields()
    {
        DatingPackage status = new DatingPackage();
        status.setPackageId(pkg.getPackageId());
        status.setStatus("1");
        status.setServiceDuration(999);
        packageService.updateDatingPackage(status);
        DatingPackage updated = packageService.selectDatingPackageById(pkg.getPackageId());
        assertEquals("1", updated.getStatus());
        assertEquals(2, updated.getRuleVersion());
        assertNotEquals(999, updated.getServiceDuration());
        assertThrows(ServiceException.class, () -> packageService.updateDatingPackage(pkg));
    }

    @Test void rejectsCancelledWrongAmountFuturePaymentAndChangedReplay()
    {
        DatingOrder order = create();
        assertThrows(ServiceException.class, () -> service.confirm(order.getOrderId(),
            new ConfirmPayment(BigDecimal.ONE, "WECHAT", "REF", new Date(1700000000000L), null), "alice", false));
        assertThrows(ServiceException.class, () -> service.confirm(order.getOrderId(),
            new ConfirmPayment(order.getAmount(), "WECHAT", "REF", new Date(System.currentTimeMillis() + 60000), null), "alice", false));
        service.cancel(order.getOrderId(), new CancelOrder("Customer cancelled"), "alice", false);
        assertThrows(ServiceException.class, () -> service.confirm(order.getOrderId(), payment(), "alice", false));
        DatingOrder replacement = create();
        service.confirm(replacement.getOrderId(), payment(), "alice", false);
        assertThrows(ServiceException.class, () -> service.confirm(replacement.getOrderId(),
            new ConfirmPayment(replacement.getAmount(), "CASH", "OTHER", payment().paidAt(), null), "alice", false));
        assertThrows(ServiceException.class, () -> service.cancel(replacement.getOrderId(), new CancelOrder("No"), "alice", false));
    }

    @Test void checksOwnerOnEveryActionAndMasksCustomers()
    {
        assertThrows(ServiceException.class, () -> service.create(request(2), "alice", false));
        DatingOrder order = create();
        assertThrows(ServiceException.class, () -> service.detail(order.getOrderId(), "bob", false));
        assertThrows(ServiceException.class, () -> service.confirm(order.getOrderId(), payment(), "bob", false));
        assertThrows(ServiceException.class, () -> service.cancel(order.getOrderId(), new CancelOrder("No"), "bob", false));
        assertTrue(service.orders(null, null, null, "bob").isEmpty());
        assertEquals(2, service.customers(null, "alice").size());
        assertEquals("138****0001", service.customers("0001", "alice").get(0).getPhone());
        assertEquals("PAID", service.confirm(order.getOrderId(), payment(), "admin", true).getStatus());
        assertTrue(service.entitlements(null, "bob").isEmpty());
    }

    @Test void rollsBackAllWritesWhenOpeningLedgerFails()
    {
        DatingOrder order = create();
        jdbc.execute("alter table dating_entitlement_ledger add constraint force_failure check(delta<0)");
        assertThrows(RuntimeException.class, () -> service.confirm(order.getOrderId(), payment(), "alice", false));
        assertEquals("UNPAID", service.detail(order.getOrderId(), "alice", false).getStatus());
        assertEquals(0, count("dating_payment_record"));
        assertEquals(0, count("dating_entitlement"));
        assertEquals(0, count("dating_entitlement_ledger"));
        assertEquals("NOT_STARTED", jdbc.queryForObject("select service_status from sys_paying_user where id=1", String.class));
    }

    @Test void paymentReferenceCannotBeReusedForAnotherCustomer()
    {
        service.confirm(create().getOrderId(), payment(), "alice", false);
        DatingOrder other = service.create(request(3), "alice", false);
        assertThrows(RuntimeException.class, () -> service.confirm(other.getOrderId(), payment(), "alice", false));
        assertEquals("UNPAID", service.detail(other.getOrderId(), "alice", false).getStatus());
        assertEquals(1, count("dating_entitlement"));
    }

    @Test void serializesConcurrentCreateAndConfirm() throws Exception
    {
        CreateOrder request = request(1);
        List<DatingOrder> orders = concurrently(() -> service.create(request, "alice", false));
        assertEquals(orders.get(0).getOrderId(), orders.get(1).getOrderId());
        assertEquals(1, count("dating_order"));
        List<DatingOrder> paid = concurrently(() -> service.confirm(orders.get(0).getOrderId(), payment(), "alice", false));
        assertEquals(paid.get(0).getEntitlementId(), paid.get(1).getEntitlementId());
        assertEquals(1, count("dating_entitlement"));
        assertEquals(1, count("dating_entitlement_ledger"));
    }

    @Test void blocksPendingOrdersDisabledCustomersAndClosedCases()
    {
        create();
        assertThrows(ServiceException.class, this::create);
        jdbc.update("update sys_paying_user set service_status='SUCCESS_CLOSED' where id=3");
        assertThrows(ServiceException.class, () -> service.create(request(3), "alice", false));
        jdbc.update("update sys_paying_user set service_status='NOT_STARTED',status='1' where id=3");
        assertThrows(ServiceException.class, () -> service.create(request(3), "alice", false));
    }

    @Test void optionCodeCannotChangeAndDisabledOptionPreventsNewSale()
    {
        DatingServiceOption option = service.options(null).get(0);
        option.setCode("OTHER_CODE");
        assertThrows(ServiceException.class, () -> service.saveOption(option, "alice"));
        option.setCode("FRIEND_ADDED");
        option.setStatus("1");
        service.saveOption(option, "alice");
        assertThrows(ServiceException.class, this::create);
        assertTrue(service.availablePackages().isEmpty());
    }

    @Test void newOptionsGenerateNumericCodesAndIgnoreClientCodes()
    {
        for (String suppliedCode : Arrays.asList(null, "", "FRIEND_ADDED", "user supplied"))
        {
            DatingServiceOption option = newOption();
            option.setCode(suppliedCode);
            assertTrue(context.getBean(Validator.class).validate(option).isEmpty());
            service.saveOption(option, "alice");
            assertNotNull(option.getOptionId());
            assertTrue(option.getCode().matches("OPTION_[0-9]{1,39}"));
            assertEquals(option.getCode(), jdbc.queryForObject(
                "select code from dating_service_option where option_id=?", String.class, option.getOptionId()));
        }
        assertEquals(6, count("dating_service_option"));
        assertEquals(6, jdbc.queryForObject("select count(distinct code) from dating_service_option", Integer.class));
        assertEquals("FRIEND_ADDED", service.options(null).get(0).getCode());
        assertEquals("MEETING_COMPLETED", service.options(null).get(1).getCode());
    }

    @Test void concurrentSameNameOptionsHaveDistinctCodes() throws Exception
    {
        List<DatingServiceOption> options = concurrently(() -> {
            DatingServiceOption option = newOption();
            service.saveOption(option, "alice");
            return option;
        });
        assertNotEquals(options.get(0).getCode(), options.get(1).getCode());
        assertEquals(4, count("dating_service_option"));
    }

    @Test void generatedCodeIsStableAndWorksInPackageSnapshots()
    {
        DatingServiceOption option = newOption();
        service.saveOption(option, "alice");
        String code = option.getCode();
        option.setName("Updated follow-up");
        service.saveOption(option, "alice");
        assertEquals(code, option.getCode());
        pkg.setServiceOptionCodes(List.of(code));
        pkg.setSelectionMode("SINGLE");
        packageService.updateDatingPackage(pkg);
        var snapshot = JSON.parseObject(create().getRuleSnapshot());
        assertEquals(code, snapshot.getJSONArray("options").getJSONObject(0).getString("code"));
        option.setCode(null);
        assertThrows(ServiceException.class, () -> service.saveOption(option, "alice"));
        option.setCode("OTHER_CODE");
        assertThrows(ServiceException.class, () -> service.saveOption(option, "alice"));
        assertEquals(code, jdbc.queryForObject(
            "select code from dating_service_option where option_id=?", String.class, option.getOptionId()));
    }

    @Test void automaticCodesDoNotBypassRequiredFields()
    {
        DatingServiceOption missingName = newOption();
        missingName.setName(" ");
        assertThrows(ServiceException.class, () -> service.saveOption(missingName, "alice"));
        DatingServiceOption missingStandard = newOption();
        missingStandard.setCompletionStandard("");
        assertThrows(ServiceException.class, () -> service.saveOption(missingStandard, "alice"));
        DatingServiceOption invalidStatus = newOption();
        invalidStatus.setStatus("2");
        assertThrows(ServiceException.class, () -> service.saveOption(invalidStatus, "alice"));
        assertEquals(2, count("dating_service_option"));
    }

    private DatingServiceOption newOption()
    {
        DatingServiceOption option = new DatingServiceOption();
        option.setName("Follow-up");
        option.setCompletionStandard("Both customers confirmed feedback");
        option.setStatus("0");
        return option;
    }

    private <T> List<T> concurrently(Callable<T> task) throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<T> first = executor.submit(() -> { start.await(); return task.call(); });
            Future<T> second = executor.submit(() -> { start.await(); return task.call(); });
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        finally { executor.shutdownNow(); }
    }
}
