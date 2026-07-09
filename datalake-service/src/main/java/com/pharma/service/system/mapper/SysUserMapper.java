package com.pharma.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pharma.service.system.entity.SysUser;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 用户 Mapper。列表分页走自定义 XML（LEFT JOIN 租户/组织名），角色码聚合由 UserRoleMapper 第二查询完成。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 分页查用户（含 tenant_name/org_name）。MP 据传入 page 自动注入 LIMIT 与 count。 */
    IPage<SysUser> selectUserPage(IPage<SysUser> page, @Param("q") Map<String, Object> q);
}
