package com.pharma.service.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.system.entity.SysRole;
import com.pharma.service.system.mapper.RoleMenuMapper;
import com.pharma.service.system.mapper.SysRoleMapper;
import com.pharma.service.system.mapper.UserRoleMapper;
import com.pharma.service.system.vo.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色管理。列表=分页主查询 + 仅对当前页 id 聚合 menuIds/userIds。
 * 内置三员 id<=3 受保护；授权用整体替换（delete + foreach 批量 insert）。
 */
@Service
public class RoleService {

    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private RoleMenuMapper roleMenuMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;

    public PageResult<SysRole> pageRoles(long pageNum, long size, Map<String, Object> q) {
        Page<SysRole> page = new Page<>(pageNum, size);
        IPage<SysRole> result = roleMapper.selectRolePage(page, q);
        List<SysRole> records = result.getRecords();
        if (!records.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (SysRole r : records) ids.add(r.getId());
            Map<Long, List<Long>> menuMap = toLongListMap(roleMenuMapper.findMenuIdsByRoleIds(ids), "roleId", "menuId");
            Map<Long, List<Long>> userMap = toLongListMap(userRoleMapper.findUserIdsByRoleIds(ids), "roleId", "userId");
            for (SysRole r : records) {
                r.setMenuIds(menuMap.getOrDefault(r.getId(), List.of()));
                r.setUserIds(userMap.getOrDefault(r.getId(), List.of()));
            }
        }
        return PageResult.of(result);
    }

    private static Map<Long, List<Long>> toLongListMap(List<Map<String, Object>> rows, String k, String v) {
        Map<Long, List<Long>> m = new HashMap<>();
        for (Map<String, Object> r : rows) {
            m.computeIfAbsent(((Number) r.get(k)).longValue(), x -> new ArrayList<>())
                    .add(((Number) r.get(v)).longValue());
        }
        return m;
    }

    @Transactional
    public long createRole(SysRole r) {
        long id = System.currentTimeMillis();
        r.setId(id);
        roleMapper.insert(r);
        return id;
    }

    public void updateRole(SysRole r) {
        roleMapper.updateById(r);
    }

    @Transactional
    public void deleteRole(long id) {
        if (id <= 3) throw new AccessDeniedException("内置三员角色禁止删除");
        roleMenuMapper.deleteByRole(id);
        userRoleMapper.deleteByRole(id);
        roleMapper.deleteById(id);
    }

    /** 角色-菜单授权（整体替换）。 */
    @Transactional
    public void grantMenus(long roleId, List<Long> menuIds) {
        roleMenuMapper.deleteByRole(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            roleMenuMapper.batchInsertForRole(roleId, menuIds);
        }
    }

    /** 角色-用户授权（整体替换：设置该角色的成员）。内置三员禁止调整。 */
    @Transactional
    public void grantUsers(long roleId, List<Long> userIds) {
        if (roleId <= 3) throw new AccessDeniedException("内置三员角色成员由系统维护，禁止在此调整");
        userRoleMapper.deleteByRole(roleId);
        if (userIds != null && !userIds.isEmpty()) {
            userRoleMapper.batchInsertForRole(roleId, userIds);
        }
    }
}
