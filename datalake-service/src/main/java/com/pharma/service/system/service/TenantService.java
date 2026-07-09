package com.pharma.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.system.entity.SysOrg;
import com.pharma.service.system.entity.SysTenant;
import com.pharma.service.system.entity.SysUser;
import com.pharma.service.system.mapper.SysOrgMapper;
import com.pharma.service.system.mapper.SysTenantMapper;
import com.pharma.service.system.mapper.SysUserMapper;
import com.pharma.service.system.vo.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户管理。列表=分页主查询 + 仅对当前页 id 聚合组织数/用户数。
 */
@Service
public class TenantService {

    @Autowired
    private SysTenantMapper tenantMapper;
    @Autowired
    private SysOrgMapper orgMapper;
    @Autowired
    private SysUserMapper userMapper;

    public PageResult<SysTenant> pageTenants(long pageNum, long size, Map<String, Object> q) {
        Page<SysTenant> page = new Page<>(pageNum, size);
        IPage<SysTenant> result = tenantMapper.selectTenantPage(page, q);
        List<SysTenant> records = result.getRecords();
        if (!records.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (SysTenant t : records) ids.add(t.getId());
            Map<Long, Long> orgCnt = toLongMap(tenantMapper.countOrgsByTenantIds(ids), "tenantId");
            Map<Long, Long> userCnt = toLongMap(tenantMapper.countUsersByTenantIds(ids), "tenantId");
            for (SysTenant t : records) {
                t.setOrgCount(orgCnt.getOrDefault(t.getId(), 0L));
                t.setUserCount(userCnt.getOrDefault(t.getId(), 0L));
            }
        }
        return PageResult.of(result);
    }

    private static Map<Long, Long> toLongMap(List<Map<String, Object>> rows, String idKey) {
        Map<Long, Long> m = new HashMap<>();
        for (Map<String, Object> r : rows) {
            m.put(((Number) r.get(idKey)).longValue(), ((Number) r.get("cnt")).longValue());
        }
        return m;
    }

    @Transactional
    public long createTenant(SysTenant t) {
        long id = System.currentTimeMillis();
        t.setId(id);
        if (t.getStatus() == null) t.setStatus("NORMAL");
        t.setCreateTime(LocalDateTime.now());
        tenantMapper.insert(t);
        return id;
    }

    public void updateTenant(SysTenant t) {
        tenantMapper.updateById(t);
    }

    @Transactional
    public void deleteTenant(long id) {
        long u = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getTenantId, id));
        if (u > 0) throw new AccessDeniedException("该租户下存在用户，禁止删除");
        orgMapper.delete(new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getTenantId, id));
        tenantMapper.deleteById(id);
    }
}
