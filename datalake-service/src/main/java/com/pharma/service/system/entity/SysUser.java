package com.pharma.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户（meta.sys_user）。
 * <p>
 * 列表查询时 {@code tenantName}/{@code orgName}/{@code roles} 为非持久展示字段：
 * tenantName/orgName 由 LEFT JOIN 富化，roles 由第二查询（IN 当前页 id）聚合回填。
 * password 仅用于写入，列表 SELECT 不取此列。
 */
@TableName("meta.sys_user")
public class SysUser {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String username;
    private String password;
    private String name;
    private String status;            // NORMAL / DISABLED
    private Long tenantId;
    private Long orgId;
    private LocalDateTime createTime;

    @TableField(exist = false)
    private String tenantName;
    @TableField(exist = false)
    private String orgName;
    @TableField(exist = false)
    private List<String> roles;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getOrgId() { return orgId; }
    public void setOrgId(Long orgId) { this.orgId = orgId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
}
