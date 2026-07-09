package com.pharma.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 租户（meta.sys_tenant）。orgCount/userCount 由第二查询（IN 当前页租户 id）聚合回填。
 */
@TableName("meta.sys_tenant")
public class SysTenant {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String code;
    private String name;
    private String status;            // NORMAL / DISABLED
    private LocalDateTime createTime;

    @TableField(exist = false)
    private Long orgCount;
    @TableField(exist = false)
    private Long userCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Long getOrgCount() { return orgCount; }
    public void setOrgCount(Long orgCount) { this.orgCount = orgCount; }
    public Long getUserCount() { return userCount; }
    public void setUserCount(Long userCount) { this.userCount = userCount; }
}
