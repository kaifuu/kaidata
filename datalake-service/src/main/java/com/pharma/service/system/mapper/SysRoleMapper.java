package com.pharma.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pharma.service.system.entity.SysRole;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 角色 Mapper。menuIds/userIds 由关系表 Mapper 第二查询聚合。
 */
public interface SysRoleMapper extends BaseMapper<SysRole> {

    IPage<SysRole> selectRolePage(IPage<SysRole> page, @Param("q") Map<String, Object> q);
}
