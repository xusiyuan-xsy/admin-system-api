package com.ruoyi.web;

import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.dto.DatingMatchSearch;
import com.ruoyi.system.domain.vo.DatingMatchCandidate;
import com.ruoyi.system.service.DatingMatchingService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class DatingMatchingTest
{
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    DatingMatchingService service;

    @BeforeEach void setup()
    {
        context = new AnnotationConfigApplicationContext(DatingArchiveTest.Config.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        service = context.getBean(DatingMatchingService.class);
        jdbc.execute("alter table sys_user add status char(1) default '0'");
        jdbc.execute("alter table sys_user add del_flag char(1) default '0'");
        jdbc.execute("alter table sys_user add nick_name varchar(64)");
        jdbc.execute("alter table dating_order add status varchar(20) default 'PAID'");
        jdbc.execute("alter table dating_entitlement add order_id bigint");
        jdbc.execute("alter table dating_entitlement add status varchar(20) default 'ACTIVE'");
        jdbc.execute("alter table dating_entitlement add remaining_times int default 5");
        jdbc.execute("alter table dating_pair add progress varchar(30) default 'COMMUNICATING'");
        jdbc.update("update sys_paying_user set matchmaker_id=case when id=2 then 20 else 10 end,"
            + "service_status='SERVING',education='5',has_car='0',has_house='0',has_tattoo='0',"
            + "smoke_habit='0',drink_habit='0',current_city='Province/City/District',hometown='Province/Town'");
        jdbc.update("insert into dating_order(order_id,user_id) select id,id from sys_paying_user");
        jdbc.update("insert into dating_entitlement(entitlement_id,user_id,order_id) select id,id,id from sys_paying_user");
        jdbc.update("insert into sys_paying_requirement(paying_id,age_min,age_max,height_min,height_max)"
            + " values(3,1990,1990,175,175),(4,1991,2000,160,190)");
        login("alice",10L,Set.of());
    }
    @AfterEach void close()
    {
        com.github.pagehelper.PageHelper.clearPage();
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }
    void login(String name, long id, Set<String> permissions)
    {
        SysUser user = new SysUser(); user.setUserId(id); user.setUserName(name);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new LoginUser(id,1L,user,permissions),null,List.of()));
    }
    DatingMatchSearch query()
    {
        var q = new DatingMatchSearch(); q.setPayingId(1L); return q;
    }
    List<Long> ids(DatingMatchSearch q) { return service.search(q).stream().map(SysPayingUser::getId).toList(); }

    @Test void modesExplanationsAndStableSorting()
    {
        var q=query();
        var rows=service.search(q);
        assertEquals(List.of(3L,4L),ids(q));
        assertEquals("MATCH",rows.get(0).getReciprocalStatus());
        assertEquals(List.of("出生年份不符合对方要求"),rows.get(1).getReverseConflicts());
        q.setSortBy("RECENT"); assertEquals(List.of(4L,3L),ids(q));
        q.setMatchMode("TWO_WAY"); assertEquals(List.of(3L),ids(q));
        q.setHeightMin(171); assertTrue(ids(q).isEmpty());
    }
    @Test void missingInvalidAndUnknownReverseDataNeverPassTwoWay()
    {
        var q=query(); q.setMatchMode("TWO_WAY");
        jdbc.update("delete from sys_paying_requirement where paying_id=3");
        assertTrue(ids(q).isEmpty());
        q.setMatchMode("ONE_WAY");
        assertTrue(service.search(q).stream().filter(r->r.getId()==3L).findFirst().orElseThrow()
            .getPendingConditions().stream().anyMatch(s->s.contains("尚未填写")));
        jdbc.update("insert into sys_paying_requirement(paying_id,education) values(3,'5')");
        q.setMatchMode("TWO_WAY"); assertEquals(List.of(3L),ids(q));
        jdbc.update("update sys_paying_user set education=null where id=1");
        assertTrue(ids(q).isEmpty());
        jdbc.update("update sys_paying_requirement set education='x' where paying_id=3");
        assertTrue(ids(q).isEmpty());
        jdbc.update("update sys_paying_requirement set education=null,age_min=1800 where paying_id=3");
        assertTrue(ids(q).isEmpty());
        jdbc.update("update sys_paying_requirement set age_min=null,accept_long_dist='0',city_require=' ' where paying_id=3");
        assertTrue(ids(q).isEmpty());
    }
    @Test void reverseRegionsEnumsAndFreeText()
    {
        var q=query(); q.setMatchMode("TWO_WAY");
        jdbc.update("update sys_paying_requirement set city_require='Province/City',accept_long_dist='0',"
            + "hometown_require='Province',education='5',smoke_habit='0',drink_habit='1',no_tattoo='1',"
            + "other_require='manual verification' where paying_id=3");
        assertEquals(List.of(3L),ids(q));
        assertTrue(service.search(q).get(0).getPendingConditions().get(0).contains("人工核实"));
        for (String column : List.of("current_city","hometown","education","smoke_habit","drink_habit","has_tattoo"))
        {
            String original=jdbc.queryForObject("select "+column+" from sys_paying_user where id=1",String.class);
            jdbc.update("update sys_paying_user set "+column+"=null where id=1");
            assertTrue(ids(q).isEmpty(),column);
            jdbc.update("update sys_paying_user set "+column+"=? where id=1",original);
        }
        jdbc.update("update sys_paying_requirement set city_require='Province/Cit' where paying_id=3");
        assertTrue(ids(q).isEmpty());
        jdbc.update("update sys_paying_requirement set accept_long_dist='1',has_car='1' where paying_id=3");
        assertTrue(ids(q).isEmpty());
        jdbc.update("update sys_paying_user set has_car='1' where id=1");
        assertEquals(List.of(3L),ids(q));
    }
    @Test void eligibilityAndHistoryAreFilteredBeforePagination()
    {
        var q=query();
        jdbc.update("update dating_entitlement set remaining_times=0 where user_id=4");
        var page=com.github.pagehelper.PageHelper.<DatingMatchCandidate>startPage(1,1);
        assertEquals(List.of(3L),ids(q)); assertEquals(1,page.getTotal());
        q.setCandidateScope("ALL");
        var rows=service.search(q); assertFalse(rows.get(1).isServiceEligible());
        jdbc.update("insert into dating_pair(pair_id,user_low_id,user_high_id) values(10,1,3)");
        assertEquals(10L,service.search(q).get(0).getPairId());
        q.setHistoryFilter("NEW"); assertEquals(List.of(4L),ids(q));
        q.setCandidateScope("ELIGIBLE"); assertTrue(ids(q).isEmpty());
    }
    @Test void unpaidMissingDisabledOrMultipleEntitlementsCannotBeRecommended()
    {
        var q=query();
        jdbc.update("update dating_order set status='UNPAID' where user_id=3");
        assertEquals(List.of(4L),ids(q));
        jdbc.update("update dating_order set status='PAID',user_id=2 where order_id=3");
        assertEquals(List.of(4L),ids(q));
        jdbc.update("update dating_order set user_id=3 where order_id=3");
        jdbc.update("insert into dating_entitlement(entitlement_id,user_id,order_id) values(30,3,3)");
        assertEquals(List.of(4L),ids(q));
        jdbc.update("delete from dating_entitlement where entitlement_id=30");
        jdbc.update("update sys_user set status='1' where user_id=10");
        assertTrue(ids(q).isEmpty());
        q.setCandidateScope("ALL"); assertEquals(2,ids(q).size());
        assertFalse(service.search(q).get(0).isSourceEligible());
    }
    @Test void sourceAndCandidateScopeCannotBeForged()
    {
        var q=query(); q.getParams().put("archiveOwner","bob");
        assertEquals(List.of(3L,4L),ids(q));
        q.setPayingId(2L); assertTrue(ids(q).isEmpty());
        q.setPayingId(1L);
        jdbc.update("update sys_paying_user set matchmaker_id=20 where id=1");
        assertTrue(ids(q).isEmpty());
        login("manager",30L,Set.of("dating:service:all"));
        assertEquals(3,ids(q).size());
        jdbc.update("update sys_paying_user set status='1' where id=1");
        assertTrue(ids(q).isEmpty());
    }
    @Test void latestDemandDoesNotDuplicateRowsAndInternalArchiveIsHidden() throws Exception
    {
        jdbc.update("insert into sys_paying_requirement(paying_id,has_house) values(3,'1')");
        var q=query(); q.setMatchMode("TWO_WAY"); assertTrue(ids(q).isEmpty());
        q.setMatchMode("ONE_WAY"); assertEquals(2,ids(q).size());
        var row=service.search(q).get(0); assertNotNull(row.getSourceArchive());
        assertFalse(new ObjectMapper().writeValueAsString(row).contains("sourceArchive"));
        assertFalse(com.alibaba.fastjson2.JSON.toJSONString(row).contains("sourceArchive"));
        q.setMatchMode("invalid"); assertThrows(ServiceException.class,()->ids(q));
    }
}
