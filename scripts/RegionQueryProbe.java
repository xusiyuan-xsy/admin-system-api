import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import com.ruoyi.system.domain.AdministrativeDivision;
import com.ruoyi.system.mapper.AdministrativeDivisionMapper;
import com.ruoyi.system.service.impl.AdministrativeDivisionServiceImpl;

/** Read-only integration probe using the deployed mapper and service, without login tokens. */
public class RegionQueryProbe
{
    private static int checks;

    private static void check(boolean condition, String label)
    {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 2 || (!args[1].equals("ry-vue")
            && !args[1].matches("codex_dating_recovery_check_[0-9]{8}_[0-9]{6}_[0-9]{3}")))
            throw new IllegalArgumentException("Expected local config and allowed database");
        Object value;
        LoaderOptions options = new LoaderOptions();
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
            throw new IllegalArgumentException("Unexpected datasource");
        var source = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
            url.replace("/ry-vue", "/" + args[1]), Objects.toString(master.get("username")),
            Objects.toString(master.get("password")));
        var configuration = new Configuration(new Environment("region-probe", new JdbcTransactionFactory(), source));
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        String resource = "mapper/system/AdministrativeDivisionMapper.xml";
        try (InputStream input = RegionQueryProbe.class.getClassLoader().getResourceAsStream(resource))
        {
            if (input == null) throw new IllegalStateException("Packaged mapper missing");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession())
        {
            session.getConnection().setReadOnly(true);
            var mapper = session.getMapper(AdministrativeDivisionMapper.class);
            var service = new AdministrativeDivisionServiceImpl();
            var field = AdministrativeDivisionServiceImpl.class.getDeclaredField("administrativeDivisionMapper");
            field.setAccessible(true);
            field.set(service, mapper);
            var roots = service.selectRegionList(null);
            check(roots.size() == 34, "34 seeded province-level regions");
            check(service.selectRegionList(" ").size() == roots.size(), "blank parent normalization");
            check(service.selectRegionList("invalid").isEmpty(), "unknown parent");
            check(service.selectRegionPath(" ").isEmpty(), "blank path");
            check(service.selectRegionPath("invalid").isEmpty(), "unknown path");
            var beijing = service.selectRegionPath(" 110101 ");
            check(beijing.stream().map(AdministrativeDivision::getCode).toList()
                .equals(List.of("110000", "110101")), "municipality path");
            check("\u5317\u4eac\u5e02".equals(beijing.get(0).getName()), "UTF-8 region name");
            check(Boolean.TRUE.equals(beijing.get(1).getLeaf()), "district leaf");
            check(Boolean.FALSE.equals(beijing.get(0).getLeaf()), "province not leaf");
            var hebei = service.selectRegionPath("130102");
            check(hebei.stream().map(AdministrativeDivision::getCode).toList()
                .equals(List.of("130000", "130100", "130102")), "three-level path");
            for (var root : roots)
            {
                var children = service.selectRegionList(root.getCode());
                check(root.getLeaf().equals(children.isEmpty()), "root leaf matches children");
                check(children.stream().allMatch(child -> root.getCode().equals(child.getParentCode())
                    && child.getLevel() > root.getLevel()), "children hierarchy");
            }
            check(service.selectRegionList("110101").isEmpty(), "district has no children");
            check(mapper.selectRegionList("ZZ", "").isEmpty(), "country isolation");
        }
        System.out.println("Region mapper/service: " + checks + " read-only checks passed.");
    }
}
