package com.pharma.service.system.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 用户-角色关系（meta.sys_user_role，复合主键）。
 * <p>
 * 纯 Mapper（不继承 BaseMapper）：聚合查询返回 Map（{user_id, code} / {role_id, user_id}），
 * 授权用 foreach 批量插入，避免 N 次往返。
 */
public interface UserRoleMapper {

    /** 用户列表聚合：按用户 id 取角色码，返回 {user_id, code}。 */
    List<Map<String, Object>> findRoleCodesByUserIds(@Param("userIds") List<Long> userIds);

    /** 角色列表聚合：按角色 id 取成员用户 id，返回 {role_id, user_id}。 */
    List<Map<String, Object>> findUserIdsByRoleIds(@Param("roleIds") List<Long> roleIds);

    int deleteByUser(@Param("userId") Long userId);

    int deleteByRole(@Param("roleId") Long roleId);

    /** grantUsers：批量插入该角色的成员。 */
    int batchInsertForRole(@Param("roleId") Long roleId, @Param("userIds") List<Long> userIds);
}
