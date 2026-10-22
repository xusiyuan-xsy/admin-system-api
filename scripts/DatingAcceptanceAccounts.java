import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Explicit local account provisioning; passwords arrive on stdin and are never logged. */
public class DatingAcceptanceAccounts extends DatingAcceptanceRoles
{
    static final String ACCOUNT_MARKER = "dating-followup-acceptance-accounts-v1";
    static String username(Profile p) { return p.key().replace("dating_accept_", "accept_"); }

    static Map<String, List<Map<String, String>>> accountSnapshot(Connection c, boolean lock) throws Exception
    {
        var state = snapshot(c, lock);
        state.put("sys_user", rows(c, "SELECT * FROM sys_user ORDER BY user_id" + (lock ? " FOR UPDATE" : "")));
        state.put("sys_user_post", rows(c, "SELECT * FROM sys_user_post ORDER BY user_id,post_id" + (lock ? " FOR UPDATE" : "")));
        return state;
    }

    static boolean validateAccounts(Connection c, Map<String, List<Map<String, String>>> state) throws Exception
    {
        require(validate(state, expectedMenus(state)), "REQUIRED_ROLES_NOT_CREATED");
        Set<String> ids = new HashSet<>();
        state.get("sys_user").forEach(u -> ids.add(u.get("user_id")));
        for (String table : List.of("sys_user_role", "sys_user_post"))
            require(state.get(table).stream().allMatch(r -> ids.contains(r.get("user_id"))), "ORPHAN_USER_REFERENCE");
        int count = 0;
        for (var p : PROFILES) {
            // Use database collation to detect case/accent-equivalent names as well.
            List<String> matches = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT user_id FROM sys_user WHERE user_name=?")) {
                s.setString(1, username(p));
                try (ResultSet r = s.executeQuery()) { while (r.next()) matches.add(r.getString(1)); }
            }
            if (matches.isEmpty()) continue;
            require(matches.size() == 1, "ACCOUNT_NAME_CONFLICT");
            var u = one(state.get("sys_user").stream().filter(row -> matches.contains(row.get("user_id"))).toList(),
                "ACCOUNT_SNAPSHOT_CONFLICT");
            require(!"1".equals(u.get("user_id")) && username(p).equals(u.get("user_name")) &&
                p.name().equals(u.get("nick_name")) && "0".equals(u.get("status")) && "0".equals(u.get("del_flag")) &&
                "00".equals(u.get("user_type")) && u.get("dept_id") == null &&
                CREATOR.equals(u.get("create_by")) && ACCOUNT_MARKER.equals(u.get("remark")),
                "EXISTING_ACCOUNT_CONFLICT_OR_DRIFT");
            String roleId = one(matchingRoles(state, p), "MISSING_ROLE").get("role_id");
            var bindings = state.get("sys_user_role").stream().filter(r -> u.get("user_id").equals(r.get("user_id"))).toList();
            require(bindings.size() == 1 && roleId.equals(bindings.get(0).get("role_id")), "ACCOUNT_ROLE_DRIFT");
            require(state.get("sys_user_post").stream().noneMatch(r -> u.get("user_id").equals(r.get("user_id"))),
                "ACCOUNT_POST_DRIFT");
            count++;
        }
        require(count == 0 || count == 4, "PARTIAL_ACCOUNT_SET");
        return count == 4;
    }

    static Map<String, Object> provision(Connection c, boolean apply, Path evidence, Map<String, String> passwords) throws Exception
    {
        require(c.getAutoCommit(), "CONNECTION_ALREADY_IN_TRANSACTION");
        boolean committed = false;
        c.setAutoCommit(false);
        try {
            var before = accountSnapshot(c, apply);
            boolean exists = validateAccounts(c, before);
            var inserted = new HashSet<String>();
            if (apply && !exists) {
                require(passwords != null && passwords.size() == 4, "FOUR_PASSWORDS_REQUIRED");
                for (var p : PROFILES) {
                    String password = passwords.get(username(p));
                    require(password != null && password.matches("(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[!+/_=-])[!-~]{20}"),
                        "PASSWORD_POLICY");
                }
                require(new HashSet<>(passwords.values()).size() == 4, "PASSWORDS_MUST_DIFFER");
                write(evidence, "accounts-before.json", before);
                for (var p : PROFILES) {
                    String password = passwords.get(username(p));
                    String hash = BCrypt.hashpw(password, BCrypt.gensalt());
                    require(BCrypt.checkpw(password, hash), "PASSWORD_HASH_VERIFICATION");
                    String id;
                    try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO sys_user(user_name,nick_name,user_type,sex,password,status,del_flag,create_by,create_time,remark) " +
                        "VALUES(?,?,'00','2',?,'0','0',?,CURRENT_TIMESTAMP,?)", Statement.RETURN_GENERATED_KEYS)) {
                        s.setString(1, username(p)); s.setString(2, p.name()); s.setString(3, hash);
                        s.setString(4, CREATOR); s.setString(5, ACCOUNT_MARKER);
                        require(s.executeUpdate() == 1, "ACCOUNT_INSERT_COUNT");
                        try (ResultSet r = s.getGeneratedKeys()) {
                            require(r.next(), "ACCOUNT_ID_MISSING"); id = r.getString(1);
                        }
                    }
                    inserted.add(id);
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)")) {
                        s.setString(1, id);
                        s.setString(2, one(matchingRoles(before, p), "MISSING_ROLE").get("role_id"));
                        require(s.executeUpdate() == 1, "BINDING_INSERT_COUNT");
                    }
                }
            }
            var after = accountSnapshot(c, false);
            if (apply) require(validateAccounts(c, after), "FINAL_ACCOUNT_VALIDATION");
            for (String table : before.keySet()) {
                var retained = after.get(table).stream().filter(r ->
                    !(Set.of("sys_user", "sys_user_role").contains(table) && inserted.contains(r.get("user_id")))).toList();
                require(before.get(table).equals(retained), "ORIGINAL_ROWS_CHANGED");
            }
            if (!inserted.isEmpty()) write(evidence, "accounts-after.json", after);
            List<Map<String, String>> accounts = new ArrayList<>();
            for (var p : PROFILES) {
                var found = after.get("sys_user").stream().filter(u -> username(p).equals(u.get("user_name"))).toList();
                accounts.add(Map.of("username", username(p), "userId", found.isEmpty() ? "NOT_CREATED" : found.get(0).get("user_id"),
                    "roleId", one(matchingRoles(after, p), "MISSING_ROLE").get("role_id")));
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("action", exists ? "ALREADY_PRESENT" : apply ? "CREATED" : "WOULD_CREATE");
            result.put("accounts", accounts);
            result.put("originalAccountsChanged", false);
            result.put("actualLoginAcceptance", "NOT_PERFORMED");
            if (apply) { c.commit(); committed = true; } else c.rollback();
            write(evidence, "accounts-result.json", result);
            return result;
        } catch (Exception e) {
            if (!committed) {
                try { c.rollback(); } catch (SQLException rollback) { c.close(); throw new Rejected("ROLLBACK_FAILED_REVIEW_REQUIRED"); }
            }
            if (committed) throw new Rejected("COMMITTED_BUT_REPORT_FAILED_REVIEW_BEFORE_RETRY");
            throw e;
        } finally {
            if (!c.isClosed()) c.setAutoCommit(true);
        }
    }

    public static void main(String[] args)
    {
        try {
            require(args.length == 3 && Set.of("plan", "apply").contains(args[0]), "INVALID_ARGUMENTS");
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object value;
            try (InputStream input = Files.newInputStream(Path.of(args[1]))) {
                value = new Yaml(new SafeConstructor(options)).load(input);
            }
            for (String key : List.of("spring", "datasource", "druid", "master")) value = ((Map<?, ?>) value).get(key);
            Map<?, ?> master = (Map<?, ?>) value;
            String url = Objects.toString(master.get("url"), "");
            String user = Objects.toString(master.get("username"), "");
            String secret = Objects.toString(master.get("password"), "");
            require(url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$") &&
                "root".equals(user) && !secret.isBlank() && !secret.contains("${"), "LOCAL_DATASOURCE_REQUIRED");
            Map<String, String> passwords = null;
            if (args[0].equals("apply")) {
                byte[] input = System.in.readNBytes(8193);
                require(input.length <= 8192, "INPUT_TOO_LARGE");
                passwords = new LinkedHashMap<>();
                var json = JSON.parseObject(new String(input, StandardCharsets.UTF_8));
                for (String key : json.keySet()) passwords.put(key, json.getString(key));
                Arrays.fill(input, (byte) 0);
            }
            try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ry-vue?useSSL=false&allowPublicKeyRetrieval=true" +
                "&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=20000", user, secret)) {
                require("3306".equals(rows(c, "SELECT @@port AS port").get(0).get("port")) &&
                    "ry-vue".equals(c.getCatalog()), "LOCAL_DATABASE_IDENTITY");
                System.out.println(JSON.toJSONString(provision(c, args[0].equals("apply"), Path.of(args[2]), passwords)));
            } finally { if (passwords != null) passwords.clear(); }
        } catch (Exception e) {
            System.err.println("Account setup stopped: " +
                (e instanceof Rejected ? e.getMessage() : "OPERATION_FAILED_NO_SECRETS_LOGGED"));
            System.exit(1);
        }
    }
}
