package com.pharma.service.access.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 按列元数据生成 StarRocks 建表 DDL。
 * <p>
 * 关键约束：StarRocks 的 PRIMARY KEY / DUPLICATE KEY 指定的键列必须是列定义的前缀。
 * 故用 {@link #plan} 把键列重排到列定义首位；调用方 INSERT 时须用同样的重排顺序写值。
 * <ul>
 *   <li>FULL（全量）→ DUPLICATE KEY(首列) + truncate+insert</li>
 *   <li>INCREMENTAL（增量）→ PRIMARY KEY(业务键) 实现按主键去重 upsert</li>
 * </ul>
 */
public final class StarRocksDdlBuilder {

    public static class ColumnDef {
        public final String name;
        public final String type; // 已映射的 StarRocks 类型
        public ColumnDef(String name, String type) { this.name = name; this.type = type; }
    }

    /** 键列重排计划：实际键列名 + 重排后的列顺序（键列在前）。 */
    public static class KeyPlan {
        public final String key;
        public final List<ColumnDef> ordered;
        public KeyPlan(String key, List<ColumnDef> ordered) { this.key = key; this.ordered = ordered; }
    }

    private StarRocksDdlBuilder() {}

    /** 计算键列并把键列重排到列前缀（StarRocks 硬约束）。 */
    public static KeyPlan plan(List<ColumnDef> cols, String keyCol) {
        if (cols == null || cols.isEmpty()) throw new IllegalArgumentException("无列定义");
        String key = (keyCol != null && !keyCol.isEmpty()) ? keyCol : cols.get(0).name;
        boolean keyExists = false;
        for (ColumnDef c : cols) if (c.name.equalsIgnoreCase(key)) { keyExists = true; break; }
        if (!keyExists) key = cols.get(0).name;
        List<ColumnDef> ordered = new ArrayList<>();
        for (ColumnDef c : cols) if (c.name.equalsIgnoreCase(key)) ordered.add(c);
        for (ColumnDef c : cols) if (!c.name.equalsIgnoreCase(key)) ordered.add(c);
        return new KeyPlan(key, ordered);
    }

    public static String build(String db, String table, List<ColumnDef> cols, String keyCol, boolean primaryKeyModel) {
        ident(db);
        ident(table);
        KeyPlan p = plan(cols, keyCol);
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS `").append(db).append("`.`").append(table).append("` (");
        for (int i = 0; i < p.ordered.size(); i++) {
            if (i > 0) sb.append(", ");
            ColumnDef c = p.ordered.get(i);
            ident(c.name);
            sb.append("`").append(c.name).append("` ").append(c.type);
        }
        sb.append(") ").append(primaryKeyModel ? "PRIMARY KEY" : "DUPLICATE KEY")
                .append("(`").append(p.key).append("`)")
                .append(" DISTRIBUTED BY HASH(`").append(p.key).append("`) BUCKETS 3")
                .append(" PROPERTIES(\"replication_num\"=\"1\")");
        return sb.toString();
    }

    /** 校验标识符仅含字母数字下划线，防注入。 */
    public static void ident(String s) {
        if (s == null || !s.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法标识符: " + s);
        }
    }
}
