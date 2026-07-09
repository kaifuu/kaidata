package com.pharma.service.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pharma.service.system.entity.SysAuditLog;
import com.pharma.service.system.mapper.SysAuditLogMapper;
import com.pharma.service.system.vo.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 审计日志（只读）。分页 + 检索（username/result/keyword）。
 */
@Service
public class AuditLogService {

    @Autowired
    private SysAuditLogMapper logMapper;

    public PageResult<SysAuditLog> pageLogs(long pageNum, long size, Map<String, Object> q) {
        return PageResult.of(logMapper.selectLogPage(new Page<>(pageNum, size), q));
    }
}
