package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据标签 [SYS_ADMIN]：标签定义 + 打标关系（表/字段打标）。
 * <p>P2 升级：① 规则打标（正则匹配表名/列名/列注释/数据类型 → 批量自动打标，幂等）；
 * ② 标签血缘继承（源表标签沿 gov_meta_lineage_edge 向下游表传播）。
 */
@RestController
@RequestMapping("/api/data-gov/tag")
@CrossOrigin(origins = "*")
public class DataTagController {

    @Autowired private JdbcTemplate jdbc;

    // ===== 标签 =====
    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) String category) {
        Authz.require(Authz.SYS_ADMIN);
        if (category == null || category.isEmpty()) return jdbc.queryForList("SELECT id, name, category, color, description FROM meta.gov_tag ORDER BY id");
        return jdbc.queryForList("SELECT id, name, category, color, description FROM meta.gov_tag WHERE category=? ORDER BY id", category);
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_tag(id, name, category, color, description) VALUES (?,?,?,?,?)",
                id, str(b.get("name")), str(b.getOrDefault("category", "分类")), str(b.getOrDefault("color", "")), str(b.get("description")));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_tag SET name=?, category=?, color=?, description=? WHERE id=?",
                str(b.get("name")), str(b.getOrDefault("category", "分类")), str(b.get("color")), str(b.get("description")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_tag_relation WHERE tag_id=?", id);
        jdbc.update("DELETE FROM meta.gov_tag WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 打标关系 =====
    @GetMapping("/relation")
    public List<Map<String, Object>> listRelation(@RequestParam(required = false) String targetTable,
                                                  @RequestParam(required = false) String targetColumn) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT r.id, r.tag_id, t.name AS tag_name, t.color, r.target_type, r.target_db, r.target_table, r.target_column FROM meta.gov_tag_relation r LEFT JOIN meta.gov_tag t ON t.id=r.tag_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (targetTable != null && !targetTable.isEmpty()) { sql.append(" AND r.target_table=?"); args.add(targetTable); }
        if (targetColumn != null && !targetColumn.isEmpty()) { sql.append(" AND r.target_column=?"); args.add(targetColumn); }
        sql.append(" ORDER BY r.id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
    @PostMapping("/relation")
    public Map<String, Object> bindRelation(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("INSERT INTO meta.gov_tag_relation(id, tag_id, target_type, target_db, target_table, target_column) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), lng(b.get("tag_id")), str(b.getOrDefault("target_type", "table")),
                str(b.get("target_db")), str(b.get("target_table")), str(b.get("target_column")));
        return Map.of("success", true);
    }
    @DeleteMapping("/relation")
    public Map<String, Object> unbindRelation(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_tag_relation WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 规则打标 =====
    private final ObjectMapper json = new ObjectMapper();

    @GetMapping("/rule")
    public List<Map<String, Object>> listRules() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT r.id, r.tag_id, t.name AS tag_name, t.color, r.match_type, r.pattern, r.remark, r.create_time " +
                "FROM meta.gov_tag_rule r LEFT JOIN meta.gov_tag t ON t.id=r.tag_id ORDER BY r.id DESC");
    }

    @PostMapping("/rule")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String pattern = str(b.get("pattern"));
        if (pattern.isEmpty()) throw new IllegalArgumentException("匹配正则必填");
        try { Pattern.compile(pattern); } catch (Exception e) { throw new IllegalArgumentException("正则非法: " + e.getMessage()); }
        if (lng(b.get("tag_id")) == 0) throw new IllegalArgumentException("请选择标签");
        jdbc.update("INSERT INTO meta.gov_tag_rule(id, tag_id, match_type, pattern, remark, create_time) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), lng(b.get("tag_id")), strOrDefault(b.get("match_type"), "TABLE_NAME"),
                pattern, str(b.get("remark")), new Date());
        return Map.of("success", true);
    }

    @DeleteMapping("/rule")
    public Map<String, Object> deleteRule(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_tag_rule WHERE id=?", id);
        return Map.of("success", true);
    }

    /**
     * 执行规则打标：扫 gov_meta_table（表名/注释 + columns_json 列名/注释/类型），命中即建打标关系。
     * 幂等：已有同 tag+target 关系则跳过。返回每规则 命中数/新建数。
     */
    @PostMapping("/rule/apply")
    public Map<String, Object> applyRules() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT id, tag_id, match_type, pattern FROM meta.gov_tag_rule");
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT schema_name, table_name, comment, columns_json FROM meta.gov_meta_table LIMIT 5000");
        Set<String> exist = new HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT tag_id, target_table, target_column FROM meta.gov_tag_relation")) {
            exist.add(lng(r.get("tag_id")) + "|" + str(r.get("target_table")) + "|" + str(r.get("target_column")));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int totalNew = 0;
        for (Map<String, Object> rule : rules) {
            long tagId = lng(rule.get("tag_id"));
            String type = str(rule.get("match_type"));
            Pattern p;
            try { p = Pattern.compile(str(rule.get("pattern")), Pattern.CASE_INSENSITIVE); }
            catch (Exception e) { continue; }
            int hit = 0, created = 0;
            for (Map<String, Object> t : tables) {
                String table = str(t.get("table_name"));
                String schema = str(t.get("schema_name"));
                if ("TABLE_NAME".equals(type)) {
                    if (!p.matcher(table).find()) continue;
                    hit++;
                    if (exist.add(tagId + "|" + table + "|")) {
                        jdbc.update("INSERT INTO meta.gov_tag_relation(id, tag_id, target_type, target_db, target_table, target_column) VALUES (?,?,?,?,?,?)",
                                System.currentTimeMillis() + (long) (Math.random() * 1000), tagId, "table", schema, table, "");
                        created++;
                    }
                } else {
                    for (var col : parseColumns(str(t.get("columns_json")))) {
                        String v = "COLUMN_NAME".equals(type) ? str(col.get("name"))
                                : "COLUMN_COMMENT".equals(type) ? str(col.get("comment"))
                                : str(col.get("type"));   // DATA_TYPE
                        if (!p.matcher(v).find()) continue;
                        hit++;
                        if (exist.add(tagId + "|" + table + "|" + str(col.get("name")))) {
                            jdbc.update("INSERT INTO meta.gov_tag_relation(id, tag_id, target_type, target_db, target_table, target_column) VALUES (?,?,?,?,?,?)",
                                    System.currentTimeMillis() + (long) (Math.random() * 1000), tagId, "column", schema, table, str(col.get("name")));
                            created++;
                        }
                    }
                }
            }
            totalNew += created;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ruleId", lng(rule.get("id")));
            r.put("tagId", tagId);
            r.put("matchType", type);
            r.put("hit", hit);
            r.put("created", created);
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("rules", results.size());
        out.put("created", totalNew);
        out.put("detail", results);
        return out;
    }

    /**
     * 标签血缘继承：源表的表级标签沿血缘边向下游表传播（如 ods 侧打的"敏感"标签带到 dwd/dws）。
     * 多跳传播（BFS 到不动点），幂等跳过已有关系。返回新建数。
     */
    @PostMapping("/inherit")
    public Map<String, Object> inherit() {
        Authz.require(Authz.SYS_ADMIN);
        // 表级血缘 src→tgt
        Map<String, Set<String>> downstream = new HashMap<>();
        for (Map<String, Object> e : jdbc.queryForList(
                "SELECT src_table, tgt_table FROM meta.gov_meta_lineage_edge WHERE tgt_schema<>'EXTERNAL' AND src_table<>'' AND tgt_table<>''")) {
            downstream.computeIfAbsent(str(e.get("src_table")), k -> new HashSet<>()).add(str(e.get("tgt_table")));
        }
        // 表级标签关系 tag → [tables]
        Map<Long, Set<String>> tagTables = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT tag_id, target_table FROM meta.gov_tag_relation WHERE target_type='table' AND target_table<>''")) {
            tagTables.computeIfAbsent(lng(r.get("tag_id")), k -> new HashSet<>()).add(str(r.get("target_table")));
        }
        Set<String> exist = new HashSet<>();
        Map<String, Set<String>> tableTags = new HashMap<>();   // 反查索引：table → tagId 字符串集（含新增，供多跳）
        for (Map.Entry<Long, Set<String>> en : tagTables.entrySet()) {
            for (String tb : en.getValue()) {
                exist.add(en.getKey() + "|" + tb);
                tableTags.computeIfAbsent(tb, k -> new HashSet<>()).add(String.valueOf(en.getKey()));
            }
        }
        int created = 0;
        boolean changed = true;
        int loops = 0;
        while (changed && loops++ < 5) {   // 不动点迭代，上限 5 跳防环
            changed = false;
            for (Map.Entry<String, Set<String>> edge : downstream.entrySet()) {
                Set<Long> tags = new HashSet<>();
                for (String s : tableTags.getOrDefault(edge.getKey(), Set.of())) { try { tags.add(Long.parseLong(s)); } catch (Exception ignored) {} }
                if (tags.isEmpty()) continue;
                for (String tgt : edge.getValue()) {
                    for (Long tag : tags) {
                        if (exist.add(tag + "|" + tgt)) {
                            jdbc.update("INSERT INTO meta.gov_tag_relation(id, tag_id, target_type, target_db, target_table, target_column) VALUES (?,?,?,?,?,?)",
                                    System.currentTimeMillis() + (long) (Math.random() * 1000), tag, "table", "", tgt, "");
                            tableTags.computeIfAbsent(tgt, k -> new HashSet<>()).add(String.valueOf(tag));
                            created++;
                            changed = true;
                        }
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("created", created);
        out.put("hops", loops - 1);
        return out;
    }

    /** columns_json（[{"name":..,"type":..,"comment":..}]）解析，坏数据吞掉返回空。 */
    private List<Map<String, Object>> parseColumns(String columnsJson) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (var n : json.readTree(columnsJson == null || columnsJson.isEmpty() ? "[]" : columnsJson)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", n.has("name") ? n.get("name").asText() : "");
                m.put("type", n.has("type") ? n.get("type").asText() : "");
                m.put("comment", n.has("comment") ? n.get("comment").asText() : "");
                out.add(m);
            }
            return out;
        } catch (Exception e) { return List.of(); }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String strOrDefault(Object o, String def) { String s = str(o); return s.isEmpty() ? def : s; }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
