package com.pharma.service.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pharma.service.system.entity.SysMenu;

/**
 * 菜单 Mapper。树形列表不分页，走 BaseMapper + LambdaQueryWrapper（service 内组装树）。
 */
public interface SysMenuMapper extends BaseMapper<SysMenu> {
}
