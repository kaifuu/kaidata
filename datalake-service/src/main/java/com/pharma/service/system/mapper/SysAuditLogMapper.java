package com.pharma.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pharma.service.system.entity.SysAuditLog;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 审计日志 Mapper（meta.sys_audit_log，DUPLICATE KEY 明细模型，只读）。
 */
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {

    IPage<SysAuditLog> selectLogPage(IPage<SysAuditLog> page, @Param("q") Map<String, Object> q);
}
