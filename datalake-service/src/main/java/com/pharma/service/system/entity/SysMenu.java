package com.pharma.service.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.List;

/**
 * 菜单（meta.sys_menu），树形（parent_id=0 为顶级）。
 * children 为非持久字段，由 service 按 parentId 在 Java 层构建树。
 */
@TableName("meta.sys_menu")
public class SysMenu {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private String icon;
    private String perm;
    private String type;              // CATALOG / MENU
    private Integer sort;
    private String status;            // ENABLED / DISABLED

    @TableField(exist = false)
    private List<SysMenu> children;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }
    public String getPerm() { return perm; }
    public void setPerm(String perm) { this.perm = perm; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getSort() { return sort; }
    public void setSort(Integer sort) { this.sort = sort; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<SysMenu> getChildren() { return children; }
    public void setChildren(List<SysMenu> children) { this.children = children; }
}
