package com.ruoyi.system.service;

import java.time.Year;
import java.util.*;
import com.ruoyi.common.core.domain.entity.SysPayingRequirement;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.DatingMatchmakerConfig;
import com.ruoyi.system.mapper.DatingMatchmakerConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatingMatchmakerConfigService
{
    public static final Map<String, String> FIELDS;
    static
    {
        var fields = new LinkedHashMap<String, String>();
        fields.put("ageMin", "最早出生年份");
        fields.put("ageMax", "最晚出生年份");
        fields.put("heightMin", "最低身高");
        fields.put("heightMax", "最高身高");
        fields.put("smokeHabit", "吸烟要求");
        fields.put("drinkHabit", "饮酒要求");
        fields.put("noTattoo", "是否要求无纹身");
        fields.put("hasHouse", "是否要求有房");
        fields.put("hasCar", "是否要求有车");
        fields.put("education", "学历");
        fields.put("hometownRequire", "家乡要求");
        fields.put("acceptLongDist", "是否接受异地");
        fields.put("cityRequire", "现居城市要求");
        fields.put("otherRequire", "其他要求");
        fields.put("remark", "备注");
        FIELDS = Collections.unmodifiableMap(fields);
    }
    private final DatingMatchmakerConfigMapper mapper;
    public DatingMatchmakerConfigService(DatingMatchmakerConfigMapper mapper) { this.mapper = mapper; }
    public List<DatingMatchmakerConfig> list(String name) { return mapper.selectList(name); }
    public DatingMatchmakerConfig active() { return mapper.selectActive(); }
    public Map<String, Object> rules()
    {
        var result = new LinkedHashMap<String, Object>();
        result.put("config", active());
        result.put("currentYear", Year.now().getValue());
        result.put("fields", FIELDS);
        return result;
    }
    private void require(boolean valid, String message)
    {
        if (!valid) throw new ServiceException(message);
    }
    private void validate(DatingMatchmakerConfig c)
    {
        require(c.getName() != null && !c.getName().isBlank() && c.getName().length() <= 100,
            "配置名称不能为空且不得超过100字");
        c.setName(c.getName().strip());
        String text = c.getWatermarkText() == null ? "" : c.getWatermarkText().strip();
        require(text.length() <= 100 && text.codePoints().noneMatch(Character::isISOControl),
            "水印文字不得超过100字或包含换行、控制字符");
        c.setWatermarkText(text);
        bounds(c.getMinAge(), c.getMaxAge(), 1900, Year.now().getValue(), "出生年份");
        bounds(c.getMinHeight(), c.getMaxHeight(), 100, 250, "身高");
        require(c.getRequiredFields() != null && c.getRequiredFields().size() <= FIELDS.size()
            && c.getRequiredFields().stream().allMatch(FIELDS::containsKey), "必填项包含不支持的字段");
        c.setRequiredFields(new ArrayList<>(new LinkedHashSet<>(c.getRequiredFields())));
    }
    private void bounds(Integer min, Integer max, int low, int high, String label)
    {
        require((min == null && max == null) || (min != null && max != null
            && min >= low && max <= high && min <= max), label + "须同时填写上下限，范围为" + low + "-" + high);
    }
    // The singleton row serializes creation, deletion and default switches, including an empty config list.
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DatingMatchmakerConfig create(DatingMatchmakerConfig c, String actor)
    {
        validate(c);
        Long defaultId = mapper.lockState();
        c.setConfigId(null);
        c.setCreateBy(actor);
        mapper.insert(c);
        if (defaultId == null) require(mapper.setDefault(c.getConfigId(), actor) == 1, "默认配置状态未初始化");
        return mapper.selectById(c.getConfigId());
    }
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public DatingMatchmakerConfig update(DatingMatchmakerConfig c, String actor)
    {
        validate(c);
        mapper.lockState();
        require(c.getVersion() != null, "缺少配置版本，请刷新");
        c.setUpdateBy(actor);
        require(mapper.update(c) == 1, "配置已被修改或删除，请刷新后重试");
        return mapper.selectById(c.getConfigId());
    }
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void setDefault(Long id, Long expectedDefaultId, Long version, String actor)
    {
        Long current = mapper.lockState();
        require(Objects.equals(current, expectedDefaultId), "默认配置已变化，请刷新并重新确认替换");
        DatingMatchmakerConfig target = mapper.selectById(id);
        require(target != null && Objects.equals(target.getVersion(), version), "目标配置已变化，请刷新后重试");
        require(mapper.setDefault(id, actor) == 1, "默认配置状态未初始化");
    }
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void delete(Long id, Long version, String actor)
    {
        Long current = mapper.lockState();
        DatingMatchmakerConfig target = mapper.selectById(id);
        require(target != null && Objects.equals(target.getVersion(), version), "配置已变化，请刷新后重试");
        if (Objects.equals(current, id))
        {
            require(mapper.count() == 1, "请先将其他配置设为默认，再删除当前默认配置");
            mapper.setDefault(null, actor);
        }
        mapper.delete(id);
    }
    public void validateRequirement(SysPayingRequirement r)
    {
        DatingMatchmakerConfig c = active();
        if (c == null) return;
        if (c.getMinAge() != null)
        {
            checkBound(r.getAgeMin(), c.getMinAge(), c.getMaxAge(), "出生年份");
            checkBound(r.getAgeMax(), c.getMinAge(), c.getMaxAge(), "出生年份");
        }
        if (c.getMinHeight() != null)
        {
            checkBound(r.getHeightMin(), c.getMinHeight(), c.getMaxHeight(), "身高");
            checkBound(r.getHeightMax(), c.getMinHeight(), c.getMaxHeight(), "身高");
        }
        for (String field : c.getRequiredFields())
        {
            Object value = switch (field)
            {
                case "ageMin" -> r.getAgeMin();
                case "ageMax" -> r.getAgeMax();
                case "heightMin" -> r.getHeightMin();
                case "heightMax" -> r.getHeightMax();
                case "smokeHabit" -> r.getSmokeHabit();
                case "drinkHabit" -> r.getDrinkHabit();
                case "noTattoo" -> r.getNoTattoo();
                case "hasHouse" -> r.getHasHouse();
                case "hasCar" -> r.getHasCar();
                case "education" -> r.getEducation();
                case "hometownRequire" -> r.getHometownRequire();
                case "acceptLongDist" -> r.getAcceptLongDist();
                case "cityRequire" -> r.getCityRequire();
                case "otherRequire" -> r.getOtherRequire();
                case "remark" -> r.getRemark();
                default -> throw new ServiceException("必填项配置无效，请联系管理员");
            };
            require(value != null && (!(value instanceof String text) || !text.isBlank()),
                FIELDS.get(field) + "为当前红娘配置必填项");
        }
    }
    private void checkBound(Integer value, int min, int max, String label)
    {
        require(value == null || (value >= min && value <= max),
            label + "须在当前红娘配置范围" + min + "-" + max + "内");
    }
}
