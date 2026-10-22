import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.sql.DataSource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.core.domain.entity.DatingPackage;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.DatingOrder;
import com.ruoyi.system.domain.DatingSuccess.CloseRequest;
import com.ruoyi.system.domain.dto.DatingBillingRequests.*;
import com.ruoyi.system.domain.dto.DatingWorkflowRequests.*;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.*;
import com.ruoyi.system.service.impl.DatingPackageServiceImpl;
import jakarta.validation.Validator;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Authorized local maintenance test, not HTTP authentication/authorization acceptance.
 * Calls deployed transactional services only for newly created, labelled fixtures.
 * No schema changes, global recovery scan, cleanup, or success-feature enablement.
 */
public class DatingRetainedWorkflowProbe
{
    static DataSource source;
    static final BigDecimal PRICE = new BigDecimal("0.01");
    final String batch = "FT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMddHHmmss"))
        + UUID.randomUUID().toString().substring(0, 3);
    final String actor = "flowtest_" + batch;
    final String note = batch + " TEST ONLY; simulated payment/service; no actual money or customer contact";
    static final String TEST_REGION = "\u5e7f\u4e1c\u7701/\u6df1\u5733\u5e02/\u5357\u5c71\u533a";
    static final List<String> ARCHIVE_REQUIRED = List.of("name", "phone", "sex", "birth_year", "height",
        "weight", "current_city", "hometown", "has_house", "has_car", "has_tattoo",
        "smoke_habit", "drink_habit", "accept_long_dist");
    final List<Map<String, Object>> checks = new ArrayList<>();
    final Map<String, Long> users = new LinkedHashMap<>(), pairs = new LinkedHashMap<>();
    final List<Long> orders = new ArrayList<>(), packages = new ArrayList<>();
    final Map<String, Object> result = new LinkedHashMap<>();
    JdbcTemplate jdbc;
    DatingWorkflowService workflow;
    DatingBillingService billing;
    DatingDeductionDispatcher dispatcher;
    DatingWorkflowMapper mapper;
    IDatingPackageService packageService;
    DatingSuccessService success;
    long owner;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({DatingBillingService.class, DatingPackageRules.class, DatingPackageServiceImpl.class,
        DatingWorkflowService.class, DatingDeductionService.class, DatingDeductionDispatcher.class,
        DatingExceptionFollowupService.class, DatingSuccessService.class})
    static class Config
    {
        @Bean DataSource dataSource() { return source; }
        @Bean PlatformTransactionManager transactionManager(DataSource s) { return new DataSourceTransactionManager(s); }
        @Bean SqlSessionFactory factory(DataSource s) throws Exception
        {
            var f = new SqlSessionFactoryBean();
            f.setDataSource(s);
            f.setTypeAliases(DatingPackage.class);
            f.setMapperLocations(new ClassPathResource("mapper/system/DatingBillingMapper.xml"),
                new ClassPathResource("mapper/system/DatingWorkflowMapper.xml"),
                new ClassPathResource("mapper/system/DatingRecoveryMapper.xml"),
                new ClassPathResource("mapper/system/DatingSuccessMapper.xml"),
                new ClassPathResource("mapper/system/DatingPackageMapper.xml"));
            return f.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory f) { return new SqlSessionTemplate(f); }
        @Bean DatingBillingMapper billingMapper(SqlSessionTemplate s) { return s.getMapper(DatingBillingMapper.class); }
        @Bean DatingWorkflowMapper workflowMapper(SqlSessionTemplate s) { return s.getMapper(DatingWorkflowMapper.class); }
        @Bean DatingRecoveryMapper recoveryMapper(SqlSessionTemplate s) { return s.getMapper(DatingRecoveryMapper.class); }
        @Bean DatingSuccessMapper successMapper(SqlSessionTemplate s) { return s.getMapper(DatingSuccessMapper.class); }
        @Bean DatingPackageMapper packageMapper(SqlSessionTemplate s) { return s.getMapper(DatingPackageMapper.class); }
        @Bean Validator validator() { return new LocalValidatorFactoryBean(); }
    }

    public static void main(String[] args) throws Exception
    {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        if (args.length != 3 || !Set.of("isolated", "business").contains(args[0]))
            throw new IllegalArgumentException("Expected isolated <datadir> <new-output> OR business <config> <new-output>");
        boolean business = args[0].equals("business");
        Path output = Path.of(args[2]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Output must be a new directory; no implicit reruns");
        if (business)
        {
            if (!"YES".equals(System.getenv("DATING_RETAINED_BUSINESS_APPROVED"))
                || !Files.isRegularFile(output.getParent().resolve("before-workflow.sql"))
                || Files.size(output.getParent().resolve("before-workflow.sql")) == 0)
                throw new IllegalStateException("Business run requires the wrapper's explicit approval and backup");
            Object value;
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (InputStream input = Files.newInputStream(Path.of(args[1])))
            { value = new Yaml(new SafeConstructor(options)).load(input); }
            for (String key : List.of("spring", "datasource", "druid", "master"))
                value = ((Map<?, ?>) value).get(key);
            Map<?, ?> master = (Map<?, ?>) value;
            String url = Objects.toString(master.get("url"), "");
            if (!url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$"))
                throw new IllegalArgumentException("Only the inspected local business database is allowed");
            source = new DriverManagerDataSource(url, Objects.toString(master.get("username")),
                Objects.toString(master.get("password")));
        }
        else source = new DriverManagerDataSource(
            "jdbc:mysql://127.0.0.1:3307/dating_workflow_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
            "root", "");
        var probe = new DatingRetainedWorkflowProbe();
        probe.jdbc = new JdbcTemplate(source);
        var identity = probe.jdbc.queryForMap("select @@port as port, database() as db, @@datadir as datadir");
        if (((Number) identity.get("port")).intValue() != (business ? 3306 : 3307)
            || !identity.get("db").equals(business ? "ry-vue" : "dating_workflow_test"))
            throw new IllegalStateException("Database identity mismatch");
        if (!business)
        {
            Path actual = Path.of(identity.get("datadir").toString()).toRealPath();
            Path expected = Path.of(args[1]).toRealPath();
            if (!actual.equals(expected) || !expected.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                || !expected.getFileName().toString().equals("data")
                || !expected.getParent().getFileName().toString().startsWith("dating-mysql-"))
                throw new IllegalStateException("Isolated datadir mismatch");
        }
        Files.createDirectory(output);
        probe.result.put("batch", probe.batch);
        probe.result.put("target", identity);
        probe.result.put("testType", "DEPLOYED_TRANSACTIONAL_SERVICES_NOT_HTTP_LOGIN");
        probe.result.put("maintenanceActor", probe.actor);
        probe.result.put("startedAt", LocalDateTime.now().toString());
        probe.result.put("customers", probe.users);
        probe.result.put("packages", probe.packages);
        probe.result.put("orders", probe.orders);
        probe.result.put("pairs", probe.pairs);
        probe.result.put("checks", probe.checks);
        Map<String, Map<String, Integer>> before = probe.fingerprints();
        write(output.resolve("original-row-fingerprints.json"), before);
        probe.result.put("status", "RUNNING");
        try (var context = new AnnotationConfigApplicationContext(Config.class))
        {
            probe.workflow = context.getBean(DatingWorkflowService.class);
            probe.billing = context.getBean(DatingBillingService.class);
            probe.dispatcher = context.getBean(DatingDeductionDispatcher.class);
            probe.mapper = context.getBean(DatingWorkflowMapper.class);
            probe.packageService = context.getBean(IDatingPackageService.class);
            probe.success = context.getBean(DatingSuccessService.class);
            probe.owner = probe.jdbc.queryForObject("select user_id from sys_user where user_name=? and status='0' and del_flag='0'",
                Long.class, business ? "accept_owner" : "admin");
            probe.runScenarios();
            var after = probe.fingerprints();
            boolean unchanged = before.entrySet().stream().allMatch(table ->
                table.getValue().entrySet().stream().allMatch(row ->
                    after.getOrDefault(table.getKey(), Map.of()).getOrDefault(row.getKey(), 0) >= row.getValue()));
            probe.check("all-original-protected-rows-preserved", unchanged);
            probe.result.put("protectedTables", before.size());
            probe.result.put("status", "PASSED");
        }
        catch (Throwable ex)
        {
            probe.result.put("status", "FAILED_DATA_RETAINED");
            probe.result.put("failureType", ex.getClass().getName());
            throw ex;
        }
        finally
        {
            probe.result.put("finishedAt", LocalDateTime.now().toString());
            probe.result.put("cleanupExecuted", false);
            probe.result.put("successEnabled", false);
            // Persist even partial fixtures. No compensating deletes or fabricated successful result.
            write(output.resolve("result.json"), probe.result);
            var snapshots = new LinkedHashMap<String, Object>();
            for (var user : probe.users.entrySet())
                snapshots.put(user.getKey(), Map.of("archive",
                    probe.jdbc.queryForMap("select id,name,service_status,matchmaker_id,remark from sys_paying_user where id=?", user.getValue()),
                    "entitlements", probe.jdbc.queryForList("select entitlement_id,order_id,status,total_times,used_times,remaining_times"
                        + " from dating_entitlement where user_id=? order by entitlement_id", user.getValue())));
            write(output.resolve("retained-customers.json"), snapshots);
        }
        System.out.println("Retained workflow: " + probe.checks.size() + " checks passed; batch=" + probe.batch);
    }

    static void write(Path path, Object value) throws Exception
    { Files.writeString(path, JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat), StandardOpenOption.CREATE_NEW); }
    void check(String name, boolean passed)
    {
        checks.add(Map.of("check", name, "passed", passed));
        if (!passed) throw new AssertionError(name);
    }
    void denied(String name, Runnable action, String expected)
    {
        boolean rejected = false;
        try { action.run(); }
        catch (ServiceException ex) { rejected = ex.getMessage().contains(expected); }
        check(name, rejected);
    }
    String key() { return UUID.randomUUID().toString(); }
    // Match the HTTP contract's yyyy-MM-dd HH:mm:ss precision.
    java.util.Date now() { return new java.util.Date(System.currentTimeMillis() / 1000 * 1000); }
    long customer(String label, String sex)
    {
        String phone = fixturePhone(jdbc);
        jdbc.update("insert into sys_paying_user(name,phone,password,sex,birth_year,height,weight,"
            + "current_city,hometown,has_house,has_car,has_tattoo,smoke_habit,drink_habit,accept_long_dist,"
            + "status,del_flag,remark,create_by,create_time)"
            + " values(?,?,'TEST_LOGIN_DISABLED',?,1990,170,60,?,?,'0','0','0','0','0','0','0','0',?,?,current_timestamp)",
            batch + "-" + label, phone, sex, TEST_REGION, TEST_REGION,
            note + "; synthetic phone, DO NOT CALL OR SEND SMS", actor);
        long id = jdbc.queryForObject("select id from sys_paying_user where phone=?", Long.class, phone);
        users.put(label, id);
        check("archive-required-fields-" + id,
            archiveProblems(jdbc.queryForMap("select * from sys_paying_user where id=?", id)).isEmpty());
        workflow.assign(id, new Assign(owner, null, note), actor, true);
        return id;
    }
    static String fixturePhone(JdbcTemplate jdbc)
    {
        // Format-valid synthetic data, not a verified unallocated number. Never contact it.
        String phone = "199" + String.format(Locale.ROOT, "%08d", new java.security.SecureRandom().nextInt(100000000));
        while (jdbc.queryForObject("select count(*) from sys_paying_user where phone=?", Integer.class, phone) != 0)
            phone = "199" + String.format(Locale.ROOT, "%08d", new java.security.SecureRandom().nextInt(100000000));
        return phone;
    }
    static List<String> archiveProblems(Map<String, Object> row)
    {
        var problems = new ArrayList<String>();
        for (String column : ARCHIVE_REQUIRED)
            if (Objects.toString(row.get(column), "").isBlank()) problems.add(column);
        if (!Objects.toString(row.get("phone"), "").matches("^1[3-9][0-9]{9}$")) problems.add("phone-format");
        for (String column : List.of("sex", "has_house", "has_car", "has_tattoo", "accept_long_dist"))
            if (!Set.of("0", "1").contains(Objects.toString(row.get(column), ""))) problems.add(column + "-enum");
        for (String column : List.of("smoke_habit", "drink_habit"))
            if (!Set.of("0", "1", "2").contains(Objects.toString(row.get(column), ""))) problems.add(column + "-enum");
        if (!(row.get("birth_year") instanceof Number year) || year.intValue() < 1940 || year.intValue() > 2030)
            problems.add("birth-year-range");
        if (!(row.get("height") instanceof Number height) || height.intValue() < 100) problems.add("height-range");
        return problems;
    }
    long pkg(String label, int times, String... options)
    {
        var p = new DatingPackage();
        p.setPackageName(batch + "-" + label);
        p.setPrice(PRICE); p.setLimitExpire("0"); p.setLimitServiceTimes("1"); p.setServiceTimes(times);
        p.setStatus("0"); p.setSelectionMode(options.length == 1 ? "SINGLE" : "MULTI");
        p.setServiceOptionCodes(List.of(options)); p.setCreateBy(actor); p.setRemark(note);
        packageService.insertDatingPackage(p);
        packages.add(p.getPackageId());
        return p.getPackageId();
    }
    DatingOrder order(long user, long pkg)
    {
        var request = new CreateOrder(user, pkg, key(), note);
        var order = billing.create(request, actor, true);
        orders.add(order.getOrderId());
        check("order-idempotent-" + order.getOrderId(),
            order.getOrderId().equals(billing.create(request, actor, true).getOrderId()));
        return order;
    }
    void pay(DatingOrder order)
    {
        var request = new ConfirmPayment(PRICE, "CASH", batch + "-" + key(), now(), note);
        var paid = billing.confirm(order.getOrderId(), request, actor, true);
        check("payment-idempotent-" + order.getOrderId(),
            paid.getEntitlementId().equals(billing.confirm(order.getOrderId(), request, actor, true).getEntitlementId()));
        check("one-entitlement-" + order.getOrderId(), jdbc.queryForObject(
            "select count(*) from dating_entitlement where order_id=?", Integer.class, order.getOrderId()) == 1);
        check("one-payment-" + order.getOrderId(), jdbc.queryForObject(
            "select count(*) from dating_payment_record where order_id=?", Integer.class, order.getOrderId()) == 1);
        check("one-opening-ledger-" + order.getOrderId(), jdbc.queryForObject(
            "select count(*) from dating_entitlement_ledger where order_id=? and entry_type='OPEN'",
            Integer.class, order.getOrderId()) == 1);
    }
    long pair(String label, long a, long b)
    {
        long id = workflow.recommend(new Recommend(a, b, note, key()), actor, true);
        pairs.put(label, id);
        long recommendation = mapper.selectRecommendations(id).get(0).id();
        for (long user : new long[] {a, b})
            workflow.intention(recommendation, new Intent(user, "WILLING", note), actor, true);
        return id;
    }
    void dispatch(long pair)
    {
        dispatcher.dispatch(pair);
        check("no-deduction-errors-" + pair, mapper.selectTasks(pair).stream().noneMatch(t -> t.attempts() > 0));
    }
    void friend(long pair)
    {
        var event = new ServiceEvent("FRIEND_ADDED", now(), note, null, key());
        workflow.recordEvent(pair, event, actor, true);
        workflow.recordEvent(pair, event, actor, true);
        dispatch(pair);
    }
    long schedule(long pair)
    { return workflow.schedule(pair, new Schedule(new java.util.Date(System.currentTimeMillis() + 3600000),
        "TEST ONLY - no actual appointment", note, true), actor, true); }
    void meet(long pair)
    {
        long id = schedule(pair);
        var action = new AppointmentAction("COMPLETE", now(), note, null);
        workflow.appointmentAction(id, action, actor, true);
        workflow.appointmentAction(id, action, actor, true);
        dispatch(pair);
    }
    int remaining(long user)
    { return jdbc.queryForObject("select coalesce(sum(remaining_times),0) from dating_entitlement where user_id=?", Integer.class, user); }
    String status(long user) { return mapper.selectCustomer(user).serviceStatus(); }
    void runScenarios()
    {
        check("success-feature-remains-disabled", !success.enabled());
        long single = pkg("single3", 3, "FRIEND_ADDED");
        long multi1 = pkg("all1", 1, "FRIEND_ADDED", "MEETING_COMPLETED");
        long multi2 = pkg("all2", 2, "FRIEND_ADDED", "MEETING_COMPLETED");
        for (int i = 0; i < 10; i++) customer(String.valueOf((char) ('A' + i)), i % 2 == 0 ? "0" : "1");
        long a=users.get("A"), b=users.get("B"), c=users.get("C"), d=users.get("D"),
            e=users.get("E"), f=users.get("F"), g=users.get("G"), h=users.get("H"),
            i=users.get("I"), j=users.get("J");
        var unpaid = order(g, multi2);
        check("unpaid-no-rights", remaining(g) == 0 && status(g).equals("NOT_STARTED"));
        denied("unpaid-cannot-start", () -> workflow.recommend(new Recommend(g, h, note, key()), actor, true),
            "\u4e0d\u80fd\u65b0\u589e\u670d\u52a1");
        var cancelled = order(h, multi2);
        billing.cancel(cancelled.getOrderId(), new CancelOrder(note), actor, true);
        denied("cancelled-cannot-pay", () -> billing.confirm(cancelled.getOrderId(),
            new ConfirmPayment(PRICE, "CASH", key(), now(), note), actor, true),
            "\u8ba2\u5355");
        check("cancelled-no-rights", remaining(h) == 0 && status(h).equals("NOT_STARTED"));
        pay(order(a, single)); pay(order(b, multi1));
        long ab = pair("different-conditions-renewed", a, b);
        friend(ab);
        check("independent-all-only-A-deducted", remaining(a) == 2 && remaining(b) == 1 && mapper.selectLedger(ab).size() == 1);
        meet(ab);
        check("meeting-deducts-B-once", remaining(a) == 2 && remaining(b) == 0 && mapper.selectLedger(ab).size() == 2);
        check("exhaustion-stops-service", status(b).equals("NOT_RENEWED"));
        denied("exhaustion-blocks-new-meeting", () -> schedule(ab), "\u4e0d\u80fd\u65b0\u589e\u670d\u52a1");
        var renewal = order(b, multi2);
        check("pending-renewal-stays-stopped", status(b).equals("NOT_RENEWED") && remaining(b) == 0);
        pay(renewal);
        check("paid-renewal-restores-service", status(b).equals("SERVING") && remaining(b) == 2);
        long reverse = workflow.recommend(new Recommend(b, a, note, key()), actor, true);
        check("unordered-pair-is-same", reverse == ab);
        long recommendation = mapper.selectRecommendations(ab).get(0).id();
        for (long user : new long[] {a, b})
            workflow.intention(recommendation, new Intent(user, "WILLING", note), actor, true);
        meet(ab); friend(ab);
        check("repeat-meeting-renewal-no-extra-charge", remaining(a) == 2 && remaining(b) == 2 && mapper.selectLedger(ab).size() == 2);
        pay(order(c, multi2)); pay(order(d, multi2));
        long cd = pair("both-all-completed", c, d);
        friend(cd);
        check("both-all-friend-insufficient", remaining(c) == 2 && remaining(d) == 2 && mapper.selectLedger(cd).isEmpty());
        meet(cd);
        check("both-all-complete-deduct-both", remaining(c) == 1 && remaining(d) == 1 && mapper.selectLedger(cd).size() == 2);
        denied("success-closure-disabled", () -> success.close(cd,
            new CloseRequest(now(), true, note, "TEST ONLY"), actor, true),
            "\u6210\u529f\u7ed3\u6848\u5c1a\u672a\u542f\u7528");
        check("disabled-closure-preserves-rights", remaining(c) == 1 && remaining(d) == 1
            && status(c).equals("SERVING") && status(d).equals("SERVING"));
        pay(order(e, multi1)); pay(order(f, multi1));
        long ef = pair("both-exhausted", e, f);
        meet(ef);
        check("both-all-meeting-insufficient", remaining(e) == 1 && remaining(f) == 1);
        friend(ef);
        check("both-exhausted-retained", remaining(e) == 0 && remaining(f) == 0
            && status(e).equals("NOT_RENEWED") && status(f).equals("NOT_RENEWED"));
        pay(order(i, multi2)); pay(order(j, multi2));
        long ij = pair("friend-only-awaiting-meeting", i, j);
        friend(ij);
        check("waiting-condition-retained", remaining(i) == 2 && remaining(j) == 2 && mapper.selectLedger(ij).isEmpty());
        for (long user : users.values())
            check("ledger-integrity-" + user, billing.integrityIssues(user, null).isEmpty());
        check("unpaid-order-retained", billing.detail(unpaid.getOrderId(), actor, true).getStatus().equals("UNPAID"));
        check("cancelled-order-retained", billing.detail(cancelled.getOrderId(), actor, true).getStatus().equals("CANCELLED"));
        // Keep snapshots and package records but prevent accidental purchase of test-only offerings.
        for (long id : packages)
        {
            var p = new DatingPackage();
            p.setPackageId(id); p.setStatus("1"); p.setUpdateBy(actor);
            packageService.updateDatingPackage(p);
        }
        result.put("simulatedPaidAmount", PRICE.multiply(BigDecimal.valueOf(9)));
        result.put("assignmentAccountId", owner);
        result.put("testPackagesDisabled", true);
    }

    Map<String, Map<String, Integer>> fingerprints() throws Exception
    {
        var result = new TreeMap<String, Map<String, Integer>>();
        var tables = jdbc.queryForList("select table_name from information_schema.tables where table_schema=database()"
            + " and table_type='BASE TABLE' and ((left(table_name,7)='dating_' and table_name<>'dating_recovery_run')"
            + " or table_name in ('sys_paying_user','sys_paying_requirement','sys_user','sys_role','sys_menu',"
            + "'sys_user_role','sys_role_menu','sys_role_dept','sys_user_post')) order by table_name", String.class);
        for (String table : tables)
        {
            if (!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected table name");
            var rows = new TreeMap<String, Integer>();
            for (var row : jdbc.queryForList("select * from " + table))
            {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    JSON.toJSONString(row, JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8));
                rows.merge(HexFormat.of().formatHex(hash), 1, Integer::sum);
            }
            result.put(table, rows);
        }
        return result;
    }
}
