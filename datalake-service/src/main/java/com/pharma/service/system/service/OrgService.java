package com.pharma.service.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.system.entity.SysOrg;
import com.pharma.service.system.entity.SysUser;
import com.pharma.service.system.mapper.SysOrgMapper;
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
 * 组织管理（树形）。列表=分页主查询 + 仅对当前页 id 聚合人数。
 */
@Service
public class OrgService {

    @Autowired
    private SysOrgMapper orgMapper;
    @Autowired
    private SysUserMapper userMapper;

    public PageResult<SysOrg> pageOrgs(long pageNum, long size, Map<String, Object> q) {
        Page<SysOrg> page = new Page<>(pageNum, size);
        IPage<SysOrg> result = orgMapper.selectOrgPage(page, q);
        List<SysOrg> records = result.getRecords();
        if (!records.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (SysOrg o : records) ids.add(o.getId());
            List<Map<String, Object>> rows = orgMapper.countUsersByOrgIds(ids);
            Map<Long, Long> cnt = new HashMap<>();
            for (Map<String, Object> r : rows) {
                cnt.put(((Number) r.get("orgId")).longValue(), ((Number) r.get("cnt")).longValue());
            }
            for (SysOrg o : records) o.setUserCount(cnt.getOrDefault(o.getId(), 0L));
        }
        return PageResult.of(result);
    }

    @Transactional
    public long createOrg(SysOrg o) {
        long id = System.currentTimeMillis();
        o.setId(id);
        if (o.getParentId() == null) o.setParentId(0L);
        if (o.getSort() == null) o.setSort(1);
        o.setCreateTime(LocalDateTime.now());
        orgMapper.insert(o);
        return id;
    }

    public void updateOrg(SysOrg o) {
        orgMapper.updateById(o);
    }

    @Transactional
    public void deleteOrg(long id) {
        long child = orgMapper.selectCount(new LambdaQueryWrapper<SysOrg>().eq(SysOrg::getParentId, id));
        long u = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getOrgId, id));
        if (child > 0 || u > 0) {
            throw new AccessDeniedException("该组织下存在子组织或用户，禁止删除");
        }
        orgMapper.deleteById(id);
    }
}
