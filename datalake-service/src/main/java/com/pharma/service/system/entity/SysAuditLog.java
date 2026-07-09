package com.pharma.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 审计日志（meta.sys_audit_log，StarRocks DUPLICATE KEY 明细模型，只读）。
 */
@TableName("meta.sys_audit_log")
public class SysAuditLog {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String username;
    private String uri;
    private String method;
    private String params;
    private String result;
    private String ip;
    private LocalDateTime ts;
    private String action;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public LocalDateTime getTs() { return ts; }
    public void setTs(LocalDateTime ts) { this.ts = ts; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}
