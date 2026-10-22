package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.dto.DatingMatchSearch;
import com.ruoyi.system.domain.vo.DatingMatchCandidate;

public interface DatingMatchingMapper
{
    List<DatingMatchCandidate> search(DatingMatchSearch query);
}
