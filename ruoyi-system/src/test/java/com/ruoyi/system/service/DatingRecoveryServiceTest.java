package com.ruoyi.system.service;

import java.lang.reflect.*;
import java.util.Date;
import java.util.UUID;
import com.ruoyi.system.domain.DatingWorkflow.Task;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.Retry;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.DatingRecoveryMapper;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;

class DatingRecoveryServiceTest
{
    DatingWorkflowServiceTest f;
    DatingRecoveryMapper records;
    DatingRecoveryService recovery;

    @BeforeEach void setup() throws Exception
    {
        f = new DatingWorkflowServiceTest();
        f.setup();
        records = f.context.getBean(DatingRecoveryMapper.class);
        recovery = service(records, f.dispatcher, true);
    }
    @AfterEach void close() throws Exception { if (f != null) f.close(); }
    DatingRecoveryService service(DatingRecoveryMapper mapper, DatingDeductionDispatcher dispatcher, boolean enabled)
    { return new DatingRecoveryService(mapper, dispatcher, enabled, 120000, 300000); }
    long pair()
    {
        f.paid(1, 3, "FRIEND_ADDED"); f.paid(2, 3, "FRIEND_ADDED");
        long pair = f.pair(1, 2); f.friend(pair);
        return pair;
    }
    Task task(long pair) { return f.mapper.selectTasks(pair).stream().filter(t -> t.userId() == 1).findFirst().orElseThrow(); }

    @Test void recordsEmptyScansDisabledSchedulingAndStaleHeartbeat()
    {
        assertEquals("NOT_STARTED", recovery.overview().health());
        var disabled = service(records, f.dispatcher, false);
        disabled.recover();
        assertEquals("DISABLED", disabled.overview().health());
        assertTrue(records.runs().isEmpty());
        recovery.recover();
        var overview = recovery.overview();
        assertEquals("SUCCEEDED", overview.health());
        assertEquals(0, overview.lastRun().scanned());
        assertNotNull(overview.lastRun().finishedAt());
        assertEquals(0, overview.queue().ready());
        f.jdbc.update("update dating_recovery_run set started_at=?,finished_at=?", new Date(0), new Date(0));
        assertEquals("STALE", recovery.overview().health());
        records.startRun(UUID.randomUUID().toString(), "old-process");
        f.jdbc.update("update dating_recovery_run set started_at=? where instance_id='old-process'", new Date(0));
        assertEquals(1, recovery.overview().staleRuns());
        assertEquals("RUNNING", records.runs().stream().filter(r -> r.instanceId().equals("old-process")).findFirst().orElseThrow().status());
    }

    @Test void failureAndManualRetryHistorySurviveSuccessfulDeductionAndAreSafe()
    {
        long pair = pair(), task = task(pair).taskId();
        f.jdbc.execute("alter table dating_entitlement_ledger add constraint recovery_failure "
            + "check(entry_type<>'SERVICE_DEDUCT' or user_id<>1)");
        recovery.recover();
        var overview = recovery.overview();
        assertEquals("PARTIAL", overview.health());
        assertEquals(2, overview.lastRun().scanned());
        assertEquals(1, overview.lastRun().processed());
        assertEquals(1, overview.lastRun().failed());
        assertEquals(0, overview.lastRun().recordFailures());
        assertEquals("DATA_CONFLICT", records.history(task).get(0).errorCode());
        assertEquals(1, records.history(task).size());
        // Duplicate stale errors and early scans must not burn attempts or bypass the delay.
        f.worker.failed(task, new IllegalStateException("password=private; SQL SELECT secret"));
        f.worker.process(task);
        assertEquals(1, f.mapper.selectTask(task).attempts());
        assertEquals(1, records.history(task).size());
        for (int i = 0; i < 4; i++)
        {
            f.jdbc.update("update dating_deduction_task set next_attempt_at=current_timestamp where task_id=?", task);
            recovery.recover();
        }
        assertEquals("ERROR", f.mapper.selectTask(task).status());
        assertEquals(5, records.history(task).size());
        assertEquals(1, recovery.issues(pair, "alice").size());
        assertTrue(recovery.issues(pair, "outsider").isEmpty());
        assertNotNull(recovery.issues(pair, "alice").get(0).firstFailureAt());
        assertEquals(1, recovery.overview().queue().errors());
        f.jdbc.execute("alter table dating_entitlement_ledger drop constraint recovery_failure");
        f.workflow.retry(task, new Retry("  Verified repair  "), "alice", false);
        assertEquals("RETRY", records.history(task).get(0).action());
        assertEquals("alice", records.history(task).get(0).createBy());
        assertEquals(5, records.history(task).get(0).attempt());
        assertEquals("Verified repair", records.history(task).get(0).reason());
        recovery.recover();
        f.worker.failed(task, new IllegalStateException("Late error"));
        assertEquals("DONE", f.mapper.selectTask(task).status());
        assertEquals(6, records.history(task).size());
        assertTrue(recovery.issues(pair, null).isEmpty());
        assertEquals(2, f.deductions(pair));
        assertEquals(2, f.remaining(1)); assertEquals(2, f.remaining(2));
    }

    @Test void failureRecordAndRetryTransitionAreAtomicWhenHistoryStorageFails()
    {
        long pair = pair(), task = task(pair).taskId();
        f.jdbc.execute("alter table dating_deduction_history add constraint reject_history check(attempt<0)");
        assertThrows(RuntimeException.class, () -> f.worker.failed(task, new IllegalStateException("Test")));
        assertEquals(0, f.mapper.selectTask(task).attempts());
        assertEquals("READY", f.mapper.selectTask(task).status());
        f.jdbc.update("update dating_deduction_task set status='ERROR',attempts=5 where task_id=?", task);
        assertThrows(RuntimeException.class, () -> f.workflow.retry(task, new Retry("Verified repair"), "admin", true));
        assertEquals("ERROR", f.mapper.selectTask(task).status());
        assertEquals(5, f.mapper.selectTask(task).attempts());
        assertTrue(records.history(task).isEmpty());
    }

    @Test void brokenHistoryStorageIsVisibleInBatchAndDoesNotBlockOtherSide()
    {
        long pair = pair();
        f.jdbc.execute("alter table dating_entitlement_ledger add constraint fail_balance "
            + "check(entry_type<>'SERVICE_DEDUCT' or user_id<>1)");
        f.jdbc.execute("alter table dating_deduction_history add constraint fail_history check(attempt<0)");
        recovery.recover();
        assertEquals("PARTIAL", recovery.overview().health());
        assertEquals(1, recovery.overview().lastRun().recordFailures());
        assertEquals(0, task(pair).attempts());
        assertEquals(3, f.remaining(1)); assertEquals(2, f.remaining(2));
        f.jdbc.execute("alter table dating_entitlement_ledger drop constraint fail_balance");
        f.jdbc.execute("alter table dating_deduction_history drop constraint fail_history");
        recovery.recover();
        assertEquals(2, f.deductions(pair));
    }

    @Test void retryRequiresReasonAndCurrentOwnershipAndCannotReopenTerminalPairs()
    {
        long pair = pair(), task = task(pair).taskId();
        f.jdbc.update("update dating_deduction_task set status='ERROR',attempts=5 where task_id=?", task);
        for (Retry invalid : new Retry[] {null, new Retry(null), new Retry(""), new Retry(" \t\n"),
            new Retry("x".repeat(501))})
            assertThrows(ServiceException.class, () -> f.workflow.retry(task, invalid, "alice", false));
        assertThrows(ServiceException.class, () -> f.workflow.retry(task, new Retry("Checked"), "outsider", false));
        assertEquals("ERROR", f.mapper.selectTask(task).status());
        assertEquals(5, f.mapper.selectTask(task).attempts());
        assertTrue(records.history(task).isEmpty());
        var issue = recovery.issues(pair, "alice", "ERROR").get(0);
        assertTrue(issue.retryAllowed());
        assertNotNull(issue.customerName());
        assertEquals("FOLLOWING", issue.pairProgress());
        assertTrue(recovery.issues(pair, "alice", "BLOCKED").isEmpty());
        assertTrue(recovery.issues(pair, "outsider", "ERROR").isEmpty());
        assertThrows(ServiceException.class, () -> recovery.issues(pair, "alice", "DONE"));
        assertThrows(ServiceException.class, () -> recovery.issues(0L, "alice", null));
        f.jdbc.update("update dating_pair set progress='SUCCESS_CLOSED' where pair_id=?", pair);
        assertFalse(recovery.issues(pair, "alice").get(0).retryAllowed());
        assertThrows(ServiceException.class, () -> f.workflow.retry(task, new Retry("Checked"), "admin", true));
        assertTrue(records.history(task).isEmpty());
    }

    @Test void maximumLengthReasonIsRecordedWithoutChangingBalanceUntilProcessing()
    {
        long pair = pair(), task = task(pair).taskId();
        f.jdbc.update("update dating_deduction_task set status='ERROR',attempts=5 where task_id=?", task);
        f.workflow.retry(task, new Retry("x".repeat(500)), "alice", false);
        assertEquals("x".repeat(500), records.history(task).get(0).reason());
        assertEquals("READY", f.mapper.selectTask(task).status());
        assertEquals(3, f.remaining(1));
        assertEquals(0, f.deductions(pair));
        assertThrows(ServiceException.class, () -> f.workflow.retry(task, new Retry("Duplicate"), "alice", false));
        assertEquals(1, records.history(task).size());
    }

    @Test void monitoringFailureDoesNotPreventRecoveryAndScanErrorsStaySanitized()
    {
        long pair = pair();
        var brokenRecords = proxy(DatingRecoveryMapper.class, records, "startRun");
        service(brokenRecords, f.dispatcher, true).recover();
        assertEquals(2, f.deductions(pair));
        assertTrue(records.runs().isEmpty());
        var brokenTasks = proxy(DatingWorkflowMapper.class, f.mapper, "selectReadyTasks");
        var failing = service(records, new DatingDeductionDispatcher(brokenTasks, f.worker), true);
        failing.recover();
        assertEquals("FAILED", failing.overview().health());
        assertEquals("DATABASE_ERROR", failing.overview().lastRun().errorCode());
        assertFalse(failing.overview().toString().contains("secret"));
        var brokenFinish = proxy(DatingRecoveryMapper.class, records, "finishRun");
        var unfinished = service(brokenFinish, f.dispatcher, true);
        unfinished.recover();
        assertEquals("RUNNING", unfinished.overview().health());
    }

    @Test void overdueQueueAndBusinessBlocksAreNotReportedAsTechnicalErrors()
    {
        long pair = pair();
        f.jdbc.update("update dating_deduction_task set next_attempt_at=? where pair_id=?", new Date(0), pair);
        assertEquals(2, recovery.overview().queue().overdue());
        assertEquals(2, recovery.issues(pair, null).size());
        f.jdbc.update("update sys_paying_user set status='1' where id=1");
        recovery.recover();
        assertEquals(1, recovery.overview().queue().blocked());
        assertEquals(0, recovery.overview().queue().errors());
        assertEquals("BLOCKED", recovery.issues(pair, null).get(0).outcome());
        assertTrue(records.history(task(pair).taskId()).isEmpty());
    }

    @Test void missingFirstHeartbeatBecomesStaleAndSqlClassificationNeverUsesMessages() throws Exception
    {
        var notStarted = new DatingRecoveryService(records, f.dispatcher, true, 1, 300000);
        Thread.sleep(10);
        assertEquals("STALE", notStarted.overview().health());
        var error = new org.springframework.jdbc.UncategorizedSQLException("Secret", "SQL secret",
            new java.sql.SQLException("private constraint", "HY000", 3819));
        assertEquals("DATA_CONFLICT", DatingRecoveryErrors.code(error));
        assertEquals("PROCESSING_ERROR", DatingRecoveryErrors.code(new IllegalStateException("DATA_CONFLICT SQL password")));
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, T delegate, String failingMethod)
    {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            if (method.getName().equals(failingMethod)) throw new DataAccessResourceFailureException("SQL secret password=private");
            try { return method.invoke(delegate, args); }
            catch (InvocationTargetException ex) { throw ex.getCause(); }
        });
    }
}
