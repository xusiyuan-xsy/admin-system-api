import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Local read-only inventory. Never fetches references or prints customer data. */
public class DatingPhotoInventory
{
    private static final String PREFIX = "/dating/archives/";

    static String kind(long id, String reference)
    {
        if (reference == null || reference.isBlank()) return "invalid";
        String prefix = PREFIX + id + "/photos/";
        if (id > 0 && reference.startsWith(prefix)
            && reference.substring(prefix.length()).matches("[a-f0-9]{32}\\.(png|jpg)"))
            return "private";
        if (reference.startsWith(PREFIX)) return "invalidPrivate";
        try
        {
            URI uri = URI.create(reference);
            if (Set.of("http", "https").contains(uri.getScheme() == null ? "" : uri.getScheme())
                && uri.getHost() != null && uri.getUserInfo() == null) return "external";
            if (uri.getScheme() == null && uri.getRawAuthority() == null
                && reference.startsWith("/profile/")) return "publicProfile";
        }
        catch (IllegalArgumentException ignored) { }
        return "invalid";
    }

    static List<?> photos(String text)
    {
        if (text == null) return List.of();
        Object parsed = JSON.parse(text);
        if (!(parsed instanceof List<?> values)) throw new IllegalArgumentException("Invalid photo array");
        return values;
    }

    static Object field(Map<?, ?> map, String... path)
    {
        Object value = map;
        for (String key : path)
        {
            if (!(value instanceof Map<?, ?> current)) throw new IllegalArgumentException("Missing configuration");
            value = current.get(key);
        }
        if (value == null) throw new IllegalArgumentException("Missing configuration");
        return value;
    }

    static Map<?, ?> yaml(Path file) throws Exception
    {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream stream = Files.newInputStream(file))
        {
            return new Yaml(new SafeConstructor(options)).load(stream);
        }
    }

    static long count(Connection connection, String sql) throws SQLException
    {
        try (Statement statement = connection.createStatement())
        {
            statement.setQueryTimeout(15);
            try (ResultSet result = statement.executeQuery(sql))
            {
                result.next();
                return result.getLong(1);
            }
        }
    }

    static boolean privateFileExists(Path root, long id, String reference) throws Exception
    {
        if (!Files.isDirectory(root)) return false;
        Path actualRoot = root.toRealPath();
        Path directory = root.resolve(Long.toString(id));
        Path file = directory.resolve(reference.substring(reference.lastIndexOf('/') + 1));
        return !Files.isSymbolicLink(directory) && !Files.isSymbolicLink(file)
            && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            && file.toRealPath().startsWith(actualRoot);
    }

    static Map<String, Object> inspect(Path config, Path privateRoot) throws Exception
    {
        Map<?, ?> main = yaml(config.resolve("application.yml"));
        Map<?, ?> druid = yaml(config.resolve("application-druid.yml"));
        String url = field(druid, "spring", "datasource", "druid", "master", "url").toString();
        String username = field(druid, "spring", "datasource", "druid", "master", "username").toString();
        String password = field(druid, "spring", "datasource", "druid", "master", "password").toString();
        if (!url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$")
            || username.contains("${") || password.contains("${"))
            throw new IllegalArgumentException("Only explicit local ry-vue configuration is supported");
        Path profile = Path.of(field(main, "ruoyi", "profile").toString()).toAbsolutePath().normalize();
        Path root = privateRoot.toAbsolutePath().normalize();
        if (root.startsWith(profile) || profile.startsWith(root))
            throw new IllegalArgumentException("Private root overlaps public profile");
        if (Files.exists(root) && Files.exists(profile)
            && (root.toRealPath().startsWith(profile.toRealPath()) || profile.toRealPath().startsWith(root.toRealPath())))
            throw new IllegalArgumentException("Resolved storage overlaps public profile");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkedAt", Instant.now().toString());
        report.put("database", "127.0.0.1:3306/ry-vue");
        report.put("privateRoot", root.toString());
        report.put("publicProfile", profile.toString());
        report.put("privateRootExists", Files.isDirectory(root));
        report.put("privateRootReadable", Files.isReadable(root));
        report.put("privateRootWritable", Files.isWritable(root));
        if (Files.isDirectory(root)) report.put("usableBytes", Files.getFileStore(root).getUsableSpace());
        // Use fixed connection options, never arbitrary JDBC options from a config file.
        String localUrl = "jdbc:mysql://127.0.0.1:3306/ry-vue?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=20000";
        try (Connection connection = DriverManager.getConnection(localUrl, username, password))
        {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement start = connection.createStatement())
            {
                start.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
            }
            report.put("readyDeductionTasks", count(connection, "SELECT COUNT(*) FROM dating_deduction_task WHERE status='READY'"));
            report.put("errorDeductionTasks", count(connection, "SELECT COUNT(*) FROM dating_deduction_task WHERE status='ERROR'"));
            report.put("enabledQuartzJobs", count(connection, "SELECT COUNT(*) FROM sys_job WHERE status='0'"));
            report.put("otherActiveTransactions", count(connection,
                "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id<>CONNECTION_ID()"));
            Map<String, Long> counts = new TreeMap<>();
            for (String name : List.of("archives", "archivesWithPhotos", "references", "private",
                "invalidPrivate", "external", "publicProfile", "invalid", "malformedPhotoArrays",
                "archivesOverNineReferences", "softDeletedReferences", "duplicateReferencesWithinArchive",
                "referencesAlsoUsedByPackageCover", "missingOrUnsafePrivateFiles"))
                counts.put(name, 0L);
            Map<String, Long> externalHosts = new TreeMap<>();
            Set<String> packageCovers = new HashSet<>();
            Map<String, Long> firstOwner = new HashMap<>();
            Set<String> sharedAcrossArchives = new HashSet<>();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Statement statement = connection.createStatement())
            {
                statement.setQueryTimeout(30);
                try (ResultSet result = statement.executeQuery("SELECT cover_image FROM dating_package WHERE cover_image IS NOT NULL"))
                {
                    while (result.next()) packageCovers.add(result.getString(1));
                }
                try (ResultSet result = statement.executeQuery("SELECT id,photos,del_flag FROM sys_paying_user ORDER BY id"))
                {
                    while (result.next())
                    {
                        long id = result.getLong("id");
                        String text = result.getString("photos");
                        String deleted = result.getString("del_flag");
                        digest.update(JSON.toJSONString(Arrays.asList(id, text, deleted)).getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) '\n');
                        counts.merge("archives", 1L, Long::sum);
                        List<?> values;
                        try { values = photos(text); }
                        catch (RuntimeException e)
                        {
                            counts.merge("malformedPhotoArrays", 1L, Long::sum);
                            continue;
                        }
                        if (!values.isEmpty()) counts.merge("archivesWithPhotos", 1L, Long::sum);
                        if (values.size() > 9) counts.merge("archivesOverNineReferences", 1L, Long::sum);
                        Set<String> seen = new HashSet<>();
                        for (Object item : values)
                        {
                            counts.merge("references", 1L, Long::sum);
                            if ("2".equals(deleted)) counts.merge("softDeletedReferences", 1L, Long::sum);
                            String reference = item instanceof String s ? s : null;
                            String kind = kind(id, reference);
                            counts.merge(kind, 1L, Long::sum);
                            if (reference == null) continue;
                            if (!seen.add(reference)) counts.merge("duplicateReferencesWithinArchive", 1L, Long::sum);
                            Long previous = firstOwner.putIfAbsent(reference, id);
                            if (previous != null && previous != id) sharedAcrossArchives.add(reference);
                            if (packageCovers.contains(reference)) counts.merge("referencesAlsoUsedByPackageCover", 1L, Long::sum);
                            if ("external".equals(kind)) externalHosts.merge(URI.create(reference).getHost().toLowerCase(Locale.ROOT), 1L, Long::sum);
                            if ("private".equals(kind) && !privateFileExists(root, id, reference))
                                counts.merge("missingOrUnsafePrivateFiles", 1L, Long::sum);
                        }
                    }
                }
            }
            counts.put("referencesSharedAcrossArchives", (long) sharedAcrossArchives.size());
            report.put("counts", counts);
            report.put("externalHosts", externalHosts);
            report.put("photoBindingsSha256", HexFormat.of().formatHex(digest.digest()));
            report.put("networkPhotoRequests", 0);
            report.put("businessWrites", 0);
            connection.rollback();
        }
        return report;
    }

    static void selfTest()
    {
        String valid = "/dating/archives/1/photos/" + "a".repeat(32) + ".png";
        Map<String, String> cases = Map.of(
            valid, "private", valid + "/../x", "invalidPrivate",
            "/dating/archives/2/photos/" + "b".repeat(32) + ".jpg", "invalidPrivate",
            "https://photos.example.test/a?token=hidden", "external",
            "https://user:secret@example.test/a", "invalid",
            "//example.test/a", "invalid", "/profile/a.png", "publicProfile",
            "file:///C:/secret", "invalid", "data:image/png;base64,x", "invalid");
        cases.forEach((reference, expected) -> {
            if (!expected.equals(kind(1, reference))) throw new AssertionError("Classification failed");
        });
        if (!photos(null).isEmpty() || photos("[null,1,\"x\"]").size() != 3)
            throw new AssertionError("Array parsing failed");
        for (String malformed : List.of("{}", "\"url\"", "null", "["))
        {
            try { photos(malformed); throw new AssertionError("Invalid array accepted"); }
            catch (RuntimeException expected) { }
        }
        System.out.println("PASS: photo inventory classification and strict array parsing; no database connection");
    }

    public static void main(String[] args)
    {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try
        {
            if (args.length == 1 && "--self-test".equals(args[0])) { selfTest(); return; }
            if (args.length != 2) throw new IllegalArgumentException("Expected config directory and private root");
            System.out.println(JSON.toJSONString(inspect(Path.of(args[0]), Path.of(args[1]))));
        }
        catch (Exception e)
        {
            // SQL and parser messages can contain credentials or original photo references.
            System.err.println("Inventory failed (" + e.getClass().getSimpleName()
                + "). Check local configuration/schema/storage; sensitive details suppressed.");
            System.exit(1);
        }
    }
}
