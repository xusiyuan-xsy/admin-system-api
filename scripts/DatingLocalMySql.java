import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Internal deployment bridge: credentials stay out of command lines and reports. */
public class DatingLocalMySql
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 5) throw new IllegalArgumentException("Expected config, bin, mode, database, dump path");
        Map<?, ?> config;
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(Path.of(args[0])))
        {
            config = new Yaml(new SafeConstructor(options)).load(input);
        }
        Object value = config;
        for (String key : List.of("spring", "datasource", "druid", "master"))
            value = ((Map<?, ?>) value).get(key);
        Map<?, ?> master = (Map<?, ?>) value;
        String url = Objects.toString(master.get("url"), "");
        String user = Objects.toString(master.get("username"), "");
        String password = Objects.toString(master.get("password"), "");
        if (!url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$")
            || !user.equals("root") || password.isBlank() || password.contains("${"))
            throw new IllegalArgumentException("Only the inspected explicit local datasource is supported");
        if (!args[3].equals("ry-vue") && !args[3].matches("codex_dating_recovery_check_[0-9]{8}_[0-9]{6}_[0-9]{3}"))
            throw new IllegalArgumentException("Unexpected database");
        boolean dump = args[2].equals("dump");
        if (!dump && !args[2].equals("query")) throw new IllegalArgumentException("Unexpected mode");
        List<String> command = new ArrayList<>(List.of(
            Path.of(args[1], dump ? "mysqldump.exe" : "mysql.exe").toString(),
            "--no-defaults", "--protocol=TCP", "--host=127.0.0.1", "--port=3306",
            "--user=root", "--default-character-set=utf8mb4"));
        if (dump)
        {
            if (!args[3].equals("ry-vue") || !Path.of(args[4]).isAbsolute() || Files.exists(Path.of(args[4])))
                throw new IllegalArgumentException("Backup requires a new absolute path");
            command.addAll(List.of("--single-transaction", "--quick", "--routines", "--events",
                "--triggers", "--hex-blob", "--set-gtid-purged=OFF", "--no-tablespaces",
                "--result-file=" + args[4], args[3]));
        }
        else command.addAll(List.of("--connect-timeout=5", "--batch", "--raw", "--skip-column-names", "--database=" + args[3]));
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        builder.environment().put("MYSQL_PWD", password);
        builder.environment().put("MYSQL_TEST_LOGIN_FILE", Path.of(args[1], "no-dating-login.cnf").toString());
        System.exit(builder.start().waitFor());
    }
}
