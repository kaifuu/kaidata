package com.pharma.service.access.profile;

import com.pharma.service.access.util.StarRocksDdlBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自动化建模：首次探查时把外部表结构建到内部分层数据源（StarRocks 目标库）。
 * <p>仅当目标库无同名表时建（避免覆盖已存在表）。列类型经 TypeMapper 映射为 StarRocks 类型。
 */
@Component
public class AutoModler {

    @Autowired private JdbcTemplate jdbc;

    /** 目标库无同名表则建，返回是否实际创建。 */
    public boolean createIfAbsent(String db, String table, List<StarRocksDdlBuilder.ColumnDef> cols, String bizKey) {
        StarRocksDdlBuilder.ident(db);
        StarRocksDdlBuilder.ident(table);
        if (tableExists(db, table)) return false;
        jdbc.execute(StarRocksDdlBuilder.build(db, table, cols, bizKey, false));
        return true;
    }

    public boolean tableExists(String db, String table) {
        StarRocksDdlBuilder.ident(db);
        StarRocksDdlBuilder.ident(table);
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                    Long.class, db, table);
            return c != null && c > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
