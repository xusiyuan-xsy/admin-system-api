import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.vo.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.*;
import com.ruoyi.system.service.impl.SysPayingUserServiceImpl;
import com.ruoyi.web.controller.system.SysPayingRequirementController;
import jakarta.servlet.http.HttpServletRequest;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Local maintenance probe against the runtime artifact and real MySQL.
 * No HTTP login, permission bypass on the running server, sessions, or role changes.
 * Adds missing preferences only to the explicitly approved retained synthetic batch.
 */
class TestDatingRetainedMatching
{
    static final String BATCH = "FT1016182629ee3";
    static final String TAG = "MATCHTEST_20261019";
    static final String CITY = "\u5e7f\u4e1c\u7701/\u6df1\u5733\u5e02";
    static final List<Long> FEMALE = List.of(16L, 14L, 12L, 10L, 8L);
    static final List<Long> MALE = List.of(15L, 13L, 11L, 9L, 7L);
    final List<Map<String, Object>> checks = new ArrayList<>();
    final Map<String, Object> fixtures = new LinkedHashMap<>();
    SysPayingUserServiceImpl users;
    SysPayingRequirementMapper requirements;

    public static void main(String[] args) throws Exception
    {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        require(args.length == 3 && args[0].equals("--apply"),
            "Expected --apply <local-config> <protected-evidence-directory>");
        Path out = Path.of(args[2]).toAbsolutePath();
        require(Files.size(out.resolve("before-matching.sql")) > 1000
            && !Files.exists(out.resolve("matching-result.json")), "Fresh backup/evidence required");
        Object config;
        try (InputStream input = Files.newInputStream(Path.of(args[1])))
        {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            config = new Yaml(new SafeConstructor(options)).load(input);
        }
        for (String key : List.of("spring", "datasource", "druid", "master"))
            config = ((Map<?, ?>) config).get(key);
        var master = (Map<?, ?>) config;
        String url = Objects.toString(master.get("url"), "");
        require(url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$"), "Local database only");
        var ds = new DriverManagerDataSource(url, Objects.toString(master.get("username")),
            Objects.toString(master.get("password")));
        var jdbc = new JdbcTemplate(ds);
        require("ry-vue".equals(jdbc.queryForObject("select database()", String.class)), "Database mismatch");
        var f = new SqlSessionFactoryBean();
        f.setDataSource(ds);
        f.setTypeAliases(SysPayingUser.class, SysPayingRequirement.class,
            SysPayingUserStatsVo.class, SysPayingUserBirthYearStatVo.class);
        f.setMapperLocations(new ClassPathResource("mapper/system/SysPayingUserMapper.xml"),
            new ClassPathResource("mapper/system/SysPayingRequirementMapper.xml"),
            new ClassPathResource("mapper/system/DatingArchiveMapper.xml"));
        var pagination = new PageInterceptor();
        var properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        pagination.setProperties(properties);
        f.setPlugins(pagination);
        var session = new SqlSessionTemplate(f.getObject());
        var probe = new TestDatingRetainedMatching();
        probe.requirements = session.getMapper(SysPayingRequirementMapper.class);
        probe.users = new SysPayingUserServiceImpl();
        inject(probe.users, "payingUserMapper", session.getMapper(SysPayingUserMapper.class));
        inject(probe.users, "access", new DatingArchiveAccess(session.getMapper(DatingArchiveMapper.class)));
        var protection = new DatingRetainedWorkflowProbe();
        protection.jdbc = jdbc;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testType", "REAL_MYSQL_RUNTIME_SERVICE_AND_DIRECT_CONTROLLER_NOT_HTTP_AUTH");
        result.put("batch", BATCH);
        result.put("marker", TAG);
        result.put("startedAt", LocalDateTime.now().toString());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        try
        {
            tx.executeWithoutResult(status -> {
                try
                {
                    var customers = jdbc.queryForList("select * from sys_paying_user where create_by=? order by id for update",
                        "flowtest_" + BATCH);
                    require(customers.size() == 10, "Unexpected synthetic batch size");
                    for (int i = 0; i < 10; i++)
                    {
                        var row = customers.get(i);
                        require(((Number) row.get("id")).longValue() == i + 7
                            && (BATCH + "-" + (char) ('A' + i)).equals(row.get("name"))
                            && Objects.toString(row.get("remark"), "").contains("TEST ONLY")
                            && ((Number) row.get("matchmaker_id")).longValue() == 100
                            && "0".equals(row.get("del_flag")), "Synthetic fixture ownership mismatch");
                    }
                    var before = protection.fingerprints();
                    var originalRequirements = jdbc.queryForList("select * from sys_paying_requirement order by id for update");
                    for (long id = 7; id <= 15; id++)
                    {
                        require(probe.requirements.selectRequirementByPayingId(id) == null,
                            "Existing preference will not be overwritten: " + id);
                        var r = fixture(id);
                        DatingRequirementRules.validate(r);
                        r.setCreateBy("matchtest_20261019");
                        require(probe.requirements.insertRequirement(r) == 1, "Insert count mismatch");
                    }
                    localIdentity("accept_owner", 100);
                    var query = new SysPayingUser();
                    query.setName(BATCH);
                    var archives = probe.users.selectPayingUserList(query);
                    require(archives.size() == 10 && archives.stream().allMatch(u -> u.getRequirement() != null),
                        "Archive list must expose saved preferences for all test customers");
                    probe.fixtures.put("archives", archives.stream().map(TestDatingRetainedMatching::safeUser).toList());
                    for (long id = 7; id <= 16; id++)
                    {
                        var r = probe.requirements.selectRequirementByPayingId(id);
                        List<Long> expected = switch ((int) id) {
                            case 7, 15 -> FEMALE;
                            case 8, 14 -> MALE;
                            default -> List.of();
                        };
                        var rows = probe.check("saved-" + (char) ('A' + (int) id - 7), r, expected);
                        probe.fixtures.put(Long.toString(id), Map.of("requirement", r,
                            "rows", rows.stream().map(TestDatingRetainedMatching::safeUser).toList(), "total", rows.size()));
                    }
                    probe.variant("birth-year-inclusive", r -> { r.setAgeMin(1990); r.setAgeMax(1990); }, FEMALE);
                    probe.variant("height-inclusive", r -> { r.setHeightMin(170); r.setHeightMax(170); }, FEMALE);
                    probe.variant("birth-year-lower-only", r -> { r.setAgeMin(1991); r.setAgeMax(null); }, List.of());
                    probe.variant("birth-year-upper-only", r -> { r.setAgeMin(null); r.setAgeMax(1989); }, List.of());
                    probe.variant("height-lower-only", r -> { r.setHeightMin(171); r.setHeightMax(null); }, List.of());
                    probe.variant("height-upper-only", r -> { r.setHeightMin(null); r.setHeightMax(169); }, List.of());
                    probe.variant("city-full-path", r -> r.setCityRequire(CITY + "/\u5357\u5c71\u533a"), FEMALE);
                    probe.variant("city-path-prefix-not-substring", r -> r.setCityRequire(CITY.substring(0, CITY.length() - 1)), List.of());
                    probe.variant("different-city", r -> r.setCityRequire("\u5e7f\u4e1c\u7701/\u5e7f\u5dde\u5e02"), List.of());
                    probe.variant("long-distance-ignores-city", r -> {
                        r.setAcceptLongDist("1"); r.setCityRequire("\u5e7f\u4e1c\u7701/\u5e7f\u5dde\u5e02");
                    }, FEMALE);
                    probe.variant("different-hometown", r -> r.setHometownRequire("\u6d59\u6c5f\u7701"), List.of());
                    probe.variant("habits-and-no-tattoo", r -> {
                        r.setSmokeHabit("0"); r.setDrinkHabit("0"); r.setNoTattoo("1");
                    }, FEMALE);
                    probe.variant("higher-habit-tolerance", r -> { r.setSmokeHabit("2"); r.setDrinkHabit("2"); }, FEMALE);
                    probe.invalid("invalid-year-range", r -> { r.setAgeMin(2000); r.setAgeMax(1990); });
                    probe.invalid("invalid-height-range", r -> { r.setHeightMin(180); r.setHeightMax(160); });
                    probe.invalid("missing-city", r -> r.setCityRequire(null));
                    probe.invalid("missing-subject", r -> r.setPayingId(null));
                    probe.invalid("invalid-education", r -> r.setEducation("99"));
                    probe.variant("nonexistent-subject", r -> r.setPayingId(Long.MAX_VALUE), List.of());
                    localIdentity("matchtest_unassigned", Long.MAX_VALUE);
                    probe.variant("other-owner-and-forged-scope", r -> r.getParams().put("archiveOwner", null), List.of());
                    localIdentity("accept_owner", 100);
                    probe.controllerPages();
                    var states = probe.users.selectPayingUserListByRequirement(fixture(7)).stream()
                        .map(SysPayingUser::getServiceStatus).collect(java.util.stream.Collectors.toSet());
                    require(states.containsAll(Set.of("SERVING", "NOT_STARTED", "NOT_RENEWED")), "Unexpected service-state baseline");
                    result.put("knownGap", "Candidates include NOT_STARTED and NOT_RENEWED; no service eligibility filter");
                    var after = protection.fingerprints();
                    before.remove("sys_paying_requirement");
                    after.remove("sys_paying_requirement");
                    require(before.equals(after), "Unrelated business/account records changed; rolling back fixtures");
                    for (var original : originalRequirements)
                        require(original.equals(jdbc.queryForMap("select * from sys_paying_requirement where id=?", original.get("id"))),
                            "Preexisting requirement changed");
                    require(jdbc.queryForObject("select count(*) from sys_paying_requirement", Integer.class)
                        == originalRequirements.size() + 9, "Unexpected requirement count");
                    result.put("protectedTablesUnchanged", before.size());
                    result.put("existingRequirementsPreserved", originalRequirements.size());
                    result.put("insertedRequirements", 9);
                }
                catch (Exception e) { throw new IllegalStateException("Probe failed; new preferences rolled back", e); }
            });
            result.put("status", "COMMITTED_TEST_DATA_RETAINED");
            result.put("checks", probe.checks);
            result.put("finishedAt", LocalDateTime.now().toString());
            write(out.resolve("matching-result.json"), result);
            write(out.resolve("matching-ui-fixtures.json"), probe.fixtures);
            System.out.println("COMMITTED: 9 preferences; " + probe.checks.size()
                + " checks passed; existing preferences and business records unchanged.");
        }
        finally
        {
            PageHelper.clearPage();
            RequestContextHolder.resetRequestAttributes();
            SecurityContextHolder.clearContext();
        }
    }

    static SysPayingRequirement fixture(long id)
    {
        var r = new SysPayingRequirement();
        r.setPayingId(id);
        r.setAgeMin(1985); r.setAgeMax(1995);
        r.setHeightMin(160); r.setHeightMax(180);
        r.setEducation("0"); r.setNoTattoo("1"); r.setAcceptLongDist("0");
        r.setSmokeHabit("0"); r.setDrinkHabit("1"); r.setHasCar("0"); r.setHasHouse("0");
        r.setCityRequire(CITY);
        String scenario = switch ((int) id) {
            case 7 -> "same-city";
            case 8 -> { r.setAgeMin(1990); r.setAgeMax(1990); r.setHeightMin(170); r.setHeightMax(170); yield "inclusive-boundaries"; }
            case 9 -> { r.setHasCar("1"); yield "requires-car"; }
            case 10 -> { r.setHasHouse("1"); yield "requires-house"; }
            case 11 -> { r.setAgeMin(1991); r.setAgeMax(2000); yield "birth-year-no-result"; }
            case 12 -> { r.setHeightMin(171); r.setHeightMax(185); yield "height-no-result"; }
            case 13 -> { r.setEducation("5"); yield "requires-bachelor"; }
            case 14 -> { r.setHometownRequire("\u5e7f\u4e1c\u7701"); yield "hometown-prefix"; }
            case 15 -> { r.setAcceptLongDist("1"); r.setCityRequire(null); yield "long-distance"; }
            default -> throw new IllegalArgumentException("Unsupported fixture");
        };
        r.setOtherRequire(TAG + " TEST ONLY; scenario=" + scenario + "; no customer contact");
        return r;
    }

    List<SysPayingUser> check(String name, SysPayingRequirement r, List<Long> expected)
    {
        var rows = users.selectPayingUserListByRequirement(r);
        var ids = rows.stream().map(SysPayingUser::getId).toList();
        require(ids.equals(expected), name + " expected=" + expected + " actual=" + ids);
        checks.add(Map.of("name", name, "status", "PASS", "ids", ids));
        return rows;
    }

    void variant(String name, Consumer<SysPayingRequirement> change, List<Long> expected)
    {
        var r = fixture(7);
        change.accept(r);
        check(name, r, expected);
    }

    void invalid(String name, Consumer<SysPayingRequirement> change)
    {
        var r = fixture(7);
        change.accept(r);
        try { users.selectPayingUserListByRequirement(r); }
        catch (ServiceException expected) { checks.add(Map.of("name", name, "status", "PASS")); return; }
        throw new IllegalStateException(name + " was accepted");
    }

    void controllerPages() throws Exception
    {
        var controller = new SysPayingRequirementController();
        inject(controller, "payingUserService", users);
        for (int page = 1; page <= 3; page++)
        {
            var params = Map.of("pageNum", Integer.toString(page), "pageSize", "2");
            var request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> method.getName().equals("getParameter") ? params.get(args[0]) : null);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            var response = controller.search(fixture(7));
            var actual = response.getRows().stream().map(row -> ((SysPayingUser) row).getId()).toList();
            require(response.getTotal() == 5 && actual.equals(FEMALE.subList((page - 1) * 2, Math.min(page * 2, 5))),
                "Controller pagination mismatch");
            checks.add(Map.of("name", "direct-controller-page-" + page, "status", "PASS", "ids", actual, "total", response.getTotal()));
        }
    }

    static Map<String, Object> safeUser(SysPayingUser user)
    {
        require(user.getName().startsWith(BATCH + "-"), "Only synthetic customers may be exported to UI fixtures");
        var map = new LinkedHashMap<String, Object>();
        map.put("id", user.getId()); map.put("name", user.getName()); map.put("sex", user.getSex());
        map.put("birthYear", user.getBirthYear()); map.put("height", user.getHeight());
        map.put("currentCity", user.getCurrentCity()); map.put("hometown", user.getHometown());
        map.put("serviceStatus", user.getServiceStatus()); map.put("status", user.getStatus());
        map.put("requirement", user.getRequirement()); map.put("photos", List.of());
        return map;
    }

    static void localIdentity(String name, long id)
    {
        var user = new SysUser();
        user.setUserId(id); user.setUserName(name);
        var principal = new LoginUser(id, 1L, user, Set.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    static void inject(Object bean, String name, Object value) throws Exception
    {
        var field = bean.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(bean, value);
    }

    static void write(Path path, Object value) throws Exception
    {
        Files.writeString(path, JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat),
            StandardOpenOption.CREATE_NEW);
    }

    static void require(boolean condition, String message)
    {
        if (!condition) throw new IllegalStateException(message);
    }
}
