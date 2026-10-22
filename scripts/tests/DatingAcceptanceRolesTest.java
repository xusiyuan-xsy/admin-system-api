import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Runs only against the explicitly checked disposable MySQL instance created by Test-DatingMySql.ps1. */
public class DatingAcceptanceRolesTest
{
    static Connection db;
    static Path evidence;
    static int passed;
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void sql(String sql) throws Exception {
        try (Statement s = db.createStatement()) { s.execute(sql); }
    }
    static String scalar(String sql) throws Exception {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(sql)) {
            check(r.next(), "Expected scalar row");
            return r.getString(1);
        }
    }
    static Map<String, Object> run(boolean apply) throws Exception {
        return DatingAcceptanceRoles.execute(db, apply, Files.createTempDirectory(evidence, "case-"));
    }
    static void pass(String name) {
        passed++;
        System.out.println("PASS roles: " + name);
    }
    static void rejected(String name, boolean apply) throws Exception {
        var before = DatingAcceptanceRoles.snapshot(db, false);
        boolean failed = false;
        try { run(apply); } catch (Exception expected) { failed = true; }
        check(failed, name + " must reject");
        check(before.equals(DatingAcceptanceRoles.snapshot(db, false)), name + " changed authorization rows");
        pass(name);
    }
    static String id(String key) throws Exception {
        try (PreparedStatement s = db.prepareStatement("SELECT role_id FROM sys_role WHERE role_key=?")) {
            s.setString(1, key);
            try (ResultSet r = s.executeQuery()) { check(r.next(), "Missing role"); return r.getString(1); }
        }
    }
    static void removeManagedRoles() throws Exception {
        sql("DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.role_id=rm.role_id " +
            "WHERE r.create_by='local_acceptance_setup'");
        sql("DELETE FROM sys_role WHERE create_by='local_acceptance_setup'");
    }
    public static void main(String[] args) throws Exception
    {
        check(args.length == 1, "Expected isolated data directory");
        Path data = Path.of(args[0]).toAbsolutePath().normalize();
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        check(data.startsWith(temp) && data.getFileName().toString().equals("data") &&
            data.getParent().getFileName().toString().startsWith("dating-mysql-"), "Only disposable datadir allowed");
        evidence = Files.createDirectory(data.getParent().resolve("acceptance-role-tests"));
        String url = "jdbc:mysql://127.0.0.1:3307/dating_workflow_test?useSSL=false&allowPublicKeyRetrieval=true" +
            "&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=20000";
        try (Connection c = DriverManager.getConnection(url, "root", "")) {
            db = c;
            check("3307".equals(scalar("SELECT @@port")) && data.equals(
                Path.of(scalar("SELECT @@datadir")).toAbsolutePath().normalize()), "Wrong MySQL identity");
            check("0".equals(scalar("SELECT COUNT(*) FROM sys_role")), "Fresh role fixture required");
            var users = DatingAcceptanceRoles.rows(db, "SELECT * FROM sys_user ORDER BY user_id");
            sql("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status) VALUES" +
                "(1,'Fixture admin','admin',1,'0'),(2,'Fixture common','common',2,'0')");
            String root = scalar("SELECT menu_id FROM sys_menu WHERE path='dating' AND parent_id=0");
            String page = scalar("SELECT menu_id FROM sys_menu WHERE component='dating/matches/index'");
            String query = scalar("SELECT menu_id FROM sys_menu WHERE perms='dating:match:query'");
            String retry = scalar("SELECT menu_id FROM sys_menu WHERE perms='dating:deduction:retry'");
            sql("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(2," + root + ")");
            sql("INSERT INTO sys_user_role(user_id,role_id) VALUES(1,1)");
            var original = DatingAcceptanceRoles.snapshot(db, false);
            check("WOULD_CREATE".equals(run(false).get("action")), "Plan must predict creation");
            check(original.equals(DatingAcceptanceRoles.snapshot(db, false)), "Plan changed rows");
            pass("read-only plan");

            sql("INSERT INTO sys_user_role(user_id,role_id) VALUES(1,1234567)");
            rejected("orphan account binding cannot activate on role creation", true);
            sql("DELETE FROM sys_user_role WHERE user_id=1 AND role_id=1234567");

            check("CREATED".equals(run(true).get("action")), "Expected creation");
            check("6".equals(scalar("SELECT COUNT(*) FROM sys_role")), "Expected four new roles");
            check("13".equals(scalar("SELECT COUNT(*) FROM sys_role_menu")), "Expected twelve new grants");
            check(users.equals(DatingAcceptanceRoles.rows(db, "SELECT * FROM sys_user ORDER BY user_id")),
                "Users or passwords changed");
            pass("create four roles and twelve grants; existing rows and users preserved");

            var created = DatingAcceptanceRoles.snapshot(db, false);
            check("ALREADY_PRESENT".equals(run(true).get("action")), "Replay should not insert");
            check(created.equals(DatingAcceptanceRoles.snapshot(db, false)), "Replay changed rows");
            pass("idempotent replay");
            String owner = id("dating_accept_owner");
            String supervisor = id("dating_accept_supervisor");
            String readonly = id("dating_accept_readonly");
            String denied = id("dating_accept_denied");
            for (var p : DatingAcceptanceRoles.PROFILES) {
                Set<String> actual = new TreeSet<>();
                for (var row : DatingAcceptanceRoles.rows(db, "SELECT m.perms FROM sys_menu m JOIN sys_role_menu rm " +
                    "ON rm.menu_id=m.menu_id WHERE rm.role_id=" + id(p.key()) + " AND m.perms<>''"))
                    actual.add(row.get("perms"));
                check(actual.equals(new TreeSet<>(p.permissions())), "Unexpected exact permission set");
                pass("exact permissions " + p.key());
            }
            check(DatingAcceptanceRoles.grants(created, owner).containsAll(Set.of(root, page)), "Missing menu ancestors");
            pass("page navigation ancestry");

            sql("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(" + owner + "," + retry + ")");
            rejected("extra privilege fails closed", true);
            sql("DELETE FROM sys_role_menu WHERE role_id=" + owner + " AND menu_id=" + retry);
            sql("DELETE FROM sys_role_menu WHERE role_id=" + readonly + " AND menu_id=" + query);
            rejected("missing grant is not silently repaired", true);
            sql("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(" + readonly + "," + query + ")");
            sql("UPDATE sys_role SET status='1' WHERE role_id=" + supervisor);
            rejected("disabled role is not re-enabled", true);
            sql("UPDATE sys_role SET status='0' WHERE role_id=" + supervisor);
            sql("UPDATE sys_role SET role_key='conflicting_key' WHERE role_id=" + denied);
            rejected("name conflict", true);
            sql("UPDATE sys_role SET role_key='dating_accept_denied' WHERE role_id=" + denied);
            sql("UPDATE sys_role SET del_flag='2' WHERE role_id=" + denied);
            rejected("deleted role is not recreated", true);
            sql("UPDATE sys_role SET del_flag='0' WHERE role_id=" + denied);
            sql("INSERT INTO sys_role_dept(role_id,dept_id) VALUES(" + owner + ",999)");
            rejected("unexpected department binding", true);
            sql("DELETE FROM sys_role_dept WHERE role_id=" + owner + " AND dept_id=999");

            sql("UPDATE sys_menu SET perms='system:user:edit' WHERE menu_id=" + root);
            rejected("ancestor cannot grant unrelated privilege", false);
            try (PreparedStatement s = db.prepareStatement("UPDATE sys_menu SET perms=? WHERE menu_id=?")) {
                s.setString(1, original.get("sys_menu").stream().filter(m -> root.equals(m.get("menu_id")))
                    .findFirst().orElseThrow().get("perms"));
                s.setString(2, root);
                s.executeUpdate();
            }
            sql("UPDATE sys_menu SET status='1' WHERE menu_id=" + query);
            rejected("disabled required menu", true);
            sql("UPDATE sys_menu SET status='0' WHERE menu_id=" + query);
            sql("UPDATE sys_menu SET parent_id=" + root + " WHERE menu_id=" + query);
            rejected("unexpected menu hierarchy", true);
            sql("UPDATE sys_menu SET parent_id=" + page + " WHERE menu_id=" + query);
            sql("INSERT INTO sys_menu(menu_name,menu_type,perms) VALUES('Duplicate fixture','F','dating:match:query')");
            String duplicate = scalar("SELECT LAST_INSERT_ID()");
            rejected("duplicate permission", true);
            sql("DELETE FROM sys_menu WHERE menu_id=" + duplicate);
            sql("UPDATE sys_menu SET perms='fixture:missing' WHERE menu_id=" + query);
            rejected("missing permission", true);
            sql("UPDATE sys_menu SET perms='dating:match:query' WHERE menu_id=" + query);

            sql("INSERT INTO sys_user_role(user_id,role_id) VALUES(1," + readonly + ")");
            var bound = DatingAcceptanceRoles.snapshot(db, false);
            run(true);
            check(bound.equals(DatingAcceptanceRoles.snapshot(db, false)), "Replay changed bound role");
            pass("replay preserves existing account bindings");
            sql("DELETE FROM sys_user_role WHERE user_id=1 AND role_id=" + readonly);
            sql("DELETE FROM sys_role WHERE role_id=" + denied);
            rejected("partial role set", true);
            removeManagedRoles();
            check(original.equals(DatingAcceptanceRoles.snapshot(db, false)), "Fixture restoration changed old rows");

            Path missing = evidence.resolve("missing-parent").resolve("missing");
            boolean noBackup = false;
            try { DatingAcceptanceRoles.execute(db, true, missing); } catch (Exception expected) { noBackup = true; }
            check(noBackup && original.equals(DatingAcceptanceRoles.snapshot(db, false)), "Must not write without backup");
            pass("backup failure leaves database unchanged");

            sql("ALTER TABLE sys_role_menu ADD CONSTRAINT ck_acceptance_fault CHECK(menu_id<>" + query + ")");
            try { rejected("mid-grant SQL failure rolls back all new roles and grants", true); }
            finally { sql("ALTER TABLE sys_role_menu DROP CHECK ck_acceptance_fault"); }
            check(original.equals(DatingAcceptanceRoles.snapshot(db, false)), "Failure left partial configuration");
            check("CREATED".equals(run(true).get("action")), "Retry after rollback should succeed");
            pass("successful retry after rollback");
            check(users.equals(DatingAcceptanceRoles.rows(db, "SELECT * FROM sys_user ORDER BY user_id")), "Users changed");
            System.out.println("Acceptance role tests passed: " + passed + "; isolated 3307 only; no business login performed.");
        }
    }
}
