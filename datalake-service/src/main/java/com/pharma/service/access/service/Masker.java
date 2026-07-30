package com.pharma.service.access.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据服务取数结果动态脱敏：按 表.列 查 sec_mask_rel/sec_mask_rule，
 * 对返回行按 pattern→replacement（Java 正则）就地脱敏。仅用于服务取数（开放 API/数据服务调用），
 * 不影响管理员数据开发调试（DevScriptExecutor 直接走 doRun 不经此处）。
 */
@Component
public class Masker {

    @Autowired private JdbcTemplate jdbc;

    /**
     * 对结果行就地脱敏。
     *
     * @param rows     List&lt;Map&lt;列名, 值&gt;&gt;
     * @param tableRef 与 sec_mask_rel.source_table 一致的表引用（约定 schema.table_name）
     */
    public void apply(List<Map<String, Object>> rows, String tableRef) {
        if (rows == null || rows.isEmpty() || tableRef == null || tableRef.isEmpty()) return;
        List<Map<String, Object>> rules;
        try {
            rules = jdbc.queryForList(
                    "SELECT r.source_column, m.pattern, m.replacement FROM meta.sec_mask_rel r " +
                            "JOIN meta.sec_mask_rule m ON m.id=r.rule_id WHERE r.source_table=?", tableRef);
        } catch (Exception e) {
            return;
        }
        if (rules.isEmpty()) return;
        // 编译该表的列→规则（正则一次编译多次复用）
        Map<String, Pattern> compiled = new HashMap<>();
        Map<String, String> replacement = new HashMap<>();
        for (Map<String, Object> r : rules) {
            String col = String.valueOf(r.get("source_column"));
            String pattern = String.valueOf(r.get("pattern"));
            try {
                compiled.put(col, Pattern.compile(pattern));
                replacement.put(col, String.valueOf(r.get("replacement")));
            } catch (Exception ignored) {}
        }
        if (compiled.isEmpty()) return;
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Pattern> e : compiled.entrySet()) {
                Object v = row.get(e.getKey());
                if (v == null) continue;
                String s = String.valueOf(v);
                if (s.isEmpty()) continue;
                row.put(e.getKey(), e.getValue().matcher(s).replaceAll(replacement.get(e.getKey())));
            }
        }
    }
}
