import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Add-only, unassigned roles for follow-up acceptance. Never creates users or business data. */
public class DatingAcceptanceRoles
{
    static final String MARKER = "dating-followup-acceptance-v1";
    static final String CREATOR = "local_acceptance_setup";
    static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("sys_role", "role_id");
        TABLES.put("sys_role_menu", "role_id,menu_id");
        TABLES.put("sys_user_role", "user_id,role_id");
        TABLES.put("sys_role_dept", "role_id,dept_id");
        TABLES.put("sys_menu", "menu_id");
    }
    record Profile(String key, String name, int sort, List<String> permissions) {}
    static final List<Profile> PROFILES = List.of(
        new Profile("dating_accept_owner", "\u9a8c\u6536-\u7ea2\u5a18\u8ddf\u8fdb", 9001,
            List.of("dating:match:list", "dating:match:query", "dating:exception:handle")),
        new Profile("dating_accept_supervisor", "\u9a8c\u6536-\u4e3b\u7ba1\u8ddf\u8fdb", 9002,
            List.of("dating:match:list", "dating:match:query", "dating:exception:handle", "dating:service:all")),
        new Profile("dating_accept_readonly", "\u9a8c\u6536-\u53ea\u8bfb\u914d\u5bf9", 9003,
            List.of("dating:match:list", "dating:match:query")),
        new Profile("dating_accept_denied", "\u9a8c\u6536-\u65e0\u5a5a\u604b\u6743\u9650", 9004, List.of()));

    static final class Rejected extends RuntimeException {
        Rejected(String code) { super(code); }
    }
    static void require(boolean condition, String code) { if (!condition) throw new Rejected(code); }

    static List<Map<String, String>> rows(Connection c, String sql) throws SQLException
    {
        List<Map<String, String>> result = new ArrayList<>();
        try (Statement s = c.createStatement()) {
            s.setQueryTimeout(15);
            try (ResultSet r = s.executeQuery(sql)) {
                while (r.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int n = 1; n <= r.getMetaData().getColumnCount(); n++)
                        row.put(r.getMetaData().getColumnLabel(n), r.getString(n));
                    result.add(row);
                }
            }
        }
        return result;
    }
    static Map<String, List<Map<String, String>>> snapshot(Connection c, boolean lock) throws SQLException
    {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (var table : TABLES.entrySet())
            result.put(table.getKey(), rows(c, "SELECT * FROM " + table.getKey() + " ORDER BY " +
                table.getValue() + (lock ? " FOR UPDATE" : "")));
        return result;
    }
    static Map<String, String> one(List<Map<String, String>> candidates, String code)
    {
        require(candidates.size() == 1, code);
        return candidates.get(0);
    }
    static boolean blank(String value) { return value == null || value.isBlank(); }
    static void activeMenu(Map<String, String> menu, String type, String parent)
    {
        require("0".equals(menu.get("status")) && "0".equals(menu.get("visible")) &&
            type.equals(menu.get("menu_type")) && parent.equals(menu.get("parent_id")), "MENU_STATE_OR_PARENT");
    }
    static Map<String, Set<String>> expectedMenus(Map<String, List<Map<String, String>>> state)
    {
        var menus = state.get("sys_menu");
        var root = one(menus.stream().filter(m -> "dating".equals(m.get("path")) &&
            "0".equals(m.get("parent_id"))).toList(), "DATING_ROOT_NOT_UNIQUE");
        activeMenu(root, "M", "0");
        require(blank(root.get("perms")), "ROOT_HAS_EXTRA_PERMISSION");
        var page = one(menus.stream().filter(m -> "dating/matches/index".equals(m.get("component"))).toList(),
            "MATCH_PAGE_NOT_UNIQUE");
        activeMenu(page, "C", root.get("menu_id"));
        require("matches".equals(page.get("path")) && "dating:match:list".equals(page.get("perms")),
            "MATCH_PAGE_CHANGED");
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (var profile : PROFILES) {
            Set<String> ids = new TreeSet<>();
            if (!profile.permissions.isEmpty()) ids.add(root.get("menu_id"));
            for (String permission : profile.permissions) {
                var menu = one(menus.stream().filter(m -> permission.equals(m.get("perms"))).toList(),
                    "PERMISSION_MISSING_OR_DUPLICATED");
                if (permission.equals("dating:match:list"))
                    require(menu.equals(page), "MATCH_PAGE_CHANGED");
                else activeMenu(menu, "F", page.get("menu_id"));
                ids.add(menu.get("menu_id"));
            }
            result.put(profile.key, ids);
        }
        return result;
    }
    static List<Map<String, String>> matchingRoles(Map<String, List<Map<String, String>>> state, Profile p)
    {
        return state.get("sys_role").stream().filter(r ->
            p.key.equalsIgnoreCase(r.get("role_key")) || p.name.equals(r.get("role_name"))).toList();
    }
    static Set<String> grants(Map<String, List<Map<String, String>>> state, String roleId)
    {
        Set<String> ids = new TreeSet<>();
        state.get("sys_role_menu").stream().filter(r -> roleId.equals(r.get("role_id")))
            .forEach(r -> ids.add(r.get("menu_id")));
        return ids;
    }
    static boolean validate(Map<String, List<Map<String, String>>> state, Map<String, Set<String>> expected)
    {
        Set<String> roleIds = new HashSet<>();
        state.get("sys_role").forEach(r -> roleIds.add(r.get("role_id")));
        for (String table : List.of("sys_role_menu", "sys_user_role", "sys_role_dept"))
            require(state.get(table).stream().allMatch(r -> roleIds.contains(r.get("role_id"))),
                "ORPHAN_ROLE_REFERENCE");
        int existing = 0;
        for (var profile : PROFILES) {
            var candidates = matchingRoles(state, profile);
            if (candidates.isEmpty()) continue;
            var role = one(candidates, "ROLE_NAME_OR_KEY_CONFLICT");
            require(profile.key.equals(role.get("role_key")) && profile.name.equals(role.get("role_name")) &&
                Integer.toString(profile.sort).equals(role.get("role_sort")) &&
                "5".equals(role.get("data_scope")) && "0".equals(role.get("status")) &&
                "0".equals(role.get("del_flag")) && "1".equals(role.get("menu_check_strictly")) &&
                "1".equals(role.get("dept_check_strictly")) && CREATOR.equals(role.get("create_by")) &&
                MARKER.equals(role.get("remark")), "EXISTING_ROLE_DRIFT_OR_CONFLICT");
            require(grants(state, role.get("role_id")).equals(expected.get(profile.key)), "EXISTING_GRANTS_DRIFT");
            require(state.get("sys_role_dept").stream().noneMatch(r ->
                role.get("role_id").equals(r.get("role_id"))), "EXISTING_DEPARTMENT_GRANTS");
            existing++;
        }
        require(existing == 0 || existing == PROFILES.size(), "PARTIAL_ROLE_SET");
        return existing == PROFILES.size();
    }
    static String insertRole(Connection c, Profile p) throws SQLException
    {
        try (PreparedStatement s = c.prepareStatement(
            "INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,menu_check_strictly," +
            "dept_check_strictly,status,del_flag,create_by,create_time,remark) " +
            "VALUES(?,?,?,'5',1,1,'0','0',?,CURRENT_TIMESTAMP,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, p.name); s.setString(2, p.key); s.setInt(3, p.sort);
            s.setString(4, CREATOR); s.setString(5, MARKER);
            require(s.executeUpdate() == 1, "ROLE_INSERT_COUNT");
            try (ResultSet keys = s.getGeneratedKeys()) {
                require(keys.next(), "ROLE_ID_MISSING");
                return keys.getString(1);
            }
        }
    }
    static void write(Path directory, String name, Object value) throws Exception
    {
        String text = JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteMapNullValue);
        Path file = directory.resolve(name);
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        require(text.equals(Files.readString(file, StandardCharsets.UTF_8)), "BACKUP_ROUND_TRIP_FAILED");
    }
    static Map<String, Object> execute(Connection c, boolean apply, Path evidence) throws Exception
    {
        require(c.getAutoCommit(), "CONNECTION_ALREADY_IN_TRANSACTION");
        boolean committed = false;
        c.setAutoCommit(false);
        try {
            // Lock only authorization tables for a short configuration transaction; no customer tables are touched.
            var before = snapshot(c, apply);
            var expected = expectedMenus(before);
            boolean existing = validate(before, expected);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", apply ? "APPLY" : "PLAN");
            result.put("action", existing ? "ALREADY_PRESENT" : apply ? "CREATED" : "WOULD_CREATE");
            result.put("database", c.getCatalog());
            result.put("expectedRoleCount", 4);
            result.put("expectedGrantCount", expected.values().stream().mapToInt(Set::size).sum());
            result.put("accountBindingsChanged", false);
            result.put("businessDataChanged", false);
            result.put("actualAccountAcceptance", "NOT_PERFORMED");
            if (apply) write(evidence, "authorization-before.json", before);
            Set<String> inserted = new HashSet<>();
            if (apply && !existing) {
                for (var profile : PROFILES) {
                    String id = insertRole(c, profile);
                    inserted.add(id);
                    for (String menu : expected.get(profile.key)) {
                        try (PreparedStatement s = c.prepareStatement("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)")) {
                            s.setString(1, id); s.setString(2, menu);
                            require(s.executeUpdate() == 1, "GRANT_INSERT_COUNT");
                        }
                    }
                }
            }
            var after = snapshot(c, false);
            if (apply) {
                require(validate(after, expected), "FINAL_ROLE_VALIDATION_FAILED");
                for (String table : TABLES.keySet()) {
                    var retained = after.get(table).stream().filter(r ->
                        !(Set.of("sys_role", "sys_role_menu").contains(table) &&
                            inserted.contains(r.get("role_id")))).toList();
                    require(before.get(table).equals(retained), "ORIGINAL_AUTHORIZATION_ROWS_CHANGED");
                }
                write(evidence, "authorization-after.json", after);
            } else require(before.equals(after), "PLAN_CHANGED_DATA");
            List<Map<String, Object>> profiles = new ArrayList<>();
            for (var p : PROFILES) {
                var matches = matchingRoles(after, p);
                profiles.add(Map.of("key", p.key, "name", p.name, "permissions", p.permissions,
                    "roleId", matches.isEmpty() ? "NOT_CREATED" : matches.get(0).get("role_id")));
            }
            result.put("profiles", profiles);
            if (apply) c.commit(); else c.rollback();
            committed = apply;
            write(evidence, "result.json", result);
            return result;
        } catch (Exception e) {
            c.rollback();
            if (committed) throw new Rejected("COMMITTED_BUT_REPORT_FAILED_REVIEW_BEFORE_RETRY");
            throw e;
        } finally { c.setAutoCommit(true); }
    }
    public static void main(String[] args)
    {
        try {
            require(args.length == 3 && Set.of("plan", "apply").contains(args[0]), "INVALID_ARGUMENTS");
            Map<?, ?> config;
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (InputStream input = Files.newInputStream(Path.of(args[1]))) {
                config = new Yaml(new SafeConstructor(options)).load(input);
            }
            Object value = config;
            for (String key : List.of("spring", "datasource", "druid", "master"))
                value = ((Map<?, ?>) value).get(key);
            Map<?, ?> master = (Map<?, ?>) value;
            String url = Objects.toString(master.get("url"), "");
            String user = Objects.toString(master.get("username"), "");
            String password = Objects.toString(master.get("password"), "");
            require(url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$") &&
                "root".equals(user) && !password.isBlank() && !password.contains("${"), "LOCAL_DATASOURCE_REQUIRED");
            // Construct a bounded local connection; do not inherit arbitrary JDBC options from configuration.
            try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ry-vue?useSSL=false&allowPublicKeyRetrieval=true" +
                "&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=20000", user, password)) {
                require("3306".equals(rows(c, "SELECT @@port AS port").get(0).get("port")) &&
                    "ry-vue".equals(c.getCatalog()), "LOCAL_DATABASE_IDENTITY");
                System.out.println(JSON.toJSONString(execute(c, args[0].equals("apply"), Path.of(args[2]))));
            }
        } catch (Exception e) {
            System.err.println("Acceptance role setup stopped: " +
                (e instanceof Rejected ? e.getMessage() : "OPERATION_FAILED_NO_SECRETS_LOGGED"));
            System.exit(1);
        }
    }
}
