package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.DatingMatchmakerConfig;

public interface DatingMatchmakerConfigMapper
{
    List<DatingMatchmakerConfig> selectList(@Param("name") String name);
    DatingMatchmakerConfig selectActive();
    DatingMatchmakerConfig selectById(Long id);
    Long lockState();
    int count();
    int insert(DatingMatchmakerConfig config);
    int update(DatingMatchmakerConfig config);
    int delete(Long id);
    int setDefault(@Param("id") Long id, @Param("actor") String actor);
}
