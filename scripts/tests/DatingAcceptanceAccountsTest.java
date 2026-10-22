import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.security.SecureRandom;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** Uses only the disposable 3307 database after the role fixture has passed. */
public class DatingAcceptanceAccountsTest extends DatingAcceptanceRolesTest
{
    static Map<String, String> passwords = new LinkedHashMap<>();
    static Map<String, Object> accounts(boolean apply) throws Exception {
        return DatingAcceptanceAccounts.provision(db, apply, Files.createTempDirectory(evidence, "case-"), passwords);
    }
    static void rejectAccount(String name) throws Exception {
        var before = DatingAcceptanceAccounts.accountSnapshot(db, false);
        boolean rejected = false;
        try { accounts(true); } catch (Exception expected) { rejected = true; }
        check(rejected && before.equals(DatingAcceptanceAccounts.accountSnapshot(db, false)), name);
        pass(name);
    }
    public static void main(String[] args) throws Exception
    {
        check(args.length == 1, "Expected disposable datadir");
        Path data = Path.of(args[0]).toAbsolutePath().normalize();
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        check(data.startsWith(temp) && data.getFileName().toString().equals("data") &&
            data.getParent().getFileName().toString().startsWith("dating-mysql-"), "Wrong fixture location");
        evidence = Files.createDirectory(data.getParent().resolve("acceptance-account-tests"));
        try (Connection c = DriverManager.getConnection(
            "jdbc:mysql://127.0.0.1:3307/dating_workflow_test?useSSL=false&allowPublicKeyRetrieval=true" +
            "&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=20000", "root", "")) {
            db = c;
            check("3307".equals(scalar("SELECT @@port")) &&
                data.equals(Path.of(scalar("SELECT @@datadir")).toAbsolutePath().normalize()), "Wrong database");
            var original = DatingAcceptanceAccounts.accountSnapshot(c, false);
            SecureRandom random = new SecureRandom();
            for (var p : DatingAcceptanceRoles.PROFILES) {
                byte[] bytes = new byte[12]; random.nextBytes(bytes);
                passwords.put(DatingAcceptanceAccounts.username(p), Base64.getEncoder().encodeToString(bytes) + "aA7!");
            }
            check("WOULD_CREATE".equals(accounts(false).get("action")) &&
                original.equals(DatingAcceptanceAccounts.accountSnapshot(c, false)), "Plan changed rows");
            pass("account plan is read only");
            String valid = passwords.put("accept_owner", "short");
            rejectAccount("invalid account password leaves rows unchanged");
            passwords.put("accept_owner", valid);
            String other = passwords.put("accept_denied", valid);
            rejectAccount("duplicate account passwords rejected");
            passwords.put("accept_denied", other);

            sql("INSERT INTO sys_user_role(user_id,role_id) VALUES(999999,1)");
            rejectAccount("orphan user binding rejected");
            sql("DELETE FROM sys_user_role WHERE user_id=999999");
            sql("INSERT INTO sys_user(user_name,nick_name) VALUES('ACCEPT_OWNER','Existing account')");
            rejectAccount("case-insensitive existing account collision rejected");
            sql("DELETE FROM sys_user WHERE user_name='ACCEPT_OWNER'");
            check(original.equals(DatingAcceptanceAccounts.accountSnapshot(c, false)), "Fixture not restored");

            boolean backupFailed = false;
            try { DatingAcceptanceAccounts.provision(c, true, evidence.resolve("missing"), passwords); }
            catch (Exception expected) { backupFailed = true; }
            check(backupFailed && original.equals(DatingAcceptanceAccounts.accountSnapshot(c, false)), "Backup failure mutated data");
            pass("account backup failure is fail closed");
            sql("ALTER TABLE sys_user ADD CONSTRAINT ck_accept_account_fault CHECK(user_name<>'accept_supervisor')");
            try { rejectAccount("mid-account SQL failure rolls back users and bindings"); }
            finally { sql("ALTER TABLE sys_user DROP CHECK ck_accept_account_fault"); }
            check("CREATED".equals(accounts(true).get("action")), "Creation failed");
            var created = DatingAcceptanceAccounts.accountSnapshot(c, false);
            check(created.get("sys_user").size() == original.get("sys_user").size() + 4, "Wrong account count");
            check(created.get("sys_user_role").size() == original.get("sys_user_role").size() + 4, "Wrong binding count");
            for (var old : original.get("sys_user"))
                check(created.get("sys_user").contains(old), "Existing user changed");
            for (var p : DatingAcceptanceRoles.PROFILES) {
                String name = DatingAcceptanceAccounts.username(p);
                var u = created.get("sys_user").stream().filter(row -> name.equals(row.get("user_name"))).findFirst().orElseThrow();
                check(BCrypt.checkpw(passwords.get(name), u.get("password")), "Password does not verify");
                check(!u.get("password").equals(passwords.get(name)), "Plaintext stored");
            }
            pass("four exact role bindings and BCrypt credentials; original users preserved");
            check("ALREADY_PRESENT".equals(accounts(true).get("action")) &&
                created.equals(DatingAcceptanceAccounts.accountSnapshot(c, false)), "Replay changed passwords");
            pass("account replay does not reset passwords or bindings");
            sql("UPDATE sys_user SET status='1' WHERE user_name='accept_owner'");
            rejectAccount("disabled account not re-enabled");
            sql("UPDATE sys_user SET status='0' WHERE user_name='accept_owner'");
            sql("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name='accept_owner'");
            rejectAccount("extra account privilege rejected");
            sql("DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.user_id=ur.user_id WHERE u.user_name='accept_owner' AND ur.role_id=1");
            sql("INSERT INTO sys_user_post(user_id,post_id) SELECT user_id,1 FROM sys_user WHERE user_name='accept_owner'");
            rejectAccount("unexpected account post rejected");
            sql("DELETE up FROM sys_user_post up JOIN sys_user u ON u.user_id=up.user_id WHERE u.user_name='accept_owner'");
            sql("DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.user_id=ur.user_id WHERE u.user_name='accept_denied'");
            sql("DELETE FROM sys_user WHERE user_name='accept_denied'");
            rejectAccount("partial account set not repaired");
            System.out.println("Acceptance account tests passed: " + passed + "; disposable 3307 only.");
        }
    }
}
