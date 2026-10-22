package com.ruoyi.system.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.DatingSuccess.*;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import com.ruoyi.system.mapper.*;
import jakarta.validation.Validator;
import org.junit.jupiter.api.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

class DatingSuccessServiceTest
{
    DatingWorkflowServiceTest f;
    DatingSuccessService service;

    @BeforeEach void setup() throws Exception
    {
        f=new DatingWorkflowServiceTest();
        f.setup();
        String sql=Files.readString(Path.of("../sql/upgrade/20261013_dating_success.sql"));
        String tables=sql.substring(sql.indexOf("CREATE TABLE"),sql.indexOf("-- Terminal states"))
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace("datetime","timestamp(6)");
        ScriptUtils.executeSqlScript(f.schemaConnection,new ByteArrayResource(tables.getBytes(StandardCharsets.UTF_8)));
        // Translate production terminal-state alterations to H2's constraint syntax.
        var alters=java.util.regex.Pattern.compile("'(ALTER TABLE [^\\r\\n]+)'\\);").matcher(sql);
        while(alters.find())
        {
            String alter=alters.group(1).replace("''","'").replace("DROP CHECK","DROP CONSTRAINT");
            String table=alter.split(" ")[2];
            String[] parts=alter.split(", ADD CONSTRAINT",2);
            try (var statement=f.schemaConnection.createStatement())
            {
                statement.execute(parts[0]);
                statement.execute("ALTER TABLE "+table+" ADD CONSTRAINT"+parts[1]);
            }
        }
        f.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("success-test",Map.of("dating.success.enabled",true)));
        f.context.registerBean(DatingSuccessService.class);
        service=f.context.getBean(DatingSuccessService.class);
    }
    @AfterEach void close() throws Exception { if(f!=null) f.close(); }
    CloseRequest request() { return new CloseRequest(f.now(),true,"Both clients confirmed relationship","confirmation-001"); }
    Long pair(String... secondOptions)
    {
        f.paid(1,3,"FRIEND_ADDED"); f.paid(2,3,secondOptions);
        return f.pair(1,2);
    }
    int cases() { return f.jdbc.queryForObject("select count(*) from dating_success_case",Integer.class); }

    @Test void incompleteConditionsCloseWithoutFabricatingEventsOrConsumingUnusedBalance()
    {
        Long pair=pair("FRIEND_ADDED","MEETING_COMPLETED");
        Long appointment=f.appointment(pair);
        f.friend(pair);
        var result=service.close(pair,request(),"alice",false);
        assertEquals(1,cases());
        assertEquals(1,f.deductions(pair));
        assertEquals(2,f.remaining(1)); assertEquals(3,f.remaining(2));
        assertEquals("SUCCESS_CLOSED",f.mapper.selectCustomer(1L).serviceStatus());
        assertEquals("SUCCESS_CLOSED",f.mapper.selectCustomer(2L).serviceStatus());
        assertEquals("SUCCESS_CLOSED",f.mapper.selectPair(pair).progress());
        assertEquals("TERMINATED",f.mapper.selectAppointment(appointment).status());
        assertEquals("STOPPED",f.mapper.selectParticipants(pair).get(1).deductStatus());
        assertEquals(1,f.mapper.selectEvents(pair).size());
        var detail=service.detail(result.caseId(),"bob",false);
        assertEquals(2,detail.members().get(0).retainedTimes());
        assertEquals(3,detail.members().get(1).retainedTimes());
        assertEquals("confirmation-001",detail.confirmation().evidence());
        assertEquals(2,f.jdbc.queryForObject("select count(*) from dating_entitlement where status='SUCCESS_CLOSED'",Integer.class));
        assertThrows(ServiceException.class,()->f.friend(pair));
        assertThrows(ServiceException.class,()->f.appointment(pair));
        assertThrows(ServiceException.class,()->f.pair(1,2));
        assertThrows(ServiceException.class,()->f.paid(1,4,"FRIEND_ADDED"));
        f.dispatcher.dispatch(null);
        assertEquals(1,f.deductions(pair));
    }

    @Test void noServiceFactsRequiredAndNotRenewedCanClose()
    {
        Long pair=pair("FRIEND_ADDED");
        service.close(pair,request(),"admin",true);
        assertEquals(0,f.deductions(pair));
        assertEquals(3,f.remaining(1));
    }

    @Test void exhaustedUsersAndPendingRenewalCloseWithoutReopeningPaidOrders()
    {
        var first=f.paid(1,1,"FRIEND_ADDED");
        f.paid(2,1,"FRIEND_ADDED");
        Long pair=f.pair(1,2);
        f.friend(pair); f.dispatcher.dispatch(pair);
        assertEquals("NOT_RENEWED",f.mapper.selectCustomer(1L).serviceStatus());
        var renewal=f.billing.create(new CreateOrder(1L,first.getPackageId(),f.key(),""),"admin",true);
        service.close(pair,request(),"admin",true);
        assertEquals(0,f.remaining(1));
        assertEquals("CANCELLED",f.billing.detail(renewal.getOrderId(),"admin",true).getStatus());
        assertThrows(ServiceException.class,()->f.billing.confirm(renewal.getOrderId(),
            new ConfirmPayment(java.math.BigDecimal.TEN,"CASH",f.key(),f.now(),""),"admin",true));
        assertEquals("SUCCESS_CLOSED",f.mapper.selectBalance(first.getEntitlementId()).status());
    }

    @Test void settlesOtherPairsBeforeTerminationAndDoesNotCloseThirdPartyEntitlement()
    {
        Long pair=pair("FRIEND_ADDED","MEETING_COMPLETED");
        f.paid(3,3,"FRIEND_ADDED","MEETING_COMPLETED");
        Long other=f.pair(1,3);
        Long meeting=f.appointment(other);
        f.friend(other);
        service.close(pair,request(),"admin",true);
        assertEquals("TERMINATED",f.mapper.selectPair(other).progress());
        assertEquals("TERMINATED",f.mapper.selectAppointment(meeting).status());
        assertEquals(1,f.deductions(other));
        assertEquals(2,f.remaining(1)); assertEquals(3,f.remaining(3));
        assertEquals("SERVING",f.mapper.selectCustomer(3L).serviceStatus());
        assertThrows(ServiceException.class,()->f.friend(other));
        assertThrows(ServiceException.class,()->f.workflow.appointmentAction(meeting,new AppointmentAction("CANCEL",null,"Late",null),"admin",true));
        assertThrows(ServiceException.class,()->service.close(other,request(),"admin",true));
        assertEquals(1,cases());
    }

    @Test void errorInPreclosureSettlementRollsBackAllChangesAndCanRetry()
    {
        Long pair=pair("FRIEND_ADDED");
        f.friend(pair);
        f.jdbc.execute("alter table dating_entitlement_ledger add constraint reject_success_test check(entry_type<>'SERVICE_DEDUCT' or user_id<>2)");
        assertThrows(RuntimeException.class,()->service.close(pair,request(),"admin",true));
        assertEquals(0,cases()); assertEquals(0,f.deductions(pair));
        assertEquals(3,f.remaining(1)); assertEquals("SERVING",f.mapper.selectCustomer(1L).serviceStatus());
        f.jdbc.execute("alter table dating_entitlement_ledger drop constraint reject_success_test");
        f.jdbc.update("update dating_deduction_task set status='ERROR',attempts=5,next_attempt_at=?",new Date(System.currentTimeMillis()+86400000));
        service.close(pair,request(),"admin",true);
        assertEquals(2,f.deductions(pair));
    }

    @Test void duplicatesAcrossBothMatchmakersAndWorkerRaceAreIdempotent() throws Exception
    {
        Long pair=pair("FRIEND_ADDED");
        f.friend(pair);
        f.concurrently(()->service.close(pair,request(),"alice",false),()->service.close(pair,request(),"bob",false));
        assertEquals(1,cases());
        assertEquals(2,f.deductions(pair));
        var original=service.close(pair,request(),"admin",true);
        var replay=service.close(pair,new CloseRequest(f.now(),true,"Do not overwrite","new"),"admin",true);
        assertEquals(original.caseId(),replay.caseId());
        assertEquals("confirmation-001",service.detail(original.caseId(),"admin",true).confirmation().evidence());
    }

    @Test void simultaneousWorkerAndClosureNeverConsumeAfterClosure() throws Exception
    {
        Long pair=pair("FRIEND_ADDED");
        f.friend(pair);
        var task=f.mapper.selectTasks(pair).get(0);
        f.concurrently(()->f.worker.process(task.taskId()),()->service.close(pair,request(),"admin",true));
        f.worker.failed(task.taskId(), new IllegalStateException("Late failure"));
        f.worker.process(task.taskId());
        assertEquals(2,f.deductions(pair));
        assertEquals(2,f.remaining(1)); assertEquals(2,f.remaining(2));
        assertEquals("DONE",f.mapper.selectTask(task.taskId()).status());
    }

    @Test void differentPairsWithSameCustomerCanOnlyCloseOnce() throws Exception
    {
        Long pair=pair("FRIEND_ADDED");
        f.paid(3,3,"FRIEND_ADDED");
        Long other=f.pair(1,3);
        AtomicInteger denied=new AtomicInteger();
        f.concurrently(()->{try {service.close(pair,request(),"admin",true);} catch(ServiceException e){denied.incrementAndGet();}},
            ()->{try {service.close(other,request(),"admin",true);} catch(ServiceException e){denied.incrementAndGet();}});
        assertEquals(1,denied.get()); assertEquals(1,cases());
    }

    @Test void scopeStatisticsAttributionAndLegacyFlagsRemainIndependent()
    {
        Long pair=pair("FRIEND_ADDED");
        assertThrows(ServiceException.class,()->service.close(pair,request(),"outsider",false));
        var result=service.close(pair,request(),"alice",false);
        assertEquals(1L,service.stats(null,null,null,null,null).caseCount());
        assertEquals(2L,service.stats(null,null,null,null,null).peopleCount());
        assertEquals(1L,service.stats(null,null,null,10L,null).caseCount());
        assertEquals(1L,service.stats(null,null,null,20L,null).caseCount());
        assertEquals(0L,service.stats(null,null,null,null,"outsider").caseCount());
        assertEquals(0,service.list("missing",null,null,null,null).size());
        assertEquals(0,service.list(null,new Date(System.currentTimeMillis()+86400000),null,null,null).size());
        assertEquals(0,service.list(null,null,new Date(0),null,null).size());
        assertEquals(2,service.matchmakers(null).size());
        assertThrows(ServiceException.class,()->service.detail(result.caseId(),"outsider",false));
        f.workflow.assign(1L,new Assign(20L,10L,"Transfer"),"alice",false);
        assertThrows(ServiceException.class,()->service.detail(result.caseId(),"alice",false));
        assertEquals(10L,service.detail(result.caseId(),"bob",false).members().get(0).matchmakerId());
        f.jdbc.update("update sys_paying_user set status='1' where id=3");
        assertEquals(1,cases());
    }

    @Test void defaultDisabledPolicyAndConfirmationValidation()
    {
        Long pair=pair("FRIEND_ADDED");
        var disabled=new DatingSuccessService(f.context.getBean(DatingSuccessMapper.class),f.mapper,
            f.context.getBean(DatingBillingMapper.class),f.workflow,f.worker,f.context.getBean(Validator.class),false);
        assertFalse(disabled.enabled());
        assertThrows(ServiceException.class,()->disabled.close(pair,request(),"admin",true));
        assertThrows(ServiceException.class,()->service.close(pair,new CloseRequest(f.now(),false,"Note","Evidence"),"admin",true));
        assertThrows(ServiceException.class,()->service.close(pair,new CloseRequest(f.now(),true,"Note"," "),"admin",true));
        assertThrows(ServiceException.class,()->service.close(pair,new CloseRequest(new Date(0),true,"Note","Evidence"),"admin",true));
        assertThrows(ServiceException.class,()->service.close(pair,new CloseRequest(new Date(System.currentTimeMillis()+86400000),true,"Note","Evidence"),"admin",true));
        Long rec=f.mapper.selectRecommendations(pair).get(0).id();
        f.workflow.intention(rec,new Intent(1L,"REJECTED","Changed"),"admin",true);
        assertThrows(ServiceException.class,()->service.close(pair,request(),"admin",true));
        assertEquals(0,cases());
    }

    @Test void integrityCheckAcceptsOpeningDeductionRenewalAndClosure()
    {
        Long pair=pair("FRIEND_ADDED","MEETING_COMPLETED");
        assertTrue(f.billing.integrityIssues(null,null).isEmpty());
        f.friend(pair); f.dispatcher.dispatch(pair);
        assertTrue(f.billing.integrityIssues(null,null).isEmpty());
        service.close(pair,request(),"admin",true);
        assertTrue(f.billing.integrityIssues(null,null).isEmpty());
    }

    @Test void integrityCheckIsScopedReadOnlyAndDetectsLedgerOrderAndStatusConflicts()
    {
        var first=f.paid(1,3,"FRIEND_ADDED");
        f.paid(2,3,"FRIEND_ADDED");
        assertThrows(ServiceException.class,()->f.billing.entitlementDetail(first.getEntitlementId(),"bob"));
        var detail=f.billing.entitlementDetail(first.getEntitlementId(),"alice");
        assertEquals(1,((List<?>)detail.get("ledger")).size());
        var opening = (com.ruoyi.system.domain.DatingEntitlementLedgerEntry)((List<?>)detail.get("ledger")).get(0);
        assertEquals("OPEN", opening.entryType());
        assertEquals(0, opening.balanceBefore());
        assertEquals(3, opening.balanceAfter());
        assertNotNull(opening.createTime());
        f.jdbc.update("update dating_entitlement_ledger set balance_before=1,balance_after=4 where user_id=1");
        f.jdbc.update("update dating_payment_record set payment_reference='mismatch' where order_id=?",first.getOrderId());
        f.jdbc.update("update sys_paying_user set service_status='NOT_STARTED' where id=1");
        var issues=f.billing.integrityIssues(1L,"alice");
        assertEquals(Set.of("BALANCE_LEDGER","ORDER_PAYMENT","SERVICE_STATUS"),
            issues.stream().map(com.ruoyi.system.domain.DatingIntegrityIssue::code).collect(java.util.stream.Collectors.toSet()));
        assertTrue(f.billing.integrityIssues(1L,"bob").isEmpty());
        assertEquals(3,f.remaining(1));
        assertEquals(1,f.jdbc.queryForObject("select balance_before from dating_entitlement_ledger where user_id=1",Integer.class));
    }
}
