package com.pharma.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pharma.service.system.entity.SysOrg;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 组织 Mapper。分页主查询 LEFT JOIN 租户名；人数聚合仅对当前页组织 id 做。
 */
public interface SysOrgMapper extends BaseMapper<SysOrg> {

    IPage<SysOrg> selectOrgPage(IPage<SysOrg> page, @Param("q") Map<String, Object> q);

    /** 按组织 id 批量统计人数（IN 当前页 id），返回 {org_id, cnt}。 */
    List<Map<String, Object>> countUsersByOrgIds(@Param("ids") List<Long> ids);
}
