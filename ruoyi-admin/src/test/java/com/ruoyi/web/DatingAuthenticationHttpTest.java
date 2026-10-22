package com.ruoyi.web;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DATING_AUTH_HTTP_TEST", matches = "true")
@SpringBootTest(classes = DatingAuthTestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.config.name=dating-auth-isolated", "spring.main.banner-mode=off",
        "server.address=127.0.0.1", "token.header=Authorization", "token.expireTime=30",
        "user.password.maxRetryCount=5", "user.password.lockTime=10"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatingAuthenticationHttpTest
{
    static final java.nio.file.Path photoDirectory = java.nio.file.Path.of(
        System.getProperty("java.io.tmpdir"), "dating-http-photos-" + UUID.randomUUID());

    @DynamicPropertySource static void secret(DynamicPropertyRegistry registry)
    {
        String secret = Base64.getEncoder().encodeToString(
            (UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        registry.add("token.secret", () -> secret);
        registry.add("dating.attachments.root", () -> photoDirectory.toString());
    }

    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired RedisCache cache;
    @Autowired DatingAuthTestConfig.IsolatedRedisTemplate redis;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired DatingAuthTestConfig.AuditScheduler scheduledExecutorService;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    JdbcTemplate jdbc;
    final String archive = "/system/payingUsers";

    @BeforeEach void resetFixtures()
    {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("update sys_paying_user set matchmaker_id=null where id=1");
        jdbc.update("update sys_paying_user set name='A' where id=1");
        jdbc.update("update sys_paying_user set photos=null,del_flag='0' where id=1");
    }
    @AfterAll void clean() throws Exception
    {
        scheduledExecutorService.shutdown();
        assertTrue(scheduledExecutorService.awaitTermination(10, TimeUnit.SECONDS), "Login audit tasks did not finish");
        redis.close();
        for (String key : Set.copyOf(redis.keys)) assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        assertTrue(scheduledExecutorService.failures.isEmpty(), () -> scheduledExecutorService.failures.toString());
        assertTrue(jdbc.queryForObject("select count(*) from sys_logininfor where status='0'", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("select count(*) from sys_logininfor where status='1'", Integer.class) > 0);
    }
    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception
    {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode call(String method, String path, String token, Object body, int expected) throws Exception
    {
        var response = request(method, path, token, body);
        assertEquals(200, response.statusCode(), method + " " + path);
        JsonNode node = json.readTree(response.body());
        // Do not include response bodies: successful login responses contain credentials.
        assertEquals(expected, node.path("code").asInt(), method + " " + path);
        return node;
    }
    Map<String, String> credentials(String name, String password) throws Exception
    {
        var captcha = call("GET", "/captchaImage", null, null, 200);
        assertTrue(captcha.path("captchaEnabled").asBoolean());
        assertTrue(Base64.getDecoder().decode(captcha.path("img").asText()).length > 100);
        String uuid = captcha.path("uuid").asText();
        // Only this test's prefixed captcha is read, never a live application's captcha/session.
        String answer = cache.getCacheObject(CacheConstants.CAPTCHA_CODE_KEY + uuid);
        assertNotNull(answer);
        return new HashMap<>(Map.of("username", name, "password", password, "uuid", uuid, "code", answer));
    }
    String login(String name) throws Exception
    {
        var result = call("POST", "/login", null, credentials(name, DatingAuthTestConfig.PASSWORD), 200);
        assertTrue(result.hasNonNull("token"));
        return result.get("token").asText();
    }
    Set<String> strings(JsonNode values)
    {
        Set<String> result = new HashSet<>();
        values.forEach(v -> result.add(v.asText()));
        return result;
    }
    List<Long> ids(JsonNode result)
    {
        List<Long> ids = new ArrayList<>();
        result.path("rows").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }
    void assertArchiveDenied(String method, String path, String token, Object body) throws Exception
    {
        var denied = call(method, path, token, body, 500);
        assertEquals("\u5ba2\u6237\u4e0d\u5b58\u5728\u6216\u65e0\u6743\u8bbf\u95ee", denied.path("msg").asText());
        assertFalse(denied.has("data"));
    }

    @Test void environmentHasNoProductionDatabaseOrApplication() throws Exception
    {
        assertEquals(1, context.getBeansOfType(DataSource.class).size());
        assertTrue(context.getBeansOfType(com.ruoyi.RuoYiApplication.class).isEmpty());
        try (var connection = dataSource.getConnection())
        {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:dating_auth_"));
        }
        assertTrue(redis.prefix.startsWith("dating-auth-test:"));
        assertThrows(IllegalArgumentException.class, () -> redis.keys("*"));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_user where user_id=1", Integer.class));
    }

    @Test void anonymousAndInvalidTokensAreRejected() throws Exception
    {
        for (String path : List.of("/getInfo", "/getRouters", archive + "/list", archive + "/1"))
        {
            call("GET", path, null, null, 401);
            call("GET", path, "invalid-test-token", null, 401);
        }
    }
    @Test void matchmakerConfigurationHttpLifecycleAndPermissions() throws Exception
    {
        String endpoint = "/dating/matchmaker-configs";
        String editor = login("alice");
        call("GET", endpoint + "/active", null, null, 401);
        assertTrue(call("GET", endpoint + "/active", editor, null, 200).path("data").path("config").isNull());
        var body = new HashMap<String, Object>(Map.of("name", "HTTP fixture",
            "watermarkText", "Private use", "minAge", 1988, "maxAge", 2004,
            "minHeight", 155, "maxHeight", 185, "requiredFields", List.of("remark")));
        call("POST", endpoint, editor, body, 403);
        String[] permissions = {"list", "add", "edit", "remove", "default"};
        try
        {
            for (int i = 0; i < permissions.length; i++)
            {
                jdbc.update("insert into sys_menu(menu_id,menu_name,path,component,menu_type,perms) values(?,?,?,?,?,?)",
                    9100 + i, "Config fixture", "#", null, "F", "dating:matchmakerConfig:" + permissions[i]);
                jdbc.update("insert into sys_role_menu(role_id,menu_id) values(40,?)", 9100 + i);
            }
            String manager = login("manager");
            JsonNode first = call("POST", endpoint, manager, body, 200).path("data");
            long firstId = first.path("configId").asLong();
            assertTrue(firstId > 0);
            assertTrue(first.path("defaultConfig").asBoolean());
            assertEquals(1988, first.path("minAge").asInt());
            assertEquals(2004, first.path("maxAge").asInt());
            assertEquals("remark", first.path("requiredFields").get(0).asText());
            assertFalse(first.has("requiredFieldsJson"));
            body.put("name", "HTTP secondary");
            JsonNode second = call("POST", endpoint, manager, body, 200).path("data");
            long secondId = second.path("configId").asLong();
            assertFalse(second.path("defaultConfig").asBoolean());
            JsonNode list = call("GET", endpoint + "?pageNum=1&pageSize=1", manager, null, 200);
            assertEquals(2, list.path("total").asInt());
            assertEquals(1, list.path("rows").size());
            assertEquals(firstId, call("GET", endpoint + "/active", editor, null, 200)
                .path("data").path("config").path("configId").asLong());
            call("PUT", endpoint + "/" + secondId + "/default", manager,
                Map.of("expectedDefaultId", firstId, "version", second.path("version").asLong()), 200);
            call("PUT", endpoint + "/" + firstId + "/default", manager,
                Map.of("expectedDefaultId", firstId, "version", first.path("version").asLong()), 500);
            body.put("version", first.path("version").asLong());
            body.put("watermarkText", "Updated text");
            JsonNode edited = call("PUT", endpoint + "/" + firstId, manager, body, 200).path("data");
            assertEquals("Updated text", edited.path("watermarkText").asText());
            call("DELETE", endpoint + "/" + secondId + "?version=1", manager, null, 500);
            call("DELETE", endpoint + "/" + firstId + "?version=" + edited.path("version").asLong(), manager, null, 200);
            call("DELETE", endpoint + "/" + secondId + "?version=1", manager, null, 200);
            assertTrue(call("GET", endpoint + "/active", editor, null, 200).path("data").path("config").isNull());
        }
        finally
        {
            jdbc.update("update dating_matchmaker_config_state set default_config_id=null where state_id=1");
            jdbc.update("delete from dating_matchmaker_config");
            jdbc.update("delete from sys_role_menu where menu_id between 9100 and 9104");
            jdbc.update("delete from sys_menu where menu_id between 9100 and 9104");
        }
    }
    @Test void captchaIsRequiredOneUseAndWrongPasswordsCannotLogin() throws Exception
    {
        var wrong = credentials("alice", DatingAuthTestConfig.PASSWORD);
        wrong.put("code", "wrong");
        call("POST", "/login", null, wrong, 500);
        assertNull(cache.getCacheObject(CacheConstants.CAPTCHA_CODE_KEY + wrong.get("uuid")));
        var valid = credentials("alice", DatingAuthTestConfig.PASSWORD);
        call("POST", "/login", null, valid, 200);
        call("POST", "/login", null, valid, 500);
        call("POST", "/login", null, credentials("alice", "incorrect-password"), 500);
        assertEquals(1, (Integer) cache.getCacheObject(CacheConstants.PWD_ERR_CNT_KEY + "alice"));
        login("alice");
        assertFalse(cache.hasKey(CacheConstants.PWD_ERR_CNT_KEY + "alice"));
        call("POST", "/login", null, credentials("disabled", DatingAuthTestConfig.PASSWORD), 500);
    }
    @Test void realRoleQueriesDeterminePermissionsAndRouters() throws Exception
    {
        String alice = login("alice");
        var info = call("GET", "/getInfo", alice, null, 200);
        assertEquals(Set.of("matchmaker"), strings(info.get("roles")));
        assertEquals(Set.of("system:payingUsers:list", "system:payingUsers:query",
            "system:payingUsers:edit", "system:payingUsers:export"), strings(info.get("permissions")));
        assertFalse(info.path("user").has("password"));
        assertEquals(1, call("GET", "/getRouters", alice, null, 200).path("data").size());
        for (String name : List.of("viewer", "disabled_role"))
        {
            String token = login(name);
            assertTrue(strings(call("GET", "/getInfo", token, null, 200).get("permissions")).isEmpty());
            assertEquals(0, call("GET", "/getRouters", token, null, 200).path("data").size());
            call("GET", archive + "/list", token, null, 403);
            call("GET", archive + "/1", token, null, 403);
        }
        assertNotNull(jdbc.queryForObject("select login_date from sys_user where user_id=10", Date.class));
    }
    @Test void archiveScopeCannotBeForgedAndFinanceIsNotServiceManager() throws Exception
    {
        String alice = login("alice");
        var list = call("GET", archive + "/list?params%5BarchiveOwner%5D=bob", alice, null, 200);
        assertEquals(List.of(4L, 3L, 1L), ids(list));
        assertFalse(list.path("rows").get(0).has("password"));
        call("GET", archive + "/1", alice, null, 200);
        assertArchiveDenied("GET", archive + "/2", alice, null);
        assertEquals(List.of(2L), ids(call("GET", archive + "/list", login("bob"), null, 200)));
        String finance = login("finance");
        assertTrue(strings(call("GET", "/getInfo", finance, null, 200).get("permissions")).contains("dating:billing:all"));
        assertTrue(ids(call("GET", archive + "/list", finance, null, 200)).isEmpty());
        assertArchiveDenied("GET", archive + "/1", finance, null);
        String manager = login("manager");
        assertEquals(4, ids(call("GET", archive + "/list", manager, null, 200)).size());
        call("GET", archive + "/2", manager, null, 200);
    }
    @Test void transferRevokesOldOwnersAccessWithoutRelogin() throws Exception
    {
        String alice = login("alice"), bob = login("bob");
        call("GET", archive + "/1", alice, null, 200);
        // Transfer fixture only; this does not claim to exercise the assignment command.
        jdbc.update("update sys_paying_user set matchmaker_id=20 where id=1");
        assertArchiveDenied("GET", archive + "/1", alice, null);
        assertArchiveDenied("PUT", archive, alice, Map.of("id", 1, "name", "forged"));
        assertEquals(List.of(4L, 3L), ids(call("GET", archive + "/list", alice, null, 200)));
        call("GET", archive + "/1", bob, null, 200);
        call("PUT", archive, bob, Map.of("id", 1, "name", "Transferred"), 200);
        assertEquals("Transferred", jdbc.queryForObject("select name from sys_paying_user where id=1", String.class));
    }
    @Test void functionPermissionsBlockWritesEvenWhenUserCanRead() throws Exception
    {
        String alice = login("alice"), finance = login("finance");
        call("PUT", archive, finance, Map.of("id", 1, "name", "forged"), 403);
        call("DELETE", archive + "/1", alice, null, 403);
        call("POST", archive, alice, Map.of("name", "forged"), 403);
        call("POST", archive + "/importTemplate", alice, null, 403);
        call("POST", archive + "/export", finance, null, 403);
        call("POST", archive + "/export/estimate", finance, null, 403);
        assertEquals("A", jdbc.queryForObject("select name from sys_paying_user where id=1", String.class));
        assertEquals(4, jdbc.queryForObject("select count(*) from sys_paying_user where del_flag='0'", Integer.class));
    }
    @Test void logoutInvalidatesTheIssuedToken() throws Exception
    {
        String token = login("alice");
        call("GET", "/getInfo", token, null, 200);
        call("POST", "/logout", token, null, 200);
        call("GET", "/getInfo", token, null, 401);
        call("GET", archive + "/list", token, null, 401);
    }
    @Test void exportUsesCurrentOwnershipEvenWithForgedScope() throws Exception
    {
        String token = login("alice");
        jdbc.update("update sys_paying_user set matchmaker_id=20 where id=1");
        var estimate = call("POST", archive + "/export/estimate?params%5BarchiveOwner%5D=bob", token, null, 200);
        assertEquals(2, estimate.path("data").path("userCount").asInt());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + archive + "/export?params%5BarchiveOwner%5D=bob"))
            .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(
            new java.io.ByteArrayInputStream(response.body())))
        {
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getLastRowNum());
            Set<String> values = new HashSet<>();
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter();
            sheet.forEach(row -> row.forEach(cell -> values.add(formatter.formatCellValue(cell))));
            assertTrue(values.containsAll(Set.of("C", "D", "13800000003", "13800000004")));
            assertFalse(values.contains("13800000001"));
            assertFalse(values.contains("13800000002"));
            assertFalse(values.contains("HASH"));
        }
    }

    @Test void uploadedPhotosAreEmbeddedInAuthenticatedExcelDownload() throws Exception
    {
        String alice = login("alice");
        String first = uploadPhoto(alice, 1L, DatingPhotoStorageTest.image(), 200).path("url").asText();
        String second = uploadPhoto(alice, 1L, DatingPhotoStorageTest.image(), 200).path("url").asText();
        call("PUT", archive, alice, Map.of("id", 1, "photos", List.of(first, second)), 200);
        var estimate = call("POST", archive + "/export/estimate?name=A", alice, null, 200).path("data");
        assertEquals(1, estimate.path("userCount").asInt());
        assertEquals(2, estimate.path("photoCount").asInt());
        assertTrue(estimate.path("imageBytes").asLong() > 0);
        assertEquals("xlsx", estimate.path("fileType").asText());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + archive + "/export?name=A"))
            .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + alice)
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.body())))
        {
            assertEquals(1, workbook.getSheet("用户数据").getLastRowNum());
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals(2, workbook.getAllPictures().size());
            for (var picture : workbook.getAllPictures())
                assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(picture.getData())));
            var sheet = workbook.getSheet("用户数据");
            assertEquals("1", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("A", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals(2, sheet.getDrawingPatriarch().getShapes().size());
            for (int i = 0; i < 2; i++)
            {
                var picture = (org.apache.poi.xssf.usermodel.XSSFPicture) sheet.getDrawingPatriarch().getShapes().get(i);
                assertEquals(1, picture.getClientAnchor().getRow1());
                assertEquals(3 + i, picture.getClientAnchor().getCol1());
            }
            assertTrue(workbook.getExternalLinksTable().isEmpty());
        }
        call("POST", archive + "/export", null, null, 401);
        call("POST", archive + "/export", login("viewer"), null, 403);
        call("POST", archive + "/export/estimate", null, null, 401);
        call("POST", archive + "/export/estimate", login("viewer"), null, 403);
    }

    JsonNode uploadPhoto(String token, long id, byte[] content, int expected) throws Exception
    {
        String boundary = "TestPhoto" + UUID.randomUUID().toString().replace("-", "");
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\""
            + "\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/dating/archives/" + id + "/photos"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        var response = http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var result = json.readTree(response.body());
        assertEquals(expected, result.path("code").asInt());
        return result;
    }

    @Test void privatePhotoUploadSaveReadAndOwnershipTransferUseRealHttp() throws Exception
    {
        String alice = login("alice"), bob = login("bob");
        String ref = uploadPhoto(alice, 1L, DatingPhotoStorageTest.image(), 200).path("url").asText();
        assertTrue(com.ruoyi.common.utils.DatingPhotoReferences.belongsTo(ref, 1L));
        call("PUT", archive, alice, Map.of("id", 1, "photos", List.of(ref)), 200);
        assertEquals(ref, call("GET", archive + "/1", alice, null, 200).path("data").path("photos").get(0).asText());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ref))
            .header("Authorization", "Bearer " + alice).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertArrayEquals(DatingPhotoStorageTest.image(), response.body());
        call("GET", ref, null, null, 401);
        call("GET", ref, "invalid-token", null, 401);
        call("GET", ref, login("viewer"), null, 403);
        assertArchiveDenied("GET", ref, bob, null);
        assertArchiveDenied("GET", ref, login("finance"), null);
        jdbc.update("update sys_paying_user set matchmaker_id=20 where id=1");
        assertArchiveDenied("GET", ref, alice, null);
        assertTrue(request("GET", ref, bob, null).headers().firstValue("Content-Type").orElse("").startsWith("image/png"));
        jdbc.update("update sys_paying_user set del_flag='2' where id=1");
        assertArchiveDenied("GET", ref, bob, null);
    }

    @Test void photoUploadRejectsMissingPermissionForeignOwnerAndFakeImages() throws Exception
    {
        byte[] image = DatingPhotoStorageTest.image();
        uploadPhoto(null, 1L, image, 401);
        uploadPhoto(login("viewer"), 1L, image, 403);
        uploadPhoto(login("finance"), 1L, image, 403);
        String alice = login("alice");
        uploadPhoto(alice, 2L, image, 500);
        uploadPhoto(alice, 1L, "<svg><script>bad</script></svg>".getBytes(), 500);
        uploadPhoto(alice, 1L, new byte[0], 500);
        call("PUT", archive, alice, Map.of("id", 1, "photos", List.of("https://public.example.test/photo.png")), 500);
        String foreign = uploadPhoto(login("bob"), 2L, image, 200).path("url").asText();
        call("PUT", archive, alice, Map.of("id", 1, "photos", List.of(foreign)), 500);
        call("PUT", archive, alice, Map.of("id", 1, "photos",
            List.of("/dating/archives/1/photos/" + "f".repeat(32) + ".png")), 500);
        assertNull(jdbc.queryForObject("select photos from sys_paying_user where id=1", String.class));
    }
}
