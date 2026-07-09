package com.pharma.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.List;

/**
 * 角色（meta.sys_role）。内置三员 id=1/2/3 受保护，禁止删除。
 * menuIds/userIds 由第二查询（IN 当前页角色 id）聚合回填。
 */
@TableName("meta.sys_role")
public class SysRole {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String code;
    private String name;

    @TableField(exist = false)
    private List<Long> menuIds;
    @TableField(exist = false)
    private List<Long> userIds;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Long> getMenuIds() { return menuIds; }
    public void setMenuIds(List<Long> menuIds) { this.menuIds = menuIds; }
    public List<Long> getUserIds() { return userIds; }
    public void setUserIds(List<Long> userIds) { this.userIds = userIds; }
}
