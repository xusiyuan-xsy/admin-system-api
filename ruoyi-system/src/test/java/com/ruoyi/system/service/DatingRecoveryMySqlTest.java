package com.ruoyi.system.service;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.system.mapper.DatingRecoveryMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DATING_MYSQL_TEST_URL", matches = ".+")
class DatingRecoveryMySqlTest
{
    DatingWorkflowMySqlTest f;
    DatingRecoveryMapper records;
    DatingRecoveryService recovery;

    @BeforeEach void setup()
    {
        f = new DatingWorkflowMySqlTest(); f.setup();
        records = f.context.getBean(DatingRecoveryMapper.class);
        recovery = new DatingRecoveryService(records, f.dispatcher, true, 120000, 300000);
    }
    @AfterEach void close() { if (f != null) f.close(); }
    long pair()
    {
        f.paid(f.first, 3, "FRIEND_ADDED"); f.paid(f.second, 3, "FRIEND_ADDED");
        long pair = f.pair(f.first, f.second); f.friend(pair);
        return pair;
    }
    long task(long pair)
    { return f.mapper.selectTasks(pair).stream().filter(t -> t.userId() == f.first).findFirst().orElseThrow().taskId(); }

    @Test void crashInsideRealDeductionTransactionRollsBackAndRestartRecovers() throws Exception
    { crashAndRecover("BEFORE_LEDGER", 77, 3); }

    @Test void crashAfterCommitDoesNotConsumeAgainDuringRestartRecovery() throws Exception
    { crashAndRecover("AFTER_COMMIT", 78, 2); }

    void crashAndRecover(String mode, int exit, int expectedBeforeRecovery) throws Exception
    {
        long pair = pair(), task = task(pair);
        String run = UUID.randomUUID().toString();
        String directory = f.jdbc.queryForObject("select @@datadir", String.class);
        Path log = Files.createTempFile("dating-recovery-crash-", ".log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process process = new ProcessBuilder(executable, "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            DatingRecoveryCrashProbe.class.getName(), mode, String.valueOf(task), run, directory)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try
        {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Crash probe timed out: " + log);
            assertEquals(exit, process.exitValue(), "Crash hook not reached: " + log);
        }
        finally
        {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
        assertEquals(expectedBeforeRecovery, f.remaining(f.first));
        assertEquals(3, f.remaining(f.second));
        assertEquals(mode.equals("BEFORE_LEDGER") ? "READY" : "DONE", f.mapper.selectTask(task).status());
        assertEquals("RUNNING", f.jdbc.queryForObject("select status from dating_recovery_run where run_id=?", String.class, run));
        // A new Spring context simulates the new worker instance; the fact was committed before the subprocess began.
        f.context.close();
        try (var restarted = new org.springframework.context.annotation.AnnotationConfigApplicationContext(DatingWorkflowMySqlTest.Config.class))
        {
            var restartedRecovery = new DatingRecoveryService(restarted.getBean(DatingRecoveryMapper.class),
                restarted.getBean(DatingDeductionDispatcher.class), true, 120000, 300000);
            restartedRecovery.recover();
            restartedRecovery.recover();
            assertEquals("SUCCEEDED", restartedRecovery.overview().health());
        }
        assertEquals(2, f.remaining(f.first)); assertEquals(2, f.remaining(f.second));
        assertEquals(2, f.jdbc.queryForObject("select count(*) from dating_entitlement_ledger where pair_id=?", Integer.class, pair));
        assertEquals(1, f.jdbc.queryForObject("select count(*) from dating_service_event where pair_id=?", Integer.class, pair));
        assertEquals(0, f.jdbc.queryForObject("select count(*) from dating_deduction_history where task_id=?", Integer.class, task));
        f.jdbc.update("update dating_recovery_run set started_at=? where run_id=?", new Date(0), run);
        assertTrue(records.staleRuns(new Date(System.currentTimeMillis() - 120000)) >= 1);
    }

    @Test void concurrentFailureReportsOnlyAdvanceOneAttemptAndCannotOverwriteSuccess() throws Exception
    {
        long pair = pair(), task = task(pair);
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try
        {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                futures.add(pool.submit(() -> { start.await(); f.worker.failed(task, new IllegalStateException("secret")); return null; }));
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        assertEquals(1, f.mapper.selectTask(task).attempts());
        assertEquals(1, records.history(task).size());
        assertEquals("PROCESSING_ERROR", records.history(task).get(0).errorCode());
        f.worker.process(task);
        assertEquals(3, f.remaining(f.first));
        f.jdbc.update("update dating_deduction_task set next_attempt_at=current_timestamp where task_id=?", task);
        recovery.recover();
        f.worker.failed(task, new IllegalStateException("Late error"));
        assertEquals("DONE", f.mapper.selectTask(task).status());
        assertEquals(1, records.history(task).size());
        assertEquals(2, f.remaining(f.first)); assertEquals(2, f.remaining(f.second));
    }

    @Test void mysqlHistoryWriteFailureRollsBackRetryAndFailureCounters()
    {
        long pair = pair(), task = task(pair);
        f.jdbc.execute("alter table dating_deduction_history add constraint test_recovery_history check(task_id<>" + task + ")");
        try
        {
            assertThrows(RuntimeException.class, () -> f.worker.failed(task, new IllegalStateException("Test")));
            assertEquals(0, f.mapper.selectTask(task).attempts());
            f.jdbc.update("update dating_deduction_task set status='ERROR',attempts=5 where task_id=?", task);
            assertThrows(RuntimeException.class, () -> f.workflow.retry(task,
                new com.ruoyi.system.domain.dto.DatingWorkflowRequests.Retry("Verified repair"), "admin", true));
            assertEquals("ERROR", f.mapper.selectTask(task).status());
            assertEquals(5, f.mapper.selectTask(task).attempts());
        }
        finally { f.jdbc.execute("alter table dating_deduction_history drop check test_recovery_history"); }
        f.workflow.retry(task, new com.ruoyi.system.domain.dto.DatingWorkflowRequests.Retry("Verified repair"), "admin", true);
        recovery.recover();
        assertEquals(1, records.history(task).size());
        assertEquals("RETRY", records.history(task).get(0).action());
        assertEquals(2, f.remaining(f.first)); assertEquals(2, f.remaining(f.second));
    }
}
