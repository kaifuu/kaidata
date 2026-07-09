package com.pharma.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 组织（meta.sys_org），树形（parent_id=0 为顶级）。
 * tenantName 由 LEFT JOIN 富化，userCount 由第二查询（IN 当前页组织 id）聚合回填。
 */
@TableName("meta.sys_org")
public class SysOrg {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private Long parentId;
    private String code;
    private String name;
    private Integer sort;
    private LocalDateTime createTime;

    @TableField(exist = false)
    private String tenantName;
    @TableField(exist = false)
    private Long userCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public String getTenantName() { return tenantName; }
    public void setTenantName(String tenantName) { this.tenantName = tenantName; }
    public Long getUserCount() { return userCount; }
    public void setUserCount(Long userCount) { this.userCount = userCount; }
}
