import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.github.pagehelper.*;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.domain.dto.DatingMatchSearch;
import com.ruoyi.system.domain.vo.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Read-only local MySQL probe; no server sessions, account grants, or business writes. */
class TestDatingMatchingModes
{
    static void require(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    static void identity(String name, long id)
    {
        var user = new SysUser(); user.setUserId(id); user.setUserName(name);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new LoginUser(id,1L,user,Set.of()),null,List.of()));
    }
    static List<Long> ids(List<DatingMatchCandidate> rows) { return rows.stream().map(SysPayingUser::getId).toList(); }

    public static void main(String[] args) throws Exception
    {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        require(args.length==2,"Expected local-config protected-output-file");
        Path output=Path.of(args[1]).toAbsolutePath();
        require(!Files.exists(output),"Output already exists");
        Object config;
        try (InputStream input=Files.newInputStream(Path.of(args[0])))
        { config=new Yaml(new SafeConstructor(new LoaderOptions())).load(input); }
        for (String key:List.of("spring","datasource","druid","master")) config=((Map<?,?>)config).get(key);
        var master=(Map<?,?>)config;
        String url=Objects.toString(master.get("url"));
        require(url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$"),"Local DB only");
        var ds=new DriverManagerDataSource(url,Objects.toString(master.get("username")),Objects.toString(master.get("password")));
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(ds);
        factory.setTypeAliases(SysPayingUser.class,SysPayingRequirement.class,SysPayingUserStatsVo.class,SysPayingUserBirthYearStatVo.class);
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysPayingUserMapper.xml"),
            new ClassPathResource("mapper/system/SysPayingRequirementMapper.xml"),
            new ClassPathResource("mapper/system/DatingArchiveMapper.xml"),
            new ClassPathResource("mapper/system/DatingMatchingMapper.xml"));
        var interceptor=new PageInterceptor();
        var properties=new Properties(); properties.setProperty("helperDialect","mysql"); interceptor.setProperties(properties);
        factory.setPlugins(interceptor);
        var session=new SqlSessionTemplate(factory.getObject());
        var service=new DatingMatchingService(session.getMapper(DatingMatchingMapper.class),
            new DatingArchiveAccess(session.getMapper(DatingArchiveMapper.class)));
        var requirements=session.getMapper(SysPayingRequirementMapper.class);
        var manager=new DataSourceTransactionManager(ds);
        var tx=new TransactionTemplate(manager); tx.setReadOnly(true);
        var checks=new ArrayList<Map<String,Object>>();
        try
        {
            identity("accept_owner",100);
            tx.executeWithoutResult(status -> {
                var jdbc=new JdbcTemplate(ds);
                require(jdbc.queryForObject("select count(*) from sys_paying_user where create_by='flowtest_FT1016182629ee3'",Integer.class)==10,
                    "Retained synthetic fixtures missing");
                for (long id=7; id<=16; id++)
                {
                    var saved=requirements.selectRequirementByPayingId(id);
                    require(saved!=null,"Missing retained requirement");
                    var q=JSON.parseObject(JSON.toJSONString(saved),DatingMatchSearch.class);
                    var one=service.search(q);
                    require(one.stream().allMatch(DatingMatchCandidate::isServiceEligible),"Ineligible default candidate");
                    q.setMatchMode("TWO_WAY");
                    var two=service.search(q);
                    require(two.stream().allMatch(DatingMatchCandidate::isReverseMatched),"Invalid two-way candidate");
                    require(ids(one).containsAll(ids(two)),"Two-way not subset");
                    if (id==7) require(ids(one).equals(List.of(8L,16L,10L)) && ids(two).equals(List.of(8L)),
                        "Unexpected A one/two-way results: "+ids(one)+" / "+ids(two));
                    q.setMatchMode("ONE_WAY"); q.setCandidateScope("ALL");
                    var all=service.search(q);
                    require(ids(all).containsAll(ids(one)),"Archive scope not superset");
                    q.setHistoryFilter("NEW");
                    var fresh=service.search(q);
                    require(ids(fresh).equals(all.stream().filter(row->row.getPairId()==null).map(SysPayingUser::getId).toList()),
                        "History filter mismatch");
                    q.setHistoryFilter("ALL");
                    var page=PageHelper.<DatingMatchCandidate>startPage(1,1);
                    var paged=service.search(q);
                    require(page.getTotal()==all.size() && paged.size()==Math.min(1,all.size()),"Incorrect pagination total");
                    identity("unassigned_test_only",Long.MAX_VALUE);
                    q.getParams().put("archiveOwner","accept_owner");
                    require(service.search(q).isEmpty(),"Owner scope bypass");
                    identity("accept_owner",100);
                    checks.add(Map.of("userId",id,"oneWay",ids(one),"twoWay",ids(two),"allArchives",ids(all),
                        "newPairs",ids(fresh),"pagination","PASS","scope","PASS"));
                }
            });
            Files.writeString(output,JSON.toJSONString(Map.of("status","PASS","type","READ_ONLY_REAL_MYSQL_NOT_HTTP_AUTH",
                "checks",checks,"businessWrites",0),JSONWriter.Feature.PrettyFormat),StandardOpenOption.CREATE_NEW);
            System.out.println("PASS: 10 retained customers; modes, eligibility, history, pagination, scope; zero writes.");
        }
        finally { PageHelper.clearPage(); SecurityContextHolder.clearContext(); }
    }
}
