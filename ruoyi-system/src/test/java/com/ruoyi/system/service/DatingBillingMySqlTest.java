package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.DatingOrder;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import static org.junit.jupiter.api.Assertions.*;

/** Real InnoDB transaction tests. Only the guarded disposable MySQL fixture is allowed. */
@EnabledIfEnvironmentVariable(named = "DATING_MYSQL_TEST_URL", matches = ".+")
class DatingBillingMySqlTest
{
    private DatingWorkflowMySqlTest f;
    private DatingPackage pkg;

    @BeforeEach void setup()
    {
        f = new DatingWorkflowMySqlTest();
        f.setup();
        pkg = new DatingPackage();
        pkg.setPackageName("Isolated billing test");
        pkg.setPrice(BigDecimal.TEN);
        pkg.setLimitExpire("0");
        pkg.setLimitServiceTimes("1");
        pkg.setServiceTimes(3);
        pkg.setStatus("0");
        pkg.setSelectionMode("MULTI");
        pkg.setServiceOptionCodes(List.of("FRIEND_ADDED", "MEETING_COMPLETED"));
        pkg.setCreateBy("admin");
        f.context.getBean(IDatingPackageService.class).insertDatingPackage(pkg);
    }

    @AfterEach void close() { if (f != null) f.close(); }
    private CreateOrder request(long user) { return new CreateOrder(user, pkg.getPackageId(), f.key(), "Test only"); }
    private ConfirmPayment payment()
    { return new ConfirmPayment(BigDecimal.TEN, "CASH", f.key(), new Date(1700000000000L), "Test only"); }
    private DatingOrder create(long user) { return f.billing.create(request(user), "admin", true); }
    private DatingOrder confirm(DatingOrder order, ConfirmPayment payment)
    { return f.billing.confirm(order.getOrderId(), payment, "admin", true); }
    private int count(String table, long order)
    { return f.jdbc.queryForObject("select count(*) from " + table + " where order_id=?", Integer.class, order); }

    private void assertUnpaid(DatingOrder order)
    {
        assertEquals("UNPAID", f.billing.detail(order.getOrderId(), "admin", true).getStatus());
        for (String table : List.of("dating_payment_record", "dating_entitlement", "dating_entitlement_ledger"))
            assertEquals(0, count(table, order.getOrderId()));
        assertEquals("NOT_STARTED", f.mapper.selectCustomer(order.getUserId()).serviceStatus());
    }

    @Test void concurrentReplayOpensExactlyOneEntitlement() throws Exception
    {
        CreateOrder request = request(f.first);
        List<DatingOrder> orders = race(() -> f.billing.create(request, "admin", true),
            () -> f.billing.create(request, "admin", true));
        assertEquals(orders.get(0).getOrderId(), orders.get(1).getOrderId());
        assertUnpaid(orders.get(0));
        ConfirmPayment payment = payment();
        List<DatingOrder> paid = race(() -> confirm(orders.get(0), payment), () -> confirm(orders.get(1), payment));
        assertEquals(paid.get(0).getEntitlementId(), paid.get(1).getEntitlementId());
        assertNotNull(paid.get(0).getEntitlementId());
        assertEquals(1, f.jdbc.queryForObject("select count(*) from dating_order where user_id=?", Integer.class, f.first));
        for (String table : List.of("dating_payment_record", "dating_entitlement", "dating_entitlement_ledger"))
            assertEquals(1, count(table, orders.get(0).getOrderId()));
        assertEquals(3, f.remaining(f.first));
        assertEquals("SERVING", f.mapper.selectCustomer(f.first).serviceStatus());
        assertTrue(f.billing.integrityIssues(f.first, null).isEmpty());
    }

    @Test void differentConcurrentRequestsCannotCreateTwoPendingOrders() throws Exception
    {
        List<Boolean> results = race(() -> tryCreate(f.first), () -> tryCreate(f.first));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, f.jdbc.queryForObject("select count(*) from dating_order where user_id=?", Integer.class, f.first));
        assertEquals(0, f.jdbc.queryForObject("select count(*) from dating_entitlement where user_id=?", Integer.class, f.first));
    }

    private boolean tryCreate(long user)
    {
        try { create(user); return true; }
        catch (ServiceException ex) { return false; }
    }

    @Test void concurrentSharedPaymentReferenceRollsBackLosingOrder() throws Exception
    {
        DatingOrder first = create(f.first), second = create(f.second);
        ConfirmPayment payment = payment();
        List<Boolean> results = race(() -> tryConfirm(first, payment), () -> tryConfirm(second, payment));
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        DatingOrder winner = results.get(0) ? first : second;
        DatingOrder loser = results.get(0) ? second : first;
        assertUnpaid(loser);
        assertEquals(1, count("dating_payment_record", winner.getOrderId()));
        assertEquals(1, count("dating_entitlement", winner.getOrderId()));
        assertEquals(1, count("dating_entitlement_ledger", winner.getOrderId()));
        assertEquals(3, f.remaining(winner.getUserId()));
        confirm(loser, payment());
        assertEquals(3, f.remaining(loser.getUserId()));
    }

    private boolean tryConfirm(DatingOrder order, ConfirmPayment payment)
    {
        try { confirm(order, payment); return true; }
        catch (DataIntegrityViolationException ex) { return false; }
    }

    @Test void openingLedgerFailureRollsBackEveryPaymentWrite()
    {
        DatingOrder order = create(f.first);
        // Scoped to this order; existing fixtures remain valid and the constraint is always removed.
        f.jdbc.execute("alter table dating_entitlement_ledger add constraint test_open_failure check(order_id<>"
            + order.getOrderId() + ")");
        try
        {
            DataAccessException error = assertThrows(DataAccessException.class, () -> confirm(order, payment()));
            SQLException sql = assertInstanceOf(SQLException.class, error.getMostSpecificCause());
            assertEquals(3819, sql.getErrorCode());
            assertTrue(sql.getMessage().contains("test_open_failure"));
            assertUnpaid(order);
        }
        finally { f.jdbc.execute("alter table dating_entitlement_ledger drop check test_open_failure"); }
        confirm(order, payment());
        assertEquals(3, f.remaining(f.first));
        assertEquals(1, count("dating_entitlement_ledger", order.getOrderId()));
    }

    @Test void confirmationUsesFrozenRulesAfterPackageChanges()
    {
        DatingOrder order = create(f.first);
        String snapshot = order.getRuleSnapshot();
        pkg.setPrice(new BigDecimal("20"));
        pkg.setServiceTimes(9);
        pkg.setSelectionMode("SINGLE");
        pkg.setServiceOptionCodes(List.of("FRIEND_ADDED"));
        f.context.getBean(IDatingPackageService.class).updateDatingPackage(pkg);
        DatingOrder paid = confirm(order, payment());
        assertEquals(snapshot, paid.getRuleSnapshot());
        var rule = JSON.parseObject(snapshot);
        assertEquals("ALL", rule.getString("matchMode"));
        assertEquals(2, rule.getJSONArray("options").size());
        assertEquals(3, f.remaining(f.first));
        assertEquals(snapshot, f.jdbc.queryForObject(
            "select rule_snapshot from dating_entitlement where order_id=?", String.class, order.getOrderId()));
    }

    @Test void invalidPaymentAndCancelledOrderNeverOpenService()
    {
        DatingOrder order = create(f.first);
        assertThrows(ServiceException.class, () -> confirm(order,
            new ConfirmPayment(BigDecimal.ONE, "CASH", f.key(), new Date(1700000000000L), "")));
        assertThrows(ServiceException.class, () -> confirm(order,
            new ConfirmPayment(BigDecimal.TEN, "CASH", f.key(), new Date(System.currentTimeMillis() + 60000), "")));
        assertUnpaid(order);
        f.billing.cancel(order.getOrderId(), new CancelOrder("Test cancellation"), "admin", true);
        assertThrows(ServiceException.class, () -> confirm(order, payment()));
        for (String table : List.of("dating_payment_record", "dating_entitlement", "dating_entitlement_ledger"))
            assertEquals(0, count(table, order.getOrderId()));
        assertEquals("NOT_STARTED", f.mapper.selectCustomer(f.first).serviceStatus());
        DatingOrder replacement = create(f.first);
        ConfirmPayment original = payment();
        confirm(replacement, original);
        assertThrows(ServiceException.class, () -> confirm(replacement, payment()));
        assertEquals(1, count("dating_payment_record", replacement.getOrderId()));
        assertEquals(3, f.remaining(f.first));
    }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<T> a = executor.submit(() -> { start.await(); return first.call(); });
            Future<T> b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        finally
        {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Concurrent workers did not finish");
        }
    }
}
