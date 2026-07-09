package com.pharma.service.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.security.PasswordUtil;
import com.pharma.service.system.entity.SysUser;
import com.pharma.service.system.mapper.SysUserMapper;
import com.pharma.service.system.mapper.UserRoleMapper;
import com.pharma.service.system.vo.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理。列表=分页主查询 + 仅对当前页 id 聚合角色码（两查询规则）。
 */
@Service
public class UserService {

    /** 受保护账号，禁止删除（保证三员演示账号常在）。 */
    private static final Set<String> PROTECTED = Set.of("admin", "sysadmin", "secadmin", "audadmin");

    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;

    public PageResult<SysUser> pageUsers(long pageNum, long size, Map<String, Object> q) {
        Page<SysUser> page = new Page<>(pageNum, size);
        IPage<SysUser> result = userMapper.selectUserPage(page, q);
        List<SysUser> records = result.getRecords();
        if (!records.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (SysUser u : records) ids.add(u.getId());
            List<Map<String, Object>> rows = userRoleMapper.findRoleCodesByUserIds(ids);
            Map<Long, List<String>> roleMap = new HashMap<>();
            for (Map<String, Object> r : rows) {
                Long uid = ((Number) r.get("userId")).longValue();
                roleMap.computeIfAbsent(uid, k -> new ArrayList<>()).add(String.valueOf(r.get("code")));
            }
            for (SysUser u : records) u.setRoles(roleMap.getOrDefault(u.getId(), List.of()));
        }
        return PageResult.of(result);
    }

    @Transactional
    public long createUser(SysUser u) {
        long id = System.currentTimeMillis();
        u.setId(id);
        u.setPassword(PasswordUtil.hash(u.getPassword() == null ? "" : u.getPassword()));
        if (u.getStatus() == null) u.setStatus("NORMAL");
        u.setCreateTime(LocalDateTime.now());
        userMapper.insert(u);
        return id;
    }

    public void updateUser(SysUser u) {
        String pwd = u.getPassword();
        if (pwd != null && !pwd.isEmpty()) {
            u.setPassword(PasswordUtil.hash(pwd));      // 非空才更新密码
        } else {
            u.setPassword(null);                        // MP 默认 NOT_NULL 策略忽略，不更新密码
        }
        userMapper.updateById(u);
    }

    @Transactional
    public void deleteUser(long id) {
        SysUser u = userMapper.selectById(id);
        if (u != null && PROTECTED.contains(u.getUsername())) {
            throw new AccessDeniedException("受保护账号禁止删除：" + u.getUsername());
        }
        userRoleMapper.deleteByUser(id);                // 先解绑角色，再删用户
        userMapper.deleteById(id);
    }
}
