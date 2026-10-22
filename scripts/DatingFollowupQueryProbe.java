import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInterceptor;
import com.ruoyi.system.mapper.DatingRecoveryMapper;

/** Read-only deployed-mapper probe. Not an HTTP login or role acceptance test. */
public class DatingFollowupQueryProbe
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 1) throw new IllegalArgumentException("Expected local datasource config");
        Object value;
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(Path.of(args[0])))
        {
            value = new Yaml(new SafeConstructor(options)).load(input);
        }
        for (String key : List.of("spring", "datasource", "druid", "master"))
            value = ((Map<?, ?>) value).get(key);
        Map<?, ?> master = (Map<?, ?>) value;
        String url = Objects.toString(master.get("url"), "");
        if (!url.matches("^jdbc:mysql://(localhost|127\\.0\\.0\\.1):3306/ry-vue(\\?.*)?$"))
            throw new IllegalArgumentException("Only local ry-vue is allowed");
        var source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url,
            Objects.toString(master.get("username")), Objects.toString(master.get("password")));
        var configuration = new Configuration(new Environment("followup-probe", new JdbcTransactionFactory(), source));
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        var page = new PageInterceptor();
        var properties = new Properties();
        properties.setProperty("helperDialect", "mysql");
        page.setProperties(properties);
        configuration.addInterceptor(page);
        String resource = "mapper/system/DatingRecoveryMapper.xml";
        try (InputStream input = DatingFollowupQueryProbe.class.getClassLoader().getResourceAsStream(resource))
        {
            if (input == null) throw new IllegalStateException("Deployed mapper missing");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        int checks = 0;
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession())
        {
            session.getConnection().setReadOnly(true);
            var mapper = session.getMapper(DatingRecoveryMapper.class);
            // Count can skip the SELECT when empty, so exercise both SQL paths explicitly.
            for (boolean count : List.of(true, false))
                for (String owner : Arrays.asList(null, "accept_owner", "accept_readonly"))
                    for (int filter = 0; filter < 6; filter++)
                    {
                        PageHelper.startPage(1, 10, count);
                        try
                        {
                            var rows = mapper.issues(null, owner, new Date(System.currentTimeMillis() - 300000),
                                null, filter >= 3 ? List.of("UNASSIGNED", "IN_PROGRESS", "WAITING").get(filter - 3) : null,
                                filter == 1 ? "accept_owner" : null, filter == 2);
                            if (rows.size() > 10) throw new AssertionError("Pagination was not applied");
                            checks++;
                        }
                        finally { PageHelper.clearPage(); }
                    }
            // A nonexistent ID checks JOIN compatibility, not ownership or business correctness.
            mapper.followup(0L);
            checks++;
            session.rollback();
        }
        System.out.println("Deployed follow-up mapper: " + checks + " read-only SQL checks passed; no HTTP role acceptance.");
    }
}
