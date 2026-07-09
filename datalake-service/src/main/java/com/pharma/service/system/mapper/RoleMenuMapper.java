package com.pharma.service.system.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 角色-菜单关系（meta.sys_role_menu，复合主键）。纯 Mapper（不继承 BaseMapper）。
 */
public interface RoleMenuMapper {

    /** 角色列表聚合：按角色 id 取已授权菜单 id，返回 {role_id, menu_id}。 */
    List<Map<String, Object>> findMenuIdsByRoleIds(@Param("roleIds") List<Long> roleIds);

    int deleteByRole(@Param("roleId") Long roleId);

    int deleteByMenu(@Param("menuId") Long menuId);

    /** grantMenus：批量插入该角色的菜单。 */
    int batchInsertForRole(@Param("roleId") Long roleId, @Param("menuIds") List<Long> menuIds);
}
