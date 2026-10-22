package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.system.domain.DatingOrder;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in, additive tests against the migrated disposable localhost database only.
 * Never point this test at business data. Fixtures remain available for inspection.
 */
@EnabledIfEnvironmentVariable(named = "DATING_MYSQL_TEST_URL", matches = ".+")
class DatingWorkflowMySqlTest
{
    @Configuration
    @Import({DatingWorkflowService.class, DatingDeductionService.class, DatingDeductionDispatcher.class,
        DatingExceptionFollowupService.class})
    static class Config extends DatingBillingServiceTest.Config
    {
        @Override @Bean DataSource dataSource()
        {
            String url = System.getenv("DATING_MYSQL_TEST_URL");
            if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3307/dating_workflow_test(\\?.*)?"))
                throw new IllegalArgumentException("Only isolated localhost:3307/dating_workflow_test is allowed");
            return new DriverManagerDataSource(url, "root", "");
        }
    }

    AnnotationConfigApplicationContext context;
    DatingWorkflowService workflow;
    DatingBillingService billing;
    DatingDeductionDispatcher dispatcher;
    DatingDeductionService worker;
    DatingWorkflowMapper mapper;
    JdbcTemplate jdbc;
    long first;
    long second;
    long third;

    @BeforeEach void setup()
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        workflow = context.getBean(DatingWorkflowService.class);
        billing = context.getBean(DatingBillingService.class);
        dispatcher = context.getBean(DatingDeductionDispatcher.class);
        worker = context.getBean(DatingDeductionService.class);
        mapper = context.getBean(DatingWorkflowMapper.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        first = customer(); second = customer(); third = customer();
        for (long id : new long[] { first, second, third })
            workflow.assign(id, new Assign(1L, null, "MySQL test assignment"), "admin", true);
    }

    @AfterEach void close() { if (context != null) context.close(); }
    String key() { return UUID.randomUUID().toString(); }
    long customer()
    {
        long id = Math.abs(UUID.randomUUID().getMostSignificantBits() / 1000);
        jdbc.update("insert into sys_paying_user(id,name,phone,password,birth_year,height,weight,status,create_by,del_flag)"
            + " values(?,?,?,'TEST_DISABLED',1990,170,120,'0','admin','0')",
            id, "MySQL workflow test", String.format("%011d", id % 100000000000L));
        return id;
    }
    DatingOrder paid(long user, int times, String... options)
    {
        DatingPackage pkg = new DatingPackage();
        pkg.setPackageName("MySQL workflow test");
        pkg.setPrice(BigDecimal.TEN);
        pkg.setLimitExpire("0"); pkg.setLimitServiceTimes("1"); pkg.setServiceTimes(times);
        pkg.setStatus("0"); pkg.setSelectionMode(options.length == 1 ? "SINGLE" : "MULTI");
        pkg.setServiceOptionCodes(List.of(options)); pkg.setCreateBy("admin");
        context.getBean(IDatingPackageService.class).insertDatingPackage(pkg);
        DatingOrder order = billing.create(new CreateOrder(user, pkg.getPackageId(), key(), ""), "admin", true);
        return billing.confirm(order.getOrderId(),
            new ConfirmPayment(BigDecimal.TEN, "CASH", key(), new Date(1700000000000L), ""), "admin", true);
    }
    long pair(long a, long b)
    {
        Long pair = workflow.recommend(new Recommend(a, b, "MySQL test recommendation", key()), "admin", true);
        Long recommendation = mapper.selectRecommendations(pair).get(0).id();
        workflow.intention(recommendation, new Intent(a, "WILLING", "Confirmed"), "admin", true);
        workflow.intention(recommendation, new Intent(b, "WILLING", "Confirmed"), "admin", true);
        return pair;
    }
    void friend(long pair)
    {
        workflow.recordEvent(pair, new ServiceEvent("FRIEND_ADDED", new Date(), "Confirmed", null, key()), "admin", true);
    }
    long appointment(long pair)
    {
        return workflow.schedule(pair, new Schedule(new Date(System.currentTimeMillis() + 3600000),
            "Office", "Confirmed", true), "admin", true);
    }
    void complete(long id)
    {
        workflow.appointmentAction(id, new AppointmentAction("COMPLETE", new Date(), "Met", null), "admin", true);
    }
    int remaining(long user)
    {
        return jdbc.queryForObject("select sum(remaining_times) from dating_entitlement where user_id=?", Integer.class, user);
    }

    @Test void actualMysqlDdlSupportsIndependentAllConditionsAndOncePerPair()
    {
        paid(first, 3, "FRIEND_ADDED");
        paid(second, 1, "FRIEND_ADDED", "MEETING_COMPLETED");
        long pair = pair(first, second);
        long meeting = appointment(pair);
        friend(pair);
        dispatcher.dispatch(pair);
        assertEquals(2, remaining(first));
        assertEquals(1, remaining(second));
        complete(meeting);
        dispatcher.dispatch(pair);
        assertEquals(2, remaining(first));
        assertEquals(0, remaining(second));
        assertEquals("NOT_RENEWED", mapper.selectCustomer(second).serviceStatus());
        assertEquals(2, mapper.selectLedger(pair).size());
        paid(second, 2, "FRIEND_ADDED", "MEETING_COMPLETED");
        complete(appointment(pair));
        friend(pair);
        dispatcher.dispatch(pair);
        assertEquals(2, mapper.selectLedger(pair).size());
        assertEquals(2, remaining(first));
        assertEquals(2, remaining(second));
        // A different pair may consume the same order; the former OPEN-only unique key must not prevent it.
        paid(third, 3, "FRIEND_ADDED");
        long otherPair = pair(first, third);
        friend(otherPair);
        dispatcher.dispatch(otherPair);
        assertEquals(1, remaining(first));
        assertEquals(2, mapper.selectLedger(otherPair).size());
    }

    @Test void lastCountAcrossPairsCannotOverspendOnMysql() throws Exception
    {
        paid(first, 1, "FRIEND_ADDED");
        paid(second, 2, "FRIEND_ADDED");
        paid(third, 2, "FRIEND_ADDED");
        long one = pair(first, second), two = pair(first, third);
        friend(one); friend(two);
        List<Long> ids = new ArrayList<>(mapper.selectReadyTasks(one));
        ids.addAll(mapper.selectReadyTasks(two));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try
        {
            CountDownLatch start = new CountDownLatch(1);
            var results = ids.stream().map(id -> pool.submit(() -> {
                start.await();
                worker.process(id);
                return null;
            })).toList();
            start.countDown();
            for (Future<?> result : results) result.get(20, TimeUnit.SECONDS);
        }
        finally { pool.shutdownNow(); }
        assertEquals(0, remaining(first));
        assertEquals(1, jdbc.queryForObject(
            "select count(*) from dating_entitlement_ledger where user_id=? and entry_type='SERVICE_DEDUCT'", Integer.class, first));
        assertEquals(1, remaining(second));
        assertEquals(1, remaining(third));
    }

    @Test void unrelatedCustomersDoNotLockTheirSharedMatchmaker() throws Exception
    {
        TransactionTemplate tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> firstLock = pool.submit(() -> tx.executeWithoutResult(status -> {
            mapper.lockCustomer(first);
            locked.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        }));
        try
        {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            pool.submit(() -> tx.executeWithoutResult(status -> assertNotNull(mapper.lockCustomer(second))))
                .get(5, TimeUnit.SECONDS);
        }
        finally { release.countDown(); pool.shutdown(); }
        firstLock.get(5, TimeUnit.SECONDS);
    }

    @Test void oneSideFailurePreservesFactsAndOtherSideAndCanRetryOnMysql()
    {
        paid(first, 3, "FRIEND_ADDED");
        paid(second, 3, "FRIEND_ADDED");
        long pair = pair(first, second);
        friend(pair);
        long task = mapper.selectTasks(pair).stream().filter(t -> t.userId() == first)
            .findFirst().orElseThrow().taskId();
        jdbc.execute("alter table dating_entitlement_ledger add constraint test_deduct_failure "
            + "check(entry_type<>'SERVICE_DEDUCT' or user_id<>" + first + ")");
        try
        {
            dispatcher.dispatch(pair);
            assertEquals(3, remaining(first));
            assertEquals(2, remaining(second));
            assertEquals(1, mapper.selectEvents(pair).size());
            assertEquals(1, mapper.selectLedger(pair).size());
            assertEquals(1, mapper.selectTask(task).attempts());
            for (int attempt = 0; attempt < 4; attempt++)
            {
                jdbc.update("update dating_deduction_task set next_attempt_at=current_timestamp where task_id=?", task);
                dispatcher.dispatch(pair);
            }
            assertEquals("ERROR", mapper.selectTask(task).status());
            assertEquals(5, mapper.selectTask(task).attempts());
            assertEquals(3, remaining(first));
            assertEquals(2, remaining(second));
        }
        finally { jdbc.execute("alter table dating_entitlement_ledger drop check test_deduct_failure"); }
        workflow.retry(task, new Retry("Verified repair"), "admin", true);
        dispatcher.dispatch(pair);
        dispatcher.dispatch(pair);
        assertEquals("DONE", mapper.selectTask(task).status());
        assertEquals("DEDUCTED", mapper.selectTask(task).outcome());
        assertEquals(2, remaining(first));
        assertEquals(2, remaining(second));
        assertEquals(2, mapper.selectLedger(pair).size());
        assertEquals(1, mapper.selectEvents(pair).size());
        assertTrue(billing.integrityIssues(first, null).isEmpty());
        assertTrue(billing.integrityIssues(second, null).isEmpty());
    }
}
