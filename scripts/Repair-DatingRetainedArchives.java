import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** One-batch repair only. Requires a fresh protected backup; never changes service records. */
class RepairDatingRetainedArchives
{
    static final String BATCH = "FT1016182629ee3";
    static final String ACTOR = "flowtest_" + BATCH;
    static final Set<String> ALLOWED = Set.of("phone", "current_city", "hometown", "has_house", "has_car",
        "has_tattoo", "smoke_habit", "drink_habit", "accept_long_dist", "remark", "update_by", "update_time");

    public static void main(String[] args) throws Exception
    {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        if (args.length != 3 || !args[0].equals("--apply"))
            throw new IllegalArgumentException("Expected --apply <local-config> <protected-evidence-directory>");
        Path output = Path.of(args[2]).toAbsolutePath();
        Path backup = output.resolve("before-archive-repair.sql");
        if (!Files.isRegularFile(backup) || Files.size(backup) < 1000
            || Files.exists(output.resolve("repair-result.json")))
            throw new IllegalStateException("Requires a new evidence directory with completed backup");
        Object config;
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(Path.of(args[1])))
        { config = new Yaml(new SafeConstructor(options)).load(input); }
        for (String key : List.of("spring", "datasource", "druid", "master"))
            config = ((Map<?, ?>) config).get(key);
        Map<?, ?> master = (Map<?, ?>) config;
        String url = Objects.toString(master.get("url"), "");
        if (!url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$"))
            throw new IllegalStateException("Unexpected datasource");
        var source = new DriverManagerDataSource(url, Objects.toString(master.get("username")),
            Objects.toString(master.get("password")));
        var jdbc = new JdbcTemplate(source);
        var identity = jdbc.queryForMap("select @@port as port,database() as db");
        require(((Number) identity.get("port")).intValue() == 3306 && identity.get("db").equals("ry-vue"),
            "Database identity mismatch");
        var result = new LinkedHashMap<String, Object>();
        result.put("batch", BATCH);
        result.put("startedAt", LocalDateTime.now().toString());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            try
            {
                var before = jdbc.queryForList("select * from sys_paying_user order by id for update");
                var targets = before.stream().filter(row -> ACTOR.equals(row.get("create_by"))).toList();
                require(targets.size() == 10, "Expected exactly ten batch archives");
                for (int i = 0; i < targets.size(); i++)
                {
                    var row = targets.get(i);
                    require(((Number) row.get("id")).longValue() == 7 + i
                        && (BATCH + "-" + (char) ('A' + i)).equals(row.get("name"))
                        && Objects.toString(row.get("remark"), "").contains("TEST ONLY")
                        && "0".equals(row.get("del_flag")), "Batch ownership mismatch");
                }
                String region = jdbc.queryForObject("select concat(p.name_local,'/',c.name_local,'/',d.name_local)"
                    + " from base_administrative_division d join base_administrative_division c"
                    + " on c.country_code=d.country_code and c.division_id=d.parent_id"
                    + " join base_administrative_division p on p.country_code=c.country_code and p.division_id=c.parent_id"
                    + " where d.country_code='CN' and d.division_id='440305' and d.division_level=3", String.class);
                require(DatingRetainedWorkflowProbe.TEST_REGION.equals(region), "Unexpected region hierarchy");
                DatingRetainedWorkflowProbe.write(output.resolve("archives-before.json"), before);
                var probe = new DatingRetainedWorkflowProbe();
                probe.jdbc = jdbc;
                var protectedBefore = probe.fingerprints();
                protectedBefore.remove("sys_paying_user");
                var changes = new ArrayList<Map<String, Object>>();
                for (var row : targets)
                {
                    var values = new LinkedHashMap<String, Object>();
                    String phone = Objects.toString(row.get("phone"), "");
                    if (!phone.matches("^1[3-9][0-9]{9}$"))
                    {
                        require(phone.matches("^000[0-9]{8}$"), "Unexpected invalid phone; manual review required");
                        values.put("phone", DatingRetainedWorkflowProbe.fixturePhone(jdbc));
                    }
                    for (String column : List.of("current_city", "hometown"))
                        if (Objects.toString(row.get(column), "").isBlank()) values.put(column, region);
                    for (String column : List.of("has_house", "has_car", "has_tattoo", "smoke_habit", "drink_habit", "accept_long_dist"))
                        if (Objects.toString(row.get(column), "").isBlank()) values.put(column, "0");
                    if (!values.isEmpty())
                    {
                        values.put("remark", row.get("remark") + "; synthetic phone, DO NOT CALL OR SEND SMS");
                        values.put("update_by", "test_archive_repair");
                        var parameters = new ArrayList<>(values.values());
                        parameters.add(row.get("id"));
                        parameters.add(ACTOR);
                        String assignments = String.join(",", values.keySet().stream().map(key -> key + "=?").toList());
                        require(jdbc.update("update sys_paying_user set " + assignments
                            + ",update_time=current_timestamp where id=? and create_by=?", parameters.toArray()) == 1,
                            "Unexpected update count");
                    }
                    changes.add(Map.of("id", row.get("id"), "fields", List.copyOf(values.keySet())));
                }
                var after = jdbc.queryForList("select * from sys_paying_user order by id");
                require(before.size() == after.size(), "Archive count changed");
                for (int i = 0; i < before.size(); i++)
                {
                    var oldRow = before.get(i);
                    var newRow = after.get(i);
                    if (ACTOR.equals(oldRow.get("create_by")))
                    {
                        require(DatingRetainedWorkflowProbe.archiveProblems(newRow).isEmpty(), "Required field validation failed");
                        require(jdbc.queryForObject("select count(*) from sys_paying_user where phone=?",
                            Integer.class, newRow.get("phone")) == 1, "Duplicate phone");
                        for (String field : oldRow.keySet())
                            if (!ALLOWED.contains(field))
                                require(Objects.equals(oldRow.get(field), newRow.get(field)), "Unrelated archive field changed");
                    }
                    else require(oldRow.equals(newRow), "Non-test archive changed");
                }
                var protectedAfter = probe.fingerprints();
                protectedAfter.remove("sys_paying_user");
                require(protectedBefore.equals(protectedAfter), "Service or account data changed");
                DatingRetainedWorkflowProbe.write(output.resolve("archives-after.json"), after);
                result.put("repairedArchives", targets.size());
                result.put("changes", changes);
                result.put("protectedTablesUnchanged", protectedBefore.size());
                result.put("otherArchivesUnchanged", true);
                result.put("requiredFieldsValid", true);
            }
            catch (Exception ex) { throw new IllegalStateException("Repair failed; transaction rolled back", ex); }
        });
        result.put("status", "COMMITTED");
        result.put("finishedAt", LocalDateTime.now().toString());
        DatingRetainedWorkflowProbe.write(output.resolve("repair-result.json"), result);
        System.out.println("Repaired 10 test archives; required fields valid; service records unchanged; batch=" + BATCH);
    }

    static void require(boolean condition, String message)
    { if (!condition) throw new IllegalStateException(message); }
}
