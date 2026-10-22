package com.ruoyi.system.service;

import java.util.*;
import com.ruoyi.common.core.domain.entity.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DatingPhotoReferences;
import com.ruoyi.system.domain.dto.DatingMatchSearch;
import com.ruoyi.system.domain.vo.DatingMatchCandidate;
import com.ruoyi.system.mapper.DatingMatchingMapper;
import org.springframework.stereotype.Service;

@Service
public class DatingMatchingService
{
    private final DatingMatchingMapper mapper;
    private final DatingArchiveAccess access;

    public DatingMatchingService(DatingMatchingMapper mapper, DatingArchiveAccess access)
    {
        this.mapper = mapper;
        this.access = access;
    }

    public List<DatingMatchCandidate> search(DatingMatchSearch query)
    {
        DatingRequirementRules.validate(query);
        option(query.getMatchMode(), Set.of("ONE_WAY", "TWO_WAY"));
        option(query.getCandidateScope(), Set.of("ELIGIBLE", "ALL"));
        option(query.getHistoryFilter(), Set.of("ALL", "NEW"));
        option(query.getSortBy(), Set.of("MATCH", "RECENT"));
        access.scope(query);
        // Keep authorization and filtering in the paginated SQL, not a post-page filter.
        var rows = mapper.search(query);
        for (var row : rows)
        {
            if (row.getPhotos() != null)
                row.setPhotos(row.getPhotos().stream().filter(ref -> DatingPhotoReferences.belongsTo(ref, row.getId())).toList());
            var forward = compare(query, row);
            row.setMatchedConditions(forward.matched());
            var reverse = compare(row.getRequirement(), row.getSourceArchive());
            row.setReverseConflicts(reverse.conflicts());
            var pending = new ArrayList<String>();
            forward.pending().forEach(item -> pending.add("本方：" + item));
            reverse.pending().forEach(item -> pending.add("对方：" + item));
            row.setPendingConditions(pending);
            row.setReciprocalStatus(row.isReverseMatched() ? "MATCH"
                : reverse.conflicts().isEmpty() ? "UNKNOWN" : "MISMATCH");
        }
        return rows;
    }

    private static void option(String value, Set<String> allowed)
    {
        if (value == null || !allowed.contains(value)) throw new ServiceException("匹配查询选项不正确");
    }

    record Comparison(List<String> matched, List<String> conflicts, List<String> pending) {}

    private Comparison compare(SysPayingRequirement r, SysPayingUser u)
    {
        var matched = new ArrayList<String>();
        var conflicts = new ArrayList<String>();
        var pending = new ArrayList<String>();
        if (r == null)
        {
            pending.add("对方尚未填写择偶需求");
            return new Comparison(matched, conflicts, pending);
        }
        try { DatingRequirementRules.validate(r); }
        catch (ServiceException invalid)
        {
            pending.add("需求数据不完整或不合法，请先核对");
            return new Comparison(matched, conflicts, pending);
        }
        if (r.getAgeMin() != null || r.getAgeMax() != null)
            condition("出生年份", u.getBirthYear(), between(u.getBirthYear(), r.getAgeMin(), r.getAgeMax()), matched, conflicts, pending);
        if (r.getHeightMin() != null || r.getHeightMax() != null)
            condition("身高", u.getHeight(), between(u.getHeight(), r.getHeightMin(), r.getHeightMax()), matched, conflicts, pending);
        if (filled(r.getEducation()) && !"0".equals(r.getEducation()))
            condition("学历", number(u.getEducation(), 8), number(u.getEducation(), 8) != null
                && number(u.getEducation(), 8) >= Integer.parseInt(r.getEducation()), matched, conflicts, pending);
        if ("0".equals(r.getAcceptLongDist()))
            condition("现居城市", u.getCurrentCity(), region(u.getCurrentCity(), r.getCityRequire()), matched, conflicts, pending);
        if (filled(r.getHometownRequire()))
            condition("家乡", u.getHometown(), region(u.getHometown(), r.getHometownRequire()), matched, conflicts, pending);
        if (filled(r.getSmokeHabit()))
            condition("吸烟习惯", number(u.getSmokeHabit(), 2), number(u.getSmokeHabit(), 2) != null
                && number(u.getSmokeHabit(), 2) <= Integer.parseInt(r.getSmokeHabit()), matched, conflicts, pending);
        if (filled(r.getDrinkHabit()))
            condition("饮酒习惯", number(u.getDrinkHabit(), 2), number(u.getDrinkHabit(), 2) != null
                && number(u.getDrinkHabit(), 2) <= Integer.parseInt(r.getDrinkHabit()), matched, conflicts, pending);
        if ("1".equals(r.getHasCar())) condition("有车", binary(u.getHasCar()), "1".equals(u.getHasCar()), matched, conflicts, pending);
        if ("1".equals(r.getHasHouse())) condition("有房", binary(u.getHasHouse()), "1".equals(u.getHasHouse()), matched, conflicts, pending);
        if ("1".equals(r.getNoTattoo())) condition("无纹身", binary(u.getHasTattoo()), "0".equals(u.getHasTattoo()), matched, conflicts, pending);
        if (filled(r.getOtherRequire())) pending.add("其他要求需人工核实");
        return new Comparison(matched, conflicts, pending);
    }

    private static void condition(String name, Object value, boolean ok, List<String> matched, List<String> conflicts, List<String> pending)
    {
        if (value == null || value instanceof String s && s.isBlank()) pending.add(name + "资料待补充");
        else if (ok) matched.add(name);
        else conflicts.add(name + "不符合对方要求");
    }
    private static boolean filled(String s) { return s != null && !s.isBlank(); }
    private static boolean region(String actual, String required)
    { return filled(actual) && filled(required) && (actual.equals(required) || actual.startsWith(required + "/")); }
    private static boolean between(Integer v, Integer min, Integer max)
    { return v != null && (min == null || v >= min) && (max == null || v <= max); }
    private static String binary(String value) { return "0".equals(value) || "1".equals(value) ? value : null; }
    private static Integer number(String value, int max)
    {
        if (value == null || !value.matches("[0-8]")) return null;
        int result = Integer.parseInt(value);
        return result <= max && (max != 8 || result > 0) ? result : null;
    }
}
