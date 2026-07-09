package com.pharma.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.system.entity.SysMenu;
import com.pharma.service.system.mapper.RoleMenuMapper;
import com.pharma.service.system.mapper.SysMenuMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单管理（树形，不分页）。返回完整树，前端按关键字过滤匹配节点。
 */
@Service
public class MenuService {

    @Autowired
    private SysMenuMapper menuMapper;
    @Autowired
    private RoleMenuMapper roleMenuMapper;

    /** 按 sort、id 排序后 Java 层按 parentId 组装父子树；parent_id=0 为根。 */
    public List<SysMenu> listTree() {
        List<SysMenu> all = menuMapper.selectList(
                new LambdaQueryWrapper<SysMenu>()
                        .orderByAsc(SysMenu::getSort)
                        .orderByAsc(SysMenu::getId));
        Map<Long, List<SysMenu>> byParent = new LinkedHashMap<>();
        for (SysMenu m : all) {
            long pid = m.getParentId() == null ? 0L : m.getParentId();
            byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(m);
        }
        for (SysMenu m : all) {
            m.setChildren(byParent.get(m.getId()));   // 无子节点 → null（叶子）
        }
        return byParent.getOrDefault(0L, List.of());
    }

    @Transactional
    public long createMenu(SysMenu m) {
        long id = System.currentTimeMillis();
        m.setId(id);
        if (m.getParentId() == null) m.setParentId(0L);
        if (m.getIcon() == null || m.getIcon().isEmpty()) m.setIcon("Menu");
        if (m.getType() == null || m.getType().isEmpty()) m.setType("MENU");
        if (m.getSort() == null) m.setSort(99);
        if (m.getStatus() == null || m.getStatus().isEmpty()) m.setStatus("ENABLED");
        menuMapper.insert(m);
        return id;
    }

    public void updateMenu(SysMenu m) {
        menuMapper.updateById(m);
    }

    /** 菜单启停切换，返回新状态。 */
    public String toggleMenu(long id) {
        SysMenu m = menuMapper.selectById(id);
        if (m == null) return null;
        String next = "DISABLED".equals(m.getStatus()) ? "ENABLED" : "DISABLED";
        m.setStatus(next);
        menuMapper.updateById(m);
        return next;
    }

    @Transactional
    public void deleteMenu(long id) {
        long child = menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, id));
        if (child > 0) throw new AccessDeniedException("存在子菜单，禁止删除");
        roleMenuMapper.deleteByMenu(id);
        menuMapper.deleteById(id);
    }
}
