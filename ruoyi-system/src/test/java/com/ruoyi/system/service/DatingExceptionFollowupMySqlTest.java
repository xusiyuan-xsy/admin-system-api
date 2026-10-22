package com.ruoyi.system.service;

import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import com.ruoyi.system.mapper.DatingRecoveryMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DATING_MYSQL_TEST_URL", matches = ".+")
class DatingExceptionFollowupMySqlTest
{
    DatingWorkflowMySqlTest f;
    DatingExceptionFollowupService service;
    DatingRecoveryMapper records;
    long pair, task;
    @BeforeEach void setup()
    {
        f = new DatingWorkflowMySqlTest(); f.setup();
        service = f.context.getBean(DatingExceptionFollowupService.class);
        records = f.context.getBean(DatingRecoveryMapper.class);
        f.paid(f.first, 3, "FRIEND_ADDED"); f.paid(f.second, 3, "FRIEND_ADDED");
        pair = f.pair(f.first, f.second); f.friend(pair);
        task = f.mapper.selectTasks(pair).stream().filter(t -> t.userId() == f.first).findFirst().orElseThrow().taskId();
        f.jdbc.update("update dating_deduction_task set status='ERROR',attempts=5 where task_id=?", task);
    }
    @AfterEach void close() { if (f != null) f.close(); }
    FollowupAction action(String action, long version, String status)
    { return new FollowupAction(action, version, status, null, "MySQL synthetic followup"); }
    List<Boolean> race(Callable<Boolean> one, Callable<Boolean> two) throws Exception
    {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try
        {
            var a = pool.submit(() -> { start.await(); return one.call(); });
            var b = pool.submit(() -> { start.await(); return two.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    boolean act(FollowupAction action, String actor)
    {
        try { service.act(task, action, actor, true); return true; }
        catch (ServiceException ex) { return false; }
    }

    @Test void accountJoinsWorkWhenLegacyTablesAndDatabaseDefaultsDiffer()
    {
        String accountCollation = f.jdbc.queryForObject("select collation_name from information_schema.columns"
            + " where table_schema=database() and table_name='sys_user' and column_name='user_name'", String.class);
        assertEquals("utf8mb4_general_ci", accountCollation);
        assertEquals("utf8mb4_0900_ai_ci", f.jdbc.queryForObject("select default_collation_name"
            + " from information_schema.schemata where schema_name=database()", String.class));
        for (String table : List.of("dating_exception_followup", "dating_exception_followup_history"))
            assertEquals(accountCollation, f.jdbc.queryForObject("select collation_name from information_schema.columns"
                + " where table_schema=database() and table_name=? and column_name='handler'", String.class, table));

        assertNull(records.followup(task));
        assertTrue(records.issues(pair, "admin", new Date(0), null, "UNASSIGNED", null, false)
            .stream().anyMatch(issue -> issue.taskId() == task));
        service.act(task, action("CLAIM", 0, null), "admin", true);
        String nickname = f.jdbc.queryForObject("select nick_name from sys_user where user_name='admin'", String.class);
        assertEquals(nickname, records.followup(task).handlerName());
        var own = records.issues(pair, "admin", new Date(0), null, "IN_PROGRESS", "admin", false);
        assertEquals(1, own.size());
        assertEquals(nickname, own.get(0).handlerName());
        assertTrue(records.issues(pair, "unrelated-owner", new Date(0), null, null, null, false).isEmpty());
        assertEquals("admin", records.followupHistory(task).get(0).handler());
    }

    @Test void competingClaimantsAndDuplicateUpdatesCommitExactlyOneVersion() throws Exception
    {
        assertEquals(1, race(() -> act(action("CLAIM", 0, null), "admin"),
            () -> act(action("CLAIM", 0, null), "test-supervisor")).stream().filter(Boolean::booleanValue).count());
        String handler = records.followup(task).handler();
        assertEquals(1, race(() -> act(action("UPDATE", 1, "WAITING"), handler),
            () -> act(action("UPDATE", 1, "IN_PROGRESS"), handler)).stream().filter(Boolean::booleanValue).count());
        assertEquals(2, records.followupHistory(task).size());
        assertEquals(2L, records.followup(task).version());
        assertEquals("ERROR", f.mapper.selectTask(task).status());
        assertEquals(3, f.remaining(f.first)); assertEquals(3, f.remaining(f.second));
    }

    @Test void mysqlHistoryFailureRollsBackClaimAndProgress()
    {
        f.jdbc.execute("alter table dating_exception_followup_history add constraint test_followup_failure check(task_id<>" + task + ")");
        try
        {
            assertThrows(RuntimeException.class, () -> service.act(task, action("CLAIM", 0, null), "admin", true));
            assertNull(records.followup(task));
        }
        finally { f.jdbc.execute("alter table dating_exception_followup_history drop check test_followup_failure"); }
        service.act(task, action("CLAIM", 0, null), "admin", true);
        f.jdbc.execute("alter table dating_exception_followup_history add constraint test_followup_failure "
            + "check(task_id<>" + task + " or version<2)");
        try
        {
            assertThrows(RuntimeException.class, () -> service.act(task, action("UPDATE", 1, "WAITING"), "admin", true));
            assertEquals(1L, records.followup(task).version());
            assertEquals("IN_PROGRESS", records.followup(task).status());
        }
        finally { f.jdbc.execute("alter table dating_exception_followup_history drop check test_followup_failure"); }
        assertEquals(1, records.followupHistory(task).size());
    }

    @Test void followupRacingRetryAndRecoveryCannotDeductTwiceOrReopenTask() throws Exception
    {
        service.act(task, action("CLAIM", 0, null), "admin", true);
        race(() -> act(action("UPDATE", 1, "WAITING"), "admin"), () -> {
            f.workflow.retry(task, new Retry("MySQL verified repair"), "admin", true);
            f.dispatcher.dispatch(pair);
            return true;
        });
        assertEquals("DONE", f.mapper.selectTask(task).status());
        assertEquals(2, f.remaining(f.first)); assertEquals(2, f.remaining(f.second));
        assertEquals(2, f.mapper.selectLedger(pair).size());
        assertFalse(service.detail(task, "admin", true).active());
        assertFalse(service.detail(task, "admin", true).overdue());
        assertFalse(act(action("UPDATE", records.followup(task).version(), "WAITING"), "admin"));
        service.act(task, action("RELEASE", records.followup(task).version(), null), "admin", true);
        f.dispatcher.dispatch(pair);
        assertEquals(2, f.mapper.selectLedger(pair).size());
    }
}
