package com.ruoyi.web;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.*;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.redis.RedisCache;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import static com.ruoyi.web.DatingWorkflowHttpTestConfig.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DATING_WORKFLOW_HTTP_TEST", matches = "true")
@SpringBootTest(classes = DatingWorkflowHttpTestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.config.name=dating-workflow-http-isolated", "spring.main.banner-mode=off",
        "server.address=127.0.0.1", "token.header=Authorization", "token.expireTime=30",
        "user.password.maxRetryCount=5", "user.password.lockTime=10", "dating.success.enabled=false",
        "spring.jackson.time-zone=UTC"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatingWorkflowHttpMySqlTest
{
    static final Path PHOTOS = Path.of(System.getProperty("java.io.tmpdir"), "dating-workflow-photos-" + UUID.randomUUID());
    static final String ARCHIVES = "/system/payingUsers";
    static final String PACKAGES = "/system/datingPackage";
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry)
    {
        String secret = Base64.getEncoder().encodeToString(
            (UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        registry.add("token.secret", () -> secret);
        registry.add("dating.attachments.root", PHOTOS::toString);
    }

    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired RedisCache cache;
    @Autowired DatingAuthTestConfig.IsolatedRedisTemplate redis;
    @Autowired DatingAuthTestConfig.AuditScheduler scheduledExecutorService;
    @Autowired org.springframework.context.ApplicationContext context;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final Map<String, String> tokens = new HashMap<>();
    JdbcTemplate jdbc;

    @BeforeAll void loginRoles() throws Exception
    {
        jdbc = new JdbcTemplate(dataSource);
        for (String role : List.of("alice", "bob", "finance", "manager", "viewer"))
        {
            JsonNode captcha = call("GET", "/captchaImage", null, null, 200);
            assertTrue(captcha.path("captchaEnabled").asBoolean());
            String uuid = captcha.path("uuid").asText();
            String answer = cache.getCacheObject(CacheConstants.CAPTCHA_CODE_KEY + uuid);
            assertNotNull(answer);
            JsonNode result = call("POST", "/login", null, Map.of("username", PREFIX + role,
                "password", PASSWORD, "uuid", uuid, "code", answer), 200);
            assertTrue(result.hasNonNull("token"));
            tokens.put(role, result.path("token").asText());
        }
    }

    @AfterAll void drainAuditAndCleanRedis() throws Exception
    {
        scheduledExecutorService.shutdown();
        assertTrue(scheduledExecutorService.awaitTermination(15, TimeUnit.SECONDS), "Audit tasks did not finish");
        redis.close();
        for (String key : Set.copyOf(redis.keys)) assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        assertTrue(scheduledExecutorService.failures.isEmpty(), "Asynchronous audit failure");
        assertEquals(5, number("select count(*) from sys_logininfor where user_name like ? and status='0'", PREFIX + "%"));
        for (var row : jdbc.queryForList("select oper_param,json_result,error_msg from sys_oper_log where oper_name like ?", PREFIX + "%"))
        {
            String text = row.toString();
            assertFalse(text.contains(PASSWORD), "Password leaked into operation audit");
            for (String token : tokens.values()) assertFalse(text.contains(token), "Token leaked into operation audit");
        }
    }

    String now() { return TIME.format(Instant.now()); }
    String key() { return UUID.randomUUID().toString(); }
    int number(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    JsonNode call(String method, String path, String role, Object body, int expected) throws Exception
    {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (role != null) request.header("Authorization", "Bearer " + tokens.get(role));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), method + " " + path);
        JsonNode node = json.readTree(response.body());
        // Never print response bodies, passwords or tokens into test reports.
        assertEquals(expected, node.path("code").asInt(), method + " " + path);
        return node;
    }
    JsonNode data(String method, String path, String role, Object body) throws Exception
    { return call(method, path, role, body, 200).path("data"); }
    long customer() throws Exception
    {
        String phone = "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100000000));
        String name = "HTTP-" + key().substring(0, 8);
        call("POST", ARCHIVES, "alice", Map.of("phone", phone, "password", PASSWORD, "name", name,
            "sex", "0", "birthYear", 1990, "height", 170, "weight", 120, "status", "0"), 200);
        JsonNode rows = call("GET", ARCHIVES + "/list?phone=" + phone, "alice", null, 200).path("rows");
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).has("password"));
        return rows.get(0).path("id").asLong();
    }
    long packageId(int times, String... options) throws Exception
    {
        String name = "HTTP-" + key();
        call("POST", PACKAGES, "manager", Map.of("packageName", name, "price", 10,
            "limitExpire", "0", "limitServiceTimes", "1", "serviceTimes", times, "status", "0",
            "selectionMode", options.length == 1 ? "SINGLE" : "MULTI", "serviceOptionCodes", List.of(options)), 200);
        JsonNode rows = call("GET", PACKAGES + "/list?packageName=" + name, "manager", null, 200).path("rows");
        assertEquals(1, rows.size());
        return rows.get(0).path("packageId").asLong();
    }
    Map<String, Object> orderBody(long user, long pkg)
    { return Map.of("userId", user, "packageId", pkg, "requestKey", key(), "remark", "HTTP order"); }
    JsonNode order(long user, long pkg) throws Exception
    { return data("POST", "/dating/orders", "alice", orderBody(user, pkg)); }
    Map<String, Object> payment()
    { return Map.of("paidAmount", 10, "paymentMethod", "CASH", "paymentReference", key(), "paidAt", now(), "remark", "HTTP payment"); }
    JsonNode pay(long id) throws Exception
    { return data("POST", "/dating/orders/" + id + "/confirm-payment", "finance", payment()); }
    long paid(long user, int times, String... options) throws Exception
    { return pay(order(user, packageId(times, options)).path("orderId").asLong()).path("entitlementId").asLong(); }
    void assign(long user, long next, Long previous, String role, int expected) throws Exception
    {
        var body = new HashMap<String, Object>();
        body.put("matchmakerId", next); body.put("expectedMatchmakerId", previous); body.put("reason", "HTTP assignment");
        call("POST", "/dating/users/" + user + "/assign", role, body, expected);
    }
    long recommend(long first, long second) throws Exception
    {
        return data("POST", "/dating/recommendations", "alice", Map.of("firstUserId", first, "secondUserId", second,
            "reason", "HTTP recommendation", "requestKey", key())).asLong();
    }
    JsonNode detail(long pair) throws Exception { return data("GET", "/dating/matches/" + pair, "alice", null); }
    void willing(long pair, long first, long second) throws Exception
    {
        long recommendation = detail(pair).path("recommendations").get(0).path("id").asLong();
        for (long id : new long[] {first, second})
            call("POST", "/dating/recommendations/" + recommendation + "/intentions", "alice",
                Map.of("userId", id, "intention", "WILLING", "reason", "HTTP confirmed"), 200);
    }
    Map<String, Object> event(String option)
    { return Map.of("optionCode", option, "occurredAt", now(), "note", "HTTP confirmed", "requestKey", key()); }
    Map<String, Object> schedule()
    { return Map.of("plannedAt", TIME.format(Instant.now().plusSeconds(3600)), "place", "HTTP office",
        "reason", "HTTP confirmed", "bothConfirmed", true); }
    long appointment(long pair) throws Exception
    {
        call("POST", "/dating/matches/" + pair + "/appointments", "alice", schedule(), 200);
        return detail(pair).path("appointments").get(0).path("appointmentId").asLong();
    }
    Map<String, Object> action(String action)
    { return Map.of("action", action, "occurredAt", now(), "reason", "HTTP outcome"); }
    void complete(long appointment) throws Exception
    { call("POST", "/dating/appointments/" + appointment + "/actions", "alice", action("COMPLETE"), 200); }
    int remaining(long entitlement) throws Exception
    { return data("GET", "/dating/entitlements/" + entitlement, "alice", null).path("entitlement").path("remainingTimes").asInt(); }
    void status(long user, String expected) throws Exception
    { assertEquals(expected, data("GET", ARCHIVES + "/" + user, "alice", null).path("serviceStatus").asText()); }
    void integrity(long user) throws Exception
    { assertEquals(0, call("GET", "/dating/integrity-issues?userId=" + user, "finance", null, 200).path("total").asInt()); }

    @Test void explicitIsolationAndRealRolePermissions() throws Exception
    {
        assertEquals(1, context.getBeansOfType(DataSource.class).size());
        assertTrue(context.getBeansOfType(com.ruoyi.RuoYiApplication.class).isEmpty());
        assertTrue(context.getBeansOfType(com.ruoyi.web.core.config.DatingRecoveryConfig.class).isEmpty());
        try (var connection = dataSource.getConnection()) { assertEquals(URL, connection.getMetaData().getURL()); }
        assertThrows(IllegalArgumentException.class, () -> redis.keys("*"));
        JsonNode info = call("GET", "/getInfo", "alice", null, 200);
        Set<String> permissions = new HashSet<>();
        info.path("permissions").forEach(p -> permissions.add(p.asText()));
        assertEquals(new HashSet<>(SERVICE_PERMISSIONS), permissions);
        assertEquals(ALICE, info.path("user").path("userId").asLong());
        assertFalse(info.path("user").has("password"));
        for (String path : List.of("/dating/orders", "/dating/assignments", "/dating/matches"))
        {
            call("GET", path, null, null, 401);
            call("GET", path, "viewer", null, 403);
        }
        long user = customer(), pkg = packageId(2, "FRIEND_ADDED");
        JsonNode order = order(user, pkg);
        long id = order.path("orderId").asLong();
        call("POST", "/dating/orders/" + id + "/confirm-payment", "alice", payment(), 403);
        call("POST", "/dating/orders", "bob", orderBody(user, pkg), 500);
        call("GET", "/dating/orders/" + id, "bob", null, 500);
        call("GET", ARCHIVES + "/" + user, "finance", null, 500);
        assertEquals(0, number("select count(*) from dating_entitlement where user_id=?", user));
        status(user, "NOT_STARTED");
    }

    @Test void allConditionsPerPersonExhaustionRenewalAndOncePerUnorderedPair() throws Exception
    {
        long a = customer(), b = customer();
        long ea = paid(a, 3, "FRIEND_ADDED"), eb = paid(b, 1, "FRIEND_ADDED", "MEETING_COMPLETED");
        assign(a, ALICE, null, "alice", 200); assign(b, ALICE, null, "alice", 200);
        long pair = recommend(a, b);
        willing(pair, a, b);
        long meeting = appointment(pair);
        Map<String, Object> friend = event("FRIEND_ADDED");
        call("POST", "/dating/matches/" + pair + "/service-events", "alice", friend, 200);
        call("POST", "/dating/matches/" + pair + "/service-events", "alice", friend, 200);
        assertEquals(1, detail(pair).path("events").size());
        assertEquals(2, remaining(ea)); assertEquals(1, remaining(eb));
        assertEquals(1, detail(pair).path("ledger").size());
        Map<String, Object> completion = action("COMPLETE");
        call("POST", "/dating/appointments/" + meeting + "/actions", "alice", completion, 200);
        call("POST", "/dating/appointments/" + meeting + "/actions", "alice", completion, 200);
        assertEquals(2, remaining(ea)); assertEquals(0, remaining(eb));
        status(b, "NOT_RENEWED");
        assertEquals(2, detail(pair).path("ledger").size());
        call("POST", "/dating/matches/" + pair + "/appointments", "alice", schedule(), 500);
        long renewalOrder = order(b, packageId(2, "FRIEND_ADDED", "MEETING_COMPLETED")).path("orderId").asLong();
        status(b, "NOT_RENEWED");
        call("POST", "/dating/matches/" + pair + "/appointments", "alice", schedule(), 500);
        long renewal = pay(renewalOrder).path("entitlementId").asLong();
        status(b, "SERVING");
        complete(appointment(pair));
        assertEquals(pair, recommend(b, a));
        willing(pair, b, a);
        complete(appointment(pair));
        assertEquals(2, detail(pair).path("ledger").size());
        assertEquals(2, remaining(ea)); assertEquals(2, remaining(renewal));
        for (JsonNode participant : detail(pair).path("participants"))
            assertEquals(participant.path("userId").asLong() == a ? ea : eb, participant.path("entitlementId").asLong());
        integrity(a); integrity(b);
        audit("/dating/matches/" + pair + "/service-events", "alice", 0, 2);
        audit("/dating/appointments/" + meeting + "/actions", "alice", 0, 2);
        audit("/dating/matches/" + pair + "/appointments", "alice", 0, 3);
        audit("/dating/matches/" + pair + "/appointments", "alice", 1, 2);
    }

    @Test void concurrentOrderAndPaymentReplaysKeepSnapshotAndOneOpeningLedger() throws Exception
    {
        long user = customer(), pkg = packageId(3, "FRIEND_ADDED", "MEETING_COMPLETED");
        var body = orderBody(user, pkg);
        List<JsonNode> orders = concurrent("POST", "/dating/orders", "alice", body);
        long id = orders.get(0).path("data").path("orderId").asLong();
        assertEquals(id, orders.get(1).path("data").path("orderId").asLong());
        assertEquals(1, number("select count(*) from dating_order where user_id=?", user));
        var mismatch = new HashMap<>(body); mismatch.put("remark", "Changed");
        call("POST", "/dating/orders", "alice", mismatch, 500);
        JsonNode original = data("GET", "/dating/orders/" + id, "alice", null);
        var changed = data("GET", PACKAGES + "/" + pkg, "manager", null).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed).put("price", 20).put("serviceTimes", 8)
            .put("selectionMode", "SINGLE").set("serviceOptionCodes", json.valueToTree(List.of("FRIEND_ADDED")));
        call("PUT", PACKAGES, "manager", changed, 200);
        var payment = payment();
        var wrong = new HashMap<>(payment); wrong.put("paidAmount", 20);
        call("POST", "/dating/orders/" + id + "/confirm-payment", "finance", wrong, 500);
        assertEquals(0, number("select count(*) from dating_entitlement where order_id=?", id));
        status(user, "NOT_STARTED");
        List<JsonNode> payments = concurrent("POST", "/dating/orders/" + id + "/confirm-payment", "finance", payment);
        long entitlement = payments.get(0).path("data").path("entitlementId").asLong();
        assertEquals(entitlement, payments.get(1).path("data").path("entitlementId").asLong());
        assertEquals(original.path("ruleSnapshot"), payments.get(0).path("data").path("ruleSnapshot"));
        assertEquals(3, remaining(entitlement));
        assertEquals(1, number("select count(*) from dating_payment_record where order_id=?", id));
        assertEquals(1, number("select count(*) from dating_entitlement where order_id=?", id));
        assertEquals(1, number("select count(*) from dating_entitlement_ledger where entitlement_id=? and entry_type='OPEN'", entitlement));
        var replacement = new HashMap<>(payment); replacement.put("paymentReference", key());
        call("POST", "/dating/orders/" + id + "/confirm-payment", "finance", replacement, 500);
        integrity(user);
        audit("/dating/orders/" + id + "/confirm-payment", "finance", 0, 2);
        audit("/dating/orders/" + id + "/confirm-payment", "finance", 1, 2);
        assertEquals(2, number("select count(*) from sys_oper_log where oper_name=? and oper_url='/dating/orders' "
            + "and status=0 and oper_param like ?", PREFIX + "alice", "%" + body.get("requestKey") + "%"));
    }

    List<JsonNode> concurrent(String method, String path, String role, Object body) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Callable<JsonNode> task = () -> { start.await(); return call(method, path, role, body, 200); };
            Future<JsonNode> one = pool.submit(task), two = pool.submit(task);
            start.countDown();
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }
        finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void intentionsAndAppointmentOutcomesCannotFabricateDeductibleMeeting() throws Exception
    {
        long a = customer(), b = customer();
        long ea = paid(a, 2, "MEETING_COMPLETED"), eb = paid(b, 2, "MEETING_COMPLETED");
        assign(a, ALICE, null, "alice", 200); assign(b, ALICE, null, "alice", 200);
        long pair = recommend(a, b);
        call("POST", "/dating/matches/" + pair + "/appointments", "alice", schedule(), 500);
        willing(pair, a, b);
        call("GET", "/dating/matches/" + pair, "bob", null, 500);
        call("POST", "/dating/matches/" + pair + "/service-events", "bob", event("MEETING_COMPLETED"), 500);
        call("POST", "/dating/matches/" + pair + "/service-events", "alice", event("MEETING_COMPLETED"), 500);
        call("POST", "/dating/matches/" + pair + "/service-events", "alice", event("FRIEND_ADDED"), 500);
        for (String outcome : List.of("CANCEL", "NO_SHOW"))
        {
            long appointment = appointment(pair);
            call("PUT", "/dating/appointments/" + appointment, "alice", schedule(), 200);
            call("POST", "/dating/appointments/" + appointment + "/actions", "alice", action(outcome), 200);
            call("POST", "/dating/appointments/" + appointment + "/actions", "alice", action("COMPLETE"), 500);
        }
        assertEquals(0, detail(pair).path("events").size());
        assertEquals(0, detail(pair).path("ledger").size());
        assertEquals(2, remaining(ea)); assertEquals(2, remaining(eb));
        complete(appointment(pair));
        assertEquals(1, detail(pair).path("events").size());
        assertEquals(1, remaining(ea)); assertEquals(1, remaining(eb));
        integrity(a); integrity(b);
    }

    @Test void actualAssignmentRevokesArchivePhotoAndBillingAccessAndPersistsAudit() throws Exception
    {
        long user = customer(), entitlement = paid(user, 2, "FRIEND_ADDED");
        long order = data("GET", "/dating/entitlements/" + entitlement, "alice", null)
            .path("entitlement").path("orderId").asLong();
        String photo = upload(user);
        call("PUT", ARCHIVES, "alice", Map.of("id", user, "photos", List.of(photo)), 200);
        readPhoto(photo, "alice");
        assign(user, ALICE, null, "alice", 200);
        assign(user, BOB, ALICE, "alice", 200);
        call("GET", ARCHIVES + "/" + user, "alice", null, 500);
        call("PUT", ARCHIVES, "alice", Map.of("id", user, "name", "Forbidden"), 500);
        call("GET", photo, "alice", null, 500);
        call("GET", "/dating/orders/" + order, "alice", null, 500);
        call("GET", "/dating/entitlements/" + entitlement, "alice", null, 500);
        readPhoto(photo, "bob");
        call("GET", ARCHIVES + "/" + user, "bob", null, 200);
        call("GET", "/dating/orders/" + order, "bob", null, 200);
        call("GET", "/dating/entitlements/" + entitlement, "bob", null, 200);
        assign(user, ALICE, ALICE, "manager", 500);
        assertEquals(BOB, jdbc.queryForObject("select matchmaker_id from sys_paying_user where id=?", Long.class, user));
        assertEquals(2, data("GET", "/dating/users/" + user + "/assignments", "bob", null).size());
        audit("/dating/users/" + user + "/assign", "alice", 0, 2);
        audit("/dating/users/" + user + "/assign", "manager", 1, 1);
        audit(photo, "bob", 0, 1);
        audit(photo, "alice", 1, 1);
        audit(ARCHIVES, "alice", 1, 1);
        for (var row : jdbc.queryForList("select oper_param,json_result from sys_oper_log "
            + "where oper_name like ? and (oper_url=? or oper_url like ?)", PREFIX + "%", ARCHIVES, "/dating/archives/%"))
        {
            assertTrue(Objects.toString(row.get("oper_param"), "").isBlank());
            assertTrue(Objects.toString(row.get("json_result"), "").isBlank());
        }
    }

    @Test void cancelledUnpaidOrderCannotStartServiceOrLaterBePaid() throws Exception
    {
        long a = customer(), b = customer();
        long order = order(a, packageId(2, "FRIEND_ADDED")).path("orderId").asLong();
        paid(b, 2, "FRIEND_ADDED");
        assign(a, ALICE, null, "alice", 200); assign(b, ALICE, null, "alice", 200);
        call("POST", "/dating/recommendations", "alice", Map.of("firstUserId", a, "secondUserId", b,
            "reason", "Unpaid", "requestKey", key()), 500);
        call("POST", "/dating/orders/" + order + "/cancel", "alice", Map.of("reason", "HTTP cancelled"), 200);
        call("POST", "/dating/orders/" + order + "/confirm-payment", "finance", payment(), 500);
        assertEquals("CANCELLED", data("GET", "/dating/orders/" + order, "alice", null).path("status").asText());
        status(a, "NOT_STARTED");
        assertEquals(0, number("select count(*) from dating_entitlement where user_id=?", a));
        audit("/dating/orders/" + order + "/cancel", "alice", 0, 1);
        audit("/dating/orders/" + order + "/confirm-payment", "finance", 1, 1);
    }

    @Test void successClosureRemainsDisabledAndCannotMutatePaidRights() throws Exception
    {
        long a = customer(), b = customer();
        long ea = paid(a, 2, "FRIEND_ADDED"), eb = paid(b, 2, "FRIEND_ADDED");
        assign(a, ALICE, null, "alice", 200); assign(b, ALICE, null, "alice", 200);
        long pair = recommend(a, b);
        willing(pair, a, b);
        assertFalse(data("GET", "/dating/success-policy", "manager", null).path("enabled").asBoolean(true));
        int before = number("select count(*) from dating_success_case");
        call("POST", "/dating/matches/" + pair + "/close-success", "manager",
            Map.of("successAt", now(), "bothConfirmed", true, "note", "HTTP closed", "evidence", "HTTP synthetic"), 500);
        assertEquals(before, number("select count(*) from dating_success_case"));
        status(a, "SERVING"); status(b, "SERVING");
        assertEquals(2, remaining(ea)); assertEquals(2, remaining(eb));
        assertEquals("IN_PROGRESS", detail(pair).path("pair").path("progress").asText());
        audit("/dating/matches/" + pair + "/close-success", "manager", 1, 1);
    }

    @Test void recoveryQueriesRespectRolesOwnershipAndPreserveFailureHistoryAfterRetry() throws Exception
    {
        for (String path : List.of("/dating/recovery/overview", "/dating/recovery/runs"))
        {
            call("GET", path, null, null, 401);
            call("GET", path, "viewer", null, 403);
            call("GET", path, "alice", null, 403);
            call("GET", path, "finance", null, 403);
            call("GET", path, "manager", null, 200);
        }
        assertEquals("NOT_STARTED", data("GET", "/dating/recovery/overview", "manager", null).path("health").asText());
        long a = customer(), b = customer();
        long ea = paid(a, 3, "FRIEND_ADDED"), eb = paid(b, 3, "FRIEND_ADDED");
        assign(a, ALICE, null, "alice", 200); assign(b, ALICE, null, "alice", 200);
        long pair = recommend(a, b);
        willing(pair, a, b);
        var event = event("FRIEND_ADDED");
        long task;
        // Fault injection only in disposable MySQL: no business state is manufactured by this constraint.
        jdbc.execute("alter table dating_entitlement_ledger add constraint http_recovery_failure "
            + "check(entry_type<>'SERVICE_DEDUCT' or user_id<>" + a + ")");
        try
        {
            call("POST", "/dating/matches/" + pair + "/service-events", "alice", event, 200);
            task = jdbc.queryForObject("select task_id from dating_deduction_task where pair_id=? and user_id=?", Long.class, pair, a);
            for (int i = 0; i < 4; i++)
            {
                jdbc.update("update dating_deduction_task set next_attempt_at=current_timestamp where task_id=?", task);
                call("POST", "/dating/matches/" + pair + "/service-events", "alice", event, 200);
            }
        }
        finally { jdbc.execute("alter table dating_entitlement_ledger drop check http_recovery_failure"); }
        String issues = "/dating/deduction-exceptions?pairId=" + pair;
        String history = "/dating/deduction-tasks/" + task + "/history";
        String retry = "/dating/deduction-tasks/" + task + "/retry";
        call("GET", issues, null, null, 401);
        call("GET", issues, "viewer", null, 403);
        assertEquals(0, call("GET", issues, "bob", null, 200).path("total").asInt());
        JsonNode issue = call("GET", issues, "alice", null, 200).path("rows").get(0);
        assertEquals("ERROR", issue.path("status").asText());
        assertEquals("DATA_CONFLICT", issue.path("errorCode").asText());
        assertTrue(issue.hasNonNull("firstFailureAt"));
        assertFalse(issue.toString().contains("http_recovery_failure"));
        call("GET", history, "bob", null, 500);
        var retryBody = Map.of("reason", "  HTTP verified repair  ");
        call("POST", retry, "bob", retryBody, 500);
        call("POST", retry, "viewer", retryBody, 403);
        for (Object invalid : Arrays.asList(null, Map.of(), Map.of("reason", ""), Map.of("reason", " \t"),
            Map.of("reason", "x".repeat(501))))
            call("POST", retry, "alice", invalid, 500);
        assertEquals("ERROR", jdbc.queryForObject("select status from dating_deduction_task where task_id=?", String.class, task));
        assertEquals(5, number("select count(*) from dating_deduction_history where task_id=?", task));
        assertTrue(issue.path("retryAllowed").asBoolean());
        assertTrue(issue.hasNonNull("customerName"));
        assertEquals(1, call("GET", issues + "&kind=ERROR", "alice", null, 200).path("total").asInt());
        assertEquals(0, call("GET", issues + "&kind=BLOCKED", "alice", null, 200).path("total").asInt());
        call("GET", issues + "&kind=DONE", "alice", null, 500);
        assertEquals(5, call("GET", history + "?pageNum=1&pageSize=2", "alice", null, 200).path("total").asInt());
        assertEquals(2, call("GET", history + "?pageNum=1&pageSize=2", "alice", null, 200).path("rows").size());
        assertEquals(3, remaining(ea)); assertEquals(2, remaining(eb));
        call("POST", retry, "alice", retryBody, 200);
        assertEquals(2, remaining(ea)); assertEquals(2, remaining(eb));
        assertEquals(0, call("GET", issues, "alice", null, 200).path("total").asInt());
        JsonNode retried = call("GET", history, "alice", null, 200);
        assertEquals(6, retried.path("total").asInt());
        assertEquals("RETRY", retried.path("rows").get(0).path("action").asText());
        assertEquals(PREFIX + "alice", retried.path("rows").get(0).path("createBy").asText());
        assertEquals("HTTP verified repair", retried.path("rows").get(0).path("reason").asText());
        call("POST", retry, "alice", retryBody, 500);
        assertEquals(2, detail(pair).path("ledger").size());
        assign(a, BOB, ALICE, "alice", 200); assign(b, BOB, ALICE, "alice", 200);
        call("GET", history, "alice", null, 500);
        call("GET", history, "bob", null, 200);
        audit(retry, "alice", 0, 1);
        audit(retry, "alice", 1, 1);
    }

    @Test void followupHttpPermissionsPaginationOwnershipAndRecoveryHistory() throws Exception
    {
        long a = customer(), b = customer();
        long ea = paid(a, 3, "FRIEND_ADDED"), eb = paid(b, 3, "FRIEND_ADDED");
        assign(a, ALICE, null, "alice", 200); assign(b, ALICE, null, "alice", 200);
        long pair = recommend(a, b);
        willing(pair, a, b);
        jdbc.execute("alter table dating_entitlement_ledger add constraint http_followup_failure "
            + "check(entry_type<>'SERVICE_DEDUCT' or user_id<>" + a + ")");
        long task;
        try
        {
            call("POST", "/dating/matches/" + pair + "/service-events", "alice", event("FRIEND_ADDED"), 200);
            task = jdbc.queryForObject("select task_id from dating_deduction_task where pair_id=? and user_id=?", Long.class, pair, a);
        }
        finally { jdbc.execute("alter table dating_entitlement_ledger drop check http_followup_failure"); }
        String path = "/dating/deduction-tasks/" + task + "/follow-up";
        String history = path + "/history";
        String issues = "/dating/deduction-exceptions?pairId=" + pair;
        var claim = Map.of("action", "CLAIM", "version", 0, "note", "HTTP private followup note");
        for (String url : List.of(path, history))
        {
            call("GET", url, null, null, 401);
            call("GET", url, "viewer", null, 403);
            call("GET", url, "finance", null, 403);
            call("GET", url, "bob", null, 500);
        }
        call("POST", path, null, claim, 401);
        call("POST", path, "viewer", claim, 403);
        call("POST", path, "finance", claim, 403);
        call("POST", path, "bob", claim, 500);
        call("POST", path, "alice", Map.of("action", "CLAIM", "version", 0, "note", " "), 500);
        assertEquals(0, data("GET", path, "alice", null).path("followup").path("version").asInt());
        call("POST", path, "alice", claim, 200);
        call("POST", path, "alice", claim, 500);
        String future = TIME.format(Instant.now().plusSeconds(3600));
        for (int version = 1; version <= 3; version++)
            call("POST", path, "alice", Map.of("action", "UPDATE", "version", version,
                "status", "WAITING", "dueAt", future, "note", "HTTP private followup note"), 200);
        JsonNode paged = call("GET", history + "?pageNum=2&pageSize=2", "alice", null, 200);
        assertEquals(4, paged.path("total").asInt());
        assertEquals(2, paged.path("rows").size());
        assertEquals(2, paged.path("rows").get(0).path("version").asInt());
        assertEquals(1, call("GET", issues + "&mine=true&handling=WAITING", "alice", null, 200).path("total").asInt());
        assertEquals(0, call("GET", issues + "&mine=true", "manager", null, 200).path("total").asInt());
        assertEquals(0, call("GET", issues + "&overdueOnly=true", "alice", null, 200).path("total").asInt());
        jdbc.update("update dating_exception_followup set due_at=? where task_id=?", new Date(0), task);
        assertEquals(1, call("GET", issues + "&overdueOnly=true", "alice", null, 200).path("total").asInt());
        assertTrue(data("GET", path, "alice", null).path("overdue").asBoolean());
        assertEquals(3, remaining(ea)); assertEquals(2, remaining(eb));
        assertEquals("READY", jdbc.queryForObject("select status from dating_deduction_task where task_id=?", String.class, task));
        assign(a, BOB, ALICE, "alice", 200); assign(b, BOB, ALICE, "alice", 200);
        call("GET", history, "alice", null, 500);
        call("POST", path, "alice", Map.of("action", "UPDATE", "version", 4, "status", "WAITING", "note", "Denied"), 500);
        call("POST", path, "bob", Map.of("action", "RELEASE", "version", 4, "note", "Denied"), 500);
        call("POST", path, "manager", Map.of("action", "RELEASE", "version", 4, "note", "Reassigned"), 200);
        call("POST", path, "bob", Map.of("action", "CLAIM", "version", 5, "note", "Claim after transfer"), 200);
        jdbc.update("update dating_deduction_task set next_attempt_at=current_timestamp where task_id=?", task);
        context.getBean(com.ruoyi.system.service.DatingDeductionService.class).process(task);
        assertFalse(data("GET", path, "bob", null).path("active").asBoolean());
        call("POST", path, "bob", Map.of("action", "UPDATE", "version", 6, "status", "WAITING", "note", "Denied"), 500);
        assertEquals(6, call("GET", history, "bob", null, 200).path("total").asInt());
        assertEquals(2, detailAsManager(pair).path("ledger").size());
        audit(path, "alice", 0, 4);
        for (var row : jdbc.queryForList("select oper_param,json_result,error_msg from sys_oper_log where oper_url=?", path))
            assertFalse(row.toString().contains("HTTP private followup note"), "Followup text leaked into operation audit");
    }

    JsonNode detailAsManager(long pair) throws Exception
    { return data("GET", "/dating/matches/" + pair, "manager", null); }

    void audit(String path, String role, int status, int expected) throws Exception
    {
        String sql = "select count(*) from sys_oper_log where oper_url=? and oper_name=? and status=?"
            + " and oper_time is not null and method is not null and request_method in ('GET','POST','PUT') and cost_time>=0";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (number(sql, path, PREFIX + role, status) < expected && System.nanoTime() < deadline) Thread.sleep(30);
        assertEquals(expected, number(sql, path, PREFIX + role, status), "Missing/duplicate operation audit: " + path);
    }
    String upload(long user) throws Exception
    {
        String boundary = "HttpPhoto" + key();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"test.png\""
            + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(DatingPhotoStorageTest.image());
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/dating/archives/" + user + "/photos"))
            .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + tokens.get("alice"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode result = json.readTree(response.body());
        assertEquals(200, result.path("code").asInt());
        return result.path("url").asText();
    }
    void readPhoto(String ref, String role) throws Exception
    {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ref))
            .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + tokens.get(role)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(DatingPhotoStorageTest.image(), response.body());
    }
}
