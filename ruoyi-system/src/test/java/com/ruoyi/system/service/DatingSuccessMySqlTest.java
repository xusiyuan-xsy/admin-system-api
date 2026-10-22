package com.ruoyi.system.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.DatingSuccess.CloseRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DATING_MYSQL_TEST_URL",matches=".+")
class DatingSuccessMySqlTest
{
    DatingWorkflowMySqlTest f;
    DatingSuccessService service;
    @BeforeEach void setup()
    {
        f=new DatingWorkflowMySqlTest(); f.setup();
        f.context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("success-test",Map.of("dating.success.enabled",true)));
        f.context.registerBean(DatingSuccessService.class);
        service=f.context.getBean(DatingSuccessService.class);
    }
    @AfterEach void close() { if(f!=null) f.close(); }
    CloseRequest request() { return new CloseRequest(new Date(),true,"MySQL success confirmation","test-evidence"); }
    void concurrently(Runnable... actions) throws Exception
    {
        var pool=Executors.newFixedThreadPool(actions.length);
        CountDownLatch start=new CountDownLatch(1);
        try
        {
            List<Future<?>> results=new ArrayList<>();
            for(Runnable action:actions) results.add(pool.submit(()->{start.await();action.run();return null;}));
            start.countDown();
            for(var result:results) result.get(25,TimeUnit.SECONDS);
        }
        finally {pool.shutdownNow();}
    }

    @Test void actualMysqlClosureSettlesFactsPreservesBalanceAndTerminatesOtherPair()
    {
        f.paid(f.first,3,"FRIEND_ADDED"); f.paid(f.second,3,"FRIEND_ADDED","MEETING_COMPLETED");
        f.paid(f.third,3,"MEETING_COMPLETED");
        long pair=f.pair(f.first,f.second),other=f.pair(f.first,f.third);
        long appointment=f.appointment(other);
        f.friend(pair);
        var result=service.close(pair,request(),"admin",true);
        assertEquals(2,f.remaining(f.first)); assertEquals(3,f.remaining(f.second)); assertEquals(3,f.remaining(f.third));
        assertEquals("SUCCESS_CLOSED",f.mapper.selectPair(pair).progress());
        assertEquals("TERMINATED",f.mapper.selectPair(other).progress());
        assertEquals("TERMINATED",f.mapper.selectAppointment(appointment).status());
        assertEquals("SERVING",f.mapper.selectCustomer(f.third).serviceStatus());
        var detail=service.detail(result.caseId(),"admin",true);
        assertEquals(2,detail.members().size());
        assertTrue(service.stats(null,result.successAt(),new Date(result.successAt().getTime()+1000),1L,null).caseCount()>=1);
        assertTrue(service.list(null,null,null,1L,null).stream().anyMatch(c->c.caseId().equals(result.caseId())));
        assertFalse(service.matchmakers(null).isEmpty());
    }

    @Test void concurrentClosuresAndDeductionWorkerCreateOneCaseAndOneChargeEach() throws Exception
    {
        f.paid(f.first,1,"FRIEND_ADDED"); f.paid(f.second,1,"FRIEND_ADDED");
        long pair=f.pair(f.first,f.second);
        f.friend(pair);
        long task=f.mapper.selectTasks(pair).get(0).taskId();
        concurrently(()->service.close(pair,request(),"admin",true),
            ()->service.close(pair,request(),"admin",true),()->f.worker.process(task));
        assertEquals(1,f.jdbc.queryForObject("select count(*) from dating_success_case where pair_id=?",Integer.class,pair));
        assertEquals(2,f.mapper.selectLedger(pair).size());
        assertEquals(0,f.remaining(f.first)); assertEquals(0,f.remaining(f.second));
        assertEquals("SUCCESS_CLOSED",f.mapper.selectCustomer(f.first).serviceStatus());
        f.dispatcher.dispatch(pair);
        assertEquals(2,f.mapper.selectLedger(pair).size());
    }

    @Test void concurrentDifferentPairClosureCannotCloseSameCustomerTwice() throws Exception
    {
        f.paid(f.first,3,"FRIEND_ADDED"); f.paid(f.second,3,"FRIEND_ADDED"); f.paid(f.third,3,"FRIEND_ADDED");
        long pair=f.pair(f.first,f.second),other=f.pair(f.first,f.third);
        AtomicInteger denied=new AtomicInteger();
        concurrently(()->{try {service.close(pair,request(),"admin",true);}catch(ServiceException e){denied.incrementAndGet();}},
            ()->{try {service.close(other,request(),"admin",true);}catch(ServiceException e){denied.incrementAndGet();}});
        assertEquals(1,denied.get());
        assertEquals(1,f.jdbc.queryForObject("select count(*) from dating_success_case_member where user_id=?",Integer.class,f.first));
    }
}
