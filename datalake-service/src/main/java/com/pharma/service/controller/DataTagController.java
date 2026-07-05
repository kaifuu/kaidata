package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 数据标签 [SYS_ADMIN]：标签定义 + 打标关系（表/字段打标）。 */
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

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
