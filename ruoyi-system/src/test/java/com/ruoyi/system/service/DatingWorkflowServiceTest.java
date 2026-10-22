package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.*;
import com.ruoyi.system.domain.DatingWorkflow.*;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import com.ruoyi.system.mapper.DatingWorkflowMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

class DatingWorkflowServiceTest
{
    @Configuration
    @Import({DatingBillingServiceTest.Config.class, DatingWorkflowService.class,
        DatingDeductionService.class, DatingDeductionDispatcher.class, DatingExceptionFollowupService.class})
    static class Config {}

    AnnotationConfigApplicationContext context;
    DatingWorkflowService workflow;
    DatingBillingService billing;
    DatingDeductionDispatcher dispatcher;
    DatingDeductionService worker;
    DatingWorkflowMapper mapper;
    JdbcTemplate jdbc;
    Connection schemaConnection;

    @BeforeEach void setup() throws Exception
    {
        context = new AnnotationConfigApplicationContext(Config.class);
        workflow = context.getBean(DatingWorkflowService.class);
        billing = context.getBean(DatingBillingService.class);
        dispatcher = context.getBean(DatingDeductionDispatcher.class);
        worker = context.getBean(DatingDeductionService.class);
        mapper = context.getBean(DatingWorkflowMapper.class);
        DataSource source = context.getBean(DataSource.class);
        jdbc = new JdbcTemplate(source);
        // Reuse production table DDL; strip only MySQL charset/storage declarations for H2.
        String migration = Files.readString(Path.of("../sql/upgrade/20261013_dating_workflow.sql"));
        String tables = migration.substring(migration.indexOf("CREATE TABLE"), migration.indexOf("-- Keep opening"));
        tables = tables.replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
            .replace("datetime", "timestamp(6)");
        // H2 2.4 retains the creating session in IN-set check constraints.
        schemaConnection = source.getConnection();
        ScriptUtils.executeSqlScript(schemaConnection, new ByteArrayResource(tables.getBytes(StandardCharsets.UTF_8)));
        String recovery = Files.readString(Path.of("../sql/upgrade/20261016_dating_recovery.sql"));
        recovery = recovery.substring(recovery.indexOf("CREATE TABLE"), recovery.indexOf("-- Permissions"))
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "").replaceAll(" COMMENT='[^']*'", "");
        ScriptUtils.executeSqlScript(schemaConnection, new ByteArrayResource(recovery.getBytes(StandardCharsets.UTF_8)));
        String reason = Files.readString(Path.of("../sql/upgrade/20261016_dating_retry_reason.sql"));
        String ddl = reason.substring(reason.indexOf("ALTER TABLE"), reason.indexOf("',\n"))
            .replace("''", "'");
        try (var statement = schemaConnection.createStatement()) { statement.execute(ddl); }
        String followup = Files.readString(Path.of("../sql/upgrade/20261016_dating_exception_followup.sql"));
        followup = followup.substring(followup.indexOf("CREATE TABLE"), followup.indexOf("-- Permission"))
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "").replaceAll(" COMMENT='[^']*'", "");
        ScriptUtils.executeSqlScript(schemaConnection, new ByteArrayResource(followup.getBytes(StandardCharsets.UTF_8)));
        workflow.assign(1L, new Assign(10L, null, "Initial assignment"), "alice", false);
        workflow.assign(2L, new Assign(20L, null, "Initial assignment"), "bob", false);
        workflow.assign(3L, new Assign(10L, null, "Initial assignment"), "alice", false);
    }
    @AfterEach void close() throws Exception
    {
        if (schemaConnection != null) schemaConnection.close();
        if (context != null) context.close();
    }
    String key() { return UUID.randomUUID().toString(); }
    Date now() { return new Date(); }
    Schedule scheduleRequest() { return new Schedule(new Date(System.currentTimeMillis()+3600000), "Office", "Both confirmed", true); }
    DatingOrder paid(long user, int times, String... options)
    {
        DatingPackage pkg = new DatingPackage();
        pkg.setPackageName("Workflow");
        pkg.setPrice(BigDecimal.TEN);
        pkg.setLimitExpire("0");
        pkg.setLimitServiceTimes("1");
        pkg.setServiceTimes(times);
        pkg.setStatus("0");
        pkg.setSelectionMode(options.length == 1 ? "SINGLE" : "MULTI");
        pkg.setServiceOptionCodes(List.of(options));
        pkg.setCreateBy("admin");
        context.getBean(IDatingPackageService.class).insertDatingPackage(pkg);
        DatingOrder order = billing.create(new CreateOrder(user, pkg.getPackageId(), key(), ""), "admin", true);
        return billing.confirm(order.getOrderId(), new ConfirmPayment(BigDecimal.TEN, "CASH", key(),
            new Date(1700000000000L), ""), "admin", true);
    }
    Long pair(long a, long b)
    {
        Long id = workflow.recommend(new Recommend(a,b,"Suitable",key()), "admin", true);
        willing(id, a, b);
        return id;
    }
    void willing(Long id, long a, long b)
    {
        Long rec = mapper.selectRecommendations(id).get(0).id();
        workflow.intention(rec, new Intent(a,"WILLING","Confirmed"), "admin", true);
        workflow.intention(rec, new Intent(b,"WILLING","Confirmed"), "admin", true);
    }
    Long friend(Long id)
    { return workflow.recordEvent(id, new ServiceEvent("FRIEND_ADDED", now(),"Both confirmed","record-1",key()), "admin", true); }
    Long appointment(Long id) { return workflow.schedule(id, scheduleRequest(), "admin", true); }
    void complete(Long id)
    { workflow.appointmentAction(id, new AppointmentAction("COMPLETE",now(),"Met","record-2"), "admin", true); }
    int remaining(long user)
    { return jdbc.queryForObject("select sum(remaining_times) from dating_entitlement where user_id=?", Integer.class,user); }
    int deductions(Long pair)
    { return mapper.selectLedger(pair).size(); }

    @Test void independentlyEvaluatesAllOptionsAndNeverDeductsScheduledMeetings()
    {
        paid(1,3,"FRIEND_ADDED");
        paid(2,3,"FRIEND_ADDED","MEETING_COMPLETED");
        Long pair = pair(1,2);
        Long meeting = appointment(pair);
        dispatcher.dispatch(pair);
        assertEquals(3,remaining(1));
        friend(pair);
        dispatcher.dispatch(pair);
        assertEquals(2,remaining(1));
        assertEquals(3,remaining(2));
        assertEquals("PENDING",mapper.selectParticipants(pair).get(1).deductStatus());
        complete(meeting);
        dispatcher.dispatch(pair);
        assertEquals(2,remaining(1));
        assertEquals(2,remaining(2));
        assertEquals(2,deductions(pair));
        complete(appointment(pair));
        friend(pair);
        dispatcher.dispatch(pair);
        assertEquals(2,deductions(pair));
        assertEquals(2,remaining(1));
        assertEquals(2,remaining(2));
        assertEquals(2,workflow.detail(pair,"alice",false).participants().size());
    }

    @Test void meetingFirstRequiresFriendLaterAndCustomOptionsUseFrozenSnapshot()
    {
        DatingServiceOption option = new DatingServiceOption();
        option.setName("Follow up"); option.setCompletionStandard("Confirmed"); option.setStatus("0");
        billing.saveOption(option,"admin");
        paid(1,3,"FRIEND_ADDED","MEETING_COMPLETED");
        paid(2,3,option.getCode());
        Long pair=pair(1,2);
        complete(appointment(pair));
        dispatcher.dispatch(pair);
        assertEquals(0,deductions(pair));
        jdbc.update("update dating_service_option set status='1'");
        friend(pair);
        workflow.recordEvent(pair,new ServiceEvent(option.getCode(),now(),"Done",null,key()),"admin",true);
        dispatcher.dispatch(pair);
        assertEquals(2,deductions(pair));
        assertThrows(ServiceException.class,()->workflow.recordEvent(pair,
            new ServiceEvent("UNKNOWN",now(),"Done",null,key()),"admin",true));
        assertThrows(ServiceException.class,()->workflow.recordEvent(pair,
            new ServiceEvent("MEETING_COMPLETED",now(),"Done",null,key()),"admin",true));
    }

    @Test void lastCountStopsNewServiceButAllowsHistoricalFactsAndRenewalDoesNotRededuct()
    {
        DatingOrder old=paid(1,1,"FRIEND_ADDED");
        paid(2,2,"FRIEND_ADDED","MEETING_COMPLETED");
        Long pair=pair(1,2);
        Long meeting=appointment(pair);
        Date occurred=now();
        friend(pair);
        dispatcher.dispatch(pair);
        assertEquals(0,remaining(1));
        assertEquals("NOT_RENEWED",mapper.selectCustomer(1L).serviceStatus());
        assertNotNull(mapper.selectBalance(old.getEntitlementId()).exhaustedAt());
        assertThrows(ServiceException.class,()->appointment(pair));
        workflow.appointmentAction(meeting,new AppointmentAction("COMPLETE",occurred,"Met before exhaustion",null),"admin",true);
        dispatcher.dispatch(pair);
        assertEquals(1,remaining(2));
        paid(1,4,"MEETING_COMPLETED");
        complete(appointment(pair));
        dispatcher.dispatch(pair);
        assertEquals(4,remaining(1));
        assertEquals(2,deductions(pair));
        assertEquals(old.getEntitlementId(),mapper.selectParticipants(pair).get(0).entitlementId());
    }

    @Test void cancelledNoShowRescheduledAndUnconfirmedAppointmentsDoNotCreateFacts()
    {
        paid(1,3,"MEETING_COMPLETED"); paid(2,3,"MEETING_COMPLETED");
        Long pair=pair(1,2);
        assertThrows(ServiceException.class,()->workflow.schedule(pair,
            new Schedule(scheduleRequest().plannedAt(),"Office","No consent",false),"admin",true));
        Long first=appointment(pair);
        workflow.reschedule(first,new Schedule(scheduleRequest().plannedAt(),"New place","Changed",true),"admin",true);
        workflow.appointmentAction(first,new AppointmentAction("CANCEL",null,"Cancelled",null),"admin",true);
        Long second=appointment(pair);
        workflow.appointmentAction(second,new AppointmentAction("NO_SHOW",null,"Absent",null),"admin",true);
        assertThrows(ServiceException.class,()->complete(first));
        assertEquals(0,mapper.selectEvents(pair).size());
        assertEquals(5,mapper.selectAppointmentHistory(pair).size());
        assertEquals(3,remaining(1));
    }

    @Test void reversedPairAndIdempotentEventsKeepOriginalBinding()
    {
        paid(1,3,"FRIEND_ADDED"); paid(2,3,"FRIEND_ADDED");
        Recommend req=new Recommend(1L,2L,"Suitable",key());
        Long pair=workflow.recommend(req,"admin",true);
        assertEquals(pair,workflow.recommend(req,"admin",true));
        willing(pair,1,2);
        ServiceEvent event=new ServiceEvent("FRIEND_ADDED",now(),"Confirmed",null,key());
        Long id=workflow.recordEvent(pair,event,"admin",true);
        assertEquals(id,workflow.recordEvent(pair,event,"admin",true));
        assertThrows(ServiceException.class,()->workflow.recordEvent(pair,
            new ServiceEvent("FRIEND_ADDED",event.occurredAt(),"Different",null,event.requestKey()),"admin",true));
        dispatcher.dispatch(pair);
        assertEquals(pair,pair(2,1));
        friend(pair); dispatcher.dispatch(pair);
        assertEquals(2,deductions(pair));
        assertEquals(2,mapper.selectRecommendations(pair).size());
        assertEquals(2,remaining(1));
    }

    @Test void assignmentTransfersWorkflowAndBillingScopeWithAuditAndStaleProtection()
    {
        DatingOrder order=paid(1,3,"FRIEND_ADDED");
        assertEquals(1,billing.orders(null,null,1L,"alice").size());
        workflow.assign(1L,new Assign(20L,10L,"Transfer"),"alice",false);
        assertThrows(ServiceException.class,()->billing.detail(order.getOrderId(),"alice",false));
        assertEquals(order.getOrderId(),billing.detail(order.getOrderId(),"bob",false).getOrderId());
        assertTrue(billing.orders(null,null,1L,"alice").isEmpty());
        assertEquals(1,billing.entitlements(1L,"bob").size());
        assertThrows(ServiceException.class,()->workflow.assign(1L,new Assign(10L,10L,"Stale"),"bob",false));
        Assignment history=workflow.assignments(1L,"bob",false).get(0);
        assertEquals("Alice",history.previousName());
        assertEquals("Bob",history.nextName());
        assertThrows(ServiceException.class,()->workflow.assignments(1L,"alice",false));
    }

    @Test void rejectsUnpaidUnassignedDisabledAndCrossOwnerCustomers()
    {
        assertThrows(ServiceException.class,()->pair(1,2));
        paid(1,3,"FRIEND_ADDED"); paid(2,3,"FRIEND_ADDED");
        assertThrows(ServiceException.class,()->workflow.recommend(new Recommend(1L,2L,"No scope",key()),"alice",false));
        jdbc.update("update sys_paying_user set matchmaker_id=null where id=1");
        assertThrows(ServiceException.class,()->pair(1,2));
        jdbc.update("update sys_paying_user set matchmaker_id=10,status='1' where id=1");
        assertThrows(ServiceException.class,()->pair(1,2));
        jdbc.update("update sys_paying_user set status='0' where id=1");
        Long pair=pair(1,2);
        assertThrows(ServiceException.class,()->workflow.detail(pair,"outsider",false));
        assertThrows(ServiceException.class,()->workflow.recordEvent(pair,
            new ServiceEvent("FRIEND_ADDED",now(),"Done",null,key()),"outsider",false));
        assertEquals(1,workflow.pairs(null,null,"alice").size());
        assertTrue(workflow.pairs(null,null,"outsider").isEmpty());
        assertEquals("138****0001",workflow.customers("0001",null,"alice").get(0).phone());
        jdbc.update("update sys_paying_user set service_status='SUCCESS_CLOSED' where id=1");
        assertThrows(ServiceException.class,()->friend(pair));
    }

    @Test void rejectedOrPendingIntentionsBlockNewFactsAndAppointments()
    {
        paid(1,3,"FRIEND_ADDED"); paid(2,3,"FRIEND_ADDED");
        Long pair=workflow.recommend(new Recommend(1L,2L,"Suitable",key()),"admin",true);
        assertThrows(ServiceException.class,()->friend(pair));
        Long rec=mapper.selectRecommendations(pair).get(0).id();
        workflow.intention(rec,new Intent(1L,"REJECTED","No"),"admin",true);
        assertThrows(ServiceException.class,()->friend(pair));
        assertThrows(ServiceException.class,()->appointment(pair));
        assertEquals("REJECTED",mapper.selectPair(pair).progress());
    }

    @Test void oneSideFailureDoesNotRollbackFactsOrOtherSideAndRetryIsDurable()
    {
        paid(1,3,"FRIEND_ADDED"); paid(2,3,"FRIEND_ADDED");
        Long pair=pair(1,2);
        friend(pair);
        jdbc.execute("alter table dating_entitlement_ledger add constraint force_one_side check(entry_type<>'SERVICE_DEDUCT' or user_id<>1)");
        dispatcher.dispatch(pair);
        assertEquals(3,remaining(1));
        assertEquals(2,remaining(2));
        assertEquals(1,mapper.selectEvents(pair).size());
        Task failed=mapper.selectTasks(pair).stream().filter(t->t.userId()==1).findFirst().orElseThrow();
        assertEquals(1,failed.attempts());
        for(int i=0;i<4;i++)
        {
            jdbc.update("update dating_deduction_task set next_attempt_at=current_timestamp where task_id=?", failed.taskId());
            worker.failed(failed.taskId(), new IllegalStateException("Test failure"));
        }
        assertEquals("ERROR",mapper.selectTask(failed.taskId()).status());
        jdbc.execute("alter table dating_entitlement_ledger drop constraint force_one_side");
        workflow.retry(failed.taskId(),new Retry("Verified repair"),"admin",true);
        new DatingDeductionDispatcher(mapper,worker).dispatch(null);
        dispatcher.dispatch(pair);
        assertEquals(2,deductions(pair));
        assertEquals(2,remaining(1));
        assertEquals(2,remaining(2));
        assertThrows(ServiceException.class,()->workflow.retry(failed.taskId(),new Retry("Verified repair"),"admin",true));
    }

    @Test void concurrentDuplicateTasksDeductOnlyOnce() throws Exception
    {
        paid(1,3,"FRIEND_ADDED"); paid(2,3,"FRIEND_ADDED");
        Long pair=pair(1,2); friend(pair); friend(pair);
        List<Task> tasks=mapper.selectTasks(pair).stream().filter(t->t.userId()==1).toList();
        concurrently(()->worker.process(tasks.get(0).taskId()),()->worker.process(tasks.get(1).taskId()));
        dispatcher.dispatch(pair);
        assertEquals(2,deductions(pair));
        assertEquals(2,remaining(1));
    }

    @Test void concurrentDifferentPairsCannotOverspendLastCount() throws Exception
    {
        paid(1,1,"FRIEND_ADDED"); paid(2,3,"FRIEND_ADDED"); paid(3,3,"FRIEND_ADDED");
        Long first=pair(1,2),second=pair(1,3);
        friend(first); friend(second);
        Long a=mapper.selectTasks(first).stream().filter(t->t.userId()==1).findFirst().orElseThrow().taskId();
        Long b=mapper.selectTasks(second).stream().filter(t->t.userId()==1).findFirst().orElseThrow().taskId();
        concurrently(()->worker.process(a),()->worker.process(b));
        assertEquals(0,remaining(1));
        assertEquals("NOT_RENEWED",mapper.selectCustomer(1L).serviceStatus());
        assertEquals(1,jdbc.queryForObject("select count(*) from dating_entitlement_ledger where user_id=1 and entry_type='SERVICE_DEDUCT'",Integer.class));
        Long blocked="BLOCKED".equals(mapper.selectTask(a).outcome())?a:b;
        paid(1,4,"FRIEND_ADDED");
        workflow.retry(blocked,new Retry("Verified repair"),"admin",true);
        dispatcher.dispatch(null);
        assertEquals("BLOCKED",mapper.selectTask(blocked).outcome());
        assertEquals(4,remaining(1));
    }

    void concurrently(Runnable a,Runnable b) throws Exception
    {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try
        {
            Future<?> first=pool.submit(()->{ start.await(); a.run(); return null; });
            Future<?> second=pool.submit(()->{ start.await(); b.run(); return null; });
            start.countDown();
            first.get(20,TimeUnit.SECONDS); second.get(20,TimeUnit.SECONDS);
        }
        finally { pool.shutdownNow(); }
    }
}
