package com.ruoyi.system.service;

import java.util.Date;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.FollowupAction;
import com.ruoyi.system.mapper.DatingRecoveryMapper;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class DatingExceptionFollowupServiceTest
{
    DatingWorkflowServiceTest f;
    DatingExceptionFollowupService service;
    DatingRecoveryMapper records;
    DatingRecoveryService recovery;
    long pair, task;

    @BeforeEach void setup() throws Exception
    {
        f = new DatingWorkflowServiceTest(); f.setup();
        try (var statement = f.schemaConnection.createStatement())
        {
            statement.execute("alter table dating_pair drop constraint ck_pair_progress");
            statement.execute("alter table dating_pair add constraint ck_pair_progress "
                + "check(progress in ('COMMUNICATING','IN_PROGRESS','FOLLOWING','REJECTED','SUCCESS_CLOSED','TERMINATED'))");
        }
        service = f.context.getBean(DatingExceptionFollowupService.class);
        records = f.context.getBean(DatingRecoveryMapper.class);
        recovery = new DatingRecoveryService(records, f.dispatcher, true, 120000, 300000);
        f.paid(1, 3, "FRIEND_ADDED"); f.paid(2, 3, "FRIEND_ADDED");
        pair = f.pair(1, 2); f.friend(pair);
        task = f.mapper.selectTasks(pair).stream().filter(t -> t.userId() == 1).findFirst().orElseThrow().taskId();
        f.jdbc.update("update dating_deduction_task set status='ERROR',attempts=5 where task_id=?", task);
    }
    @AfterEach void close() throws Exception { if (f != null) f.close(); }
    FollowupAction action(String action, long version, String status, Date due)
    { return new FollowupAction(action, version, status, due, "  Verified issue  "); }
    void claim() { service.act(task, action("CLAIM", 0, null, null), "alice", false); }

    @Test void claimUpdateReleasePreserveBusinessAndRetainVersionedHistory()
    {
        var initial = service.detail(task, "alice", false);
        assertTrue(initial.canClaim()); assertEquals(0L, initial.followup().version());
        claim();
        assertTrue(service.detail(task, "alice", false).canUpdate());
        assertFalse(service.detail(task, "bob", false).canUpdate());
        Date due = new Date((System.currentTimeMillis() / 1000 + 3600) * 1000);
        service.act(task, action("UPDATE", 1, "WAITING", due), "alice", false);
        assertEquals("WAITING", records.followup(task).status());
        assertEquals(due, records.followup(task).dueAt());
        service.act(task, action("RELEASE", 2, null, null), "admin", true);
        assertTrue(service.detail(task, "bob", false).canClaim());
        service.act(task, action("CLAIM", 3, null, null), "bob", false);
        assertEquals("bob", records.followup(task).handler());
        assertEquals(4, service.history(task).size());
        assertEquals("Verified issue", service.history(task).get(0).note());
        assertEquals(4L, records.followup(task).version());
        assertEquals(3, f.remaining(1)); assertEquals(3, f.remaining(2));
        assertEquals(0, f.deductions(pair));
        assertEquals("ERROR", f.mapper.selectTask(task).status());
        assertEquals(5, f.mapper.selectTask(task).attempts());
        assertTrue(records.history(task).isEmpty());
    }

    @Test void rejectsInvalidRequestsStaleVersionsAndNonOwners()
    {
        for (FollowupAction invalid : new FollowupAction[] {null,
            new FollowupAction("CLAIM", null, null, null, "note"),
            new FollowupAction("CLAIM", -1L, null, null, "note"),
            new FollowupAction("RESOLVE", 0L, null, null, "note"),
            new FollowupAction("CLAIM", 0L, null, null, " "),
            new FollowupAction("CLAIM", 0L, null, null, "x".repeat(501)),
            action("CLAIM", 0, "WAITING", null), action("CLAIM", 0, null, new Date(0)),
            action("UPDATE", 0, "IN_PROGRESS", null), action("RELEASE", 0, null, null)})
            assertThrows(ServiceException.class, () -> service.act(task, invalid, "alice", false));
        assertThrows(ServiceException.class, () -> service.act(task, action("CLAIM", 0, null, null), "outsider", false));
        assertThrows(ServiceException.class, () -> service.detail(task, "outsider", false));
        assertThrows(ServiceException.class, () -> service.detail(-1L, "admin", true));
        claim();
        assertThrows(ServiceException.class, () -> service.act(task, action("CLAIM", 0, null, null), "bob", false));
        assertThrows(ServiceException.class, () -> service.act(task, action("UPDATE", 1, "IN_PROGRESS", null), "bob", false));
        assertThrows(ServiceException.class, () -> service.act(task, action("UPDATE", 1, "IN_PROGRESS", null), "admin", true));
        assertThrows(ServiceException.class, () -> service.act(task, action("RELEASE", 1, null, null), "bob", false));
        assertThrows(ServiceException.class, () -> service.act(task, action("UPDATE", 1, "DONE", null), "alice", false));
        assertThrows(ServiceException.class, () -> service.act(task, action("RELEASE", 1, "WAITING", null), "alice", false));
        assertThrows(ServiceException.class, () -> service.act(task, action("CLAIM", 1, null, null), "bob", false));
        assertEquals(1, records.followupHistory(task).size());
    }

    @Test void overdueAndOwnershipFiltersApplyWithoutConferringAccess()
    {
        claim();
        f.jdbc.update("update dating_exception_followup set due_at=? where task_id=?", new Date(0), task);
        assertTrue(service.detail(task, "alice", false).overdue());
        assertEquals(1, recovery.issues(pair, "alice", null, "IN_PROGRESS", "alice", true).size());
        assertTrue(recovery.issues(pair, "alice", null, "WAITING", null, false).isEmpty());
        assertTrue(recovery.issues(pair, "alice", null, null, "bob", false).isEmpty());
        assertTrue(recovery.issues(pair, "outsider", null, null, "alice", true).isEmpty());
        assertThrows(ServiceException.class, () -> recovery.issues(pair, null, null, "DONE", null, false));
        service.act(task, action("UPDATE", 1, "WAITING", new Date(0)), "alice", false);
        assertTrue(service.detail(task, "alice", false).overdue());
        // The original claimant loses access after their only customer is reassigned.
        f.jdbc.update("update sys_paying_user set matchmaker_id=20 where id=1");
        assertThrows(ServiceException.class, () -> service.detail(task, "alice", false));
        assertThrows(ServiceException.class, () -> service.act(task, action("UPDATE", 2, "WAITING", null), "alice", false));
        assertFalse(service.detail(task, "bob", false).canRelease());
        service.act(task, action("RELEASE", 2, null, null), "admin", true);
        assertFalse(service.detail(task, "bob", false).overdue());
        assertEquals(3, records.followupHistory(task).size());
    }

    @Test void recoveryKeepsHistoryButDisablesFollowupAndOverdue()
    {
        claim();
        f.jdbc.update("update dating_exception_followup set due_at=? where task_id=?", new Date(0), task);
        f.jdbc.update("update dating_deduction_task set status='DONE',outcome='DEDUCTED' where task_id=?", task);
        assertFalse(service.detail(task, "alice", false).active());
        assertFalse(service.detail(task, "alice", false).overdue());
        assertTrue(recovery.issues(pair, "alice").isEmpty());
        assertThrows(ServiceException.class, () -> service.act(task, action("UPDATE", 1, "WAITING", null), "alice", false));
        service.act(task, action("RELEASE", 1, null, null), "alice", false);
        assertThrows(ServiceException.class, () -> service.act(task, action("CLAIM", 2, null, null), "alice", false));
        assertEquals(2, records.followupHistory(task).size());
    }

    @Test void terminalPairsAndCustomersCannotBeClaimedOrShownOverdue()
    {
        claim();
        f.jdbc.update("update dating_exception_followup set due_at=? where task_id=?", new Date(0), task);
        for (String progress : new String[] {"TERMINATED", "SUCCESS_CLOSED"})
        {
            f.jdbc.update("update dating_pair set progress=? where pair_id=?", progress, pair);
            assertFalse(service.detail(task, "alice", false).active());
            assertFalse(service.detail(task, "alice", false).overdue());
            assertTrue(recovery.issues(pair, null, null, null, null, true).isEmpty());
            assertThrows(ServiceException.class, () -> service.act(task, action("UPDATE", 1, "WAITING", null), "alice", false));
        }
        f.jdbc.update("update dating_pair set progress='FOLLOWING' where pair_id=?", pair);
        f.jdbc.update("update sys_paying_user set service_status='SUCCESS_CLOSED' where id=2");
        assertFalse(service.detail(task, "alice", false).active());
        service.act(task, action("RELEASE", 1, null, null), "admin", true);
        assertThrows(ServiceException.class, () -> service.act(task, action("CLAIM", 2, null, null), "bob", false));
    }

    @Test void historyFailureRollsBackBothNewClaimAndExistingUpdate()
    {
        f.jdbc.execute("alter table dating_exception_followup_history add constraint reject_followup check(version<0)");
        assertThrows(RuntimeException.class, this::claim);
        assertNull(records.followup(task));
        f.jdbc.execute("alter table dating_exception_followup_history drop constraint reject_followup");
        claim();
        f.jdbc.execute("alter table dating_exception_followup_history add constraint reject_followup check(version<2)");
        assertThrows(RuntimeException.class, () -> service.act(task, action("UPDATE", 1, "WAITING", null), "alice", false));
        assertEquals(1L, records.followup(task).version());
        assertEquals("IN_PROGRESS", records.followup(task).status());
        assertEquals(1, service.history(task).size());
    }
}
