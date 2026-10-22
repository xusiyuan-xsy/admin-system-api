package com.ruoyi.system.mapper;

import org.apache.ibatis.annotations.Param;

public interface DatingArchiveMapper
{
    Long lockCustomer(@Param("id") Long id);
    int accessible(@Param("id") Long id, @Param("owner") String owner);
    int hasHistory(@Param("id") Long id);
}
