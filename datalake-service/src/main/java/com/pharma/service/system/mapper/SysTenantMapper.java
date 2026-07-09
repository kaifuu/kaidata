package com.pharma.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pharma.service.system.entity.SysTenant;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 租户 Mapper。orgCount/userCount 仅对当前页租户 id 聚合。
 */
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    IPage<SysTenant> selectTenantPage(IPage<SysTenant> page, @Param("q") Map<String, Object> q);

    List<Map<String, Object>> countOrgsByTenantIds(@Param("ids") List<Long> ids);

    List<Map<String, Object>> countUsersByTenantIds(@Param("ids") List<Long> ids);
}
