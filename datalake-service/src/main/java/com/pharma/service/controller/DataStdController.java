package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据标准 [SYS_ADMIN]：数据元 + 代码集 + 代码项。
 * <p>对标大数据平台：数据元可引用代码集作取值域（value_domain 联动生成）、
 * 支持分类/状态/关键字筛选、引用统计（数据元被模型字段引用、代码集被数据元引用）。
 */
@RestController
@RequestMapping("/api/data-gov/std")
@CrossOrigin(origins = "*")
public class DataStdController {

    @Autowired private JdbcTemplate jdbc;

    // ==================== 数据元 ====================

    @GetMapping("/element")
    public List<Map<String, Object>> listElement(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder(
                "SELECT e.id, e.code, e.name, e.en_name, e.category, e.data_type, e.length, e.precision_, e.scale_, " +
                "e.unit, e.data_format, e.security_level, e.owner, e.code_set_id, e.definition, e.value_domain, " +
                "e.version, e.status, e.create_time, e.update_time, " +
                "cs.name AS code_set_name, " +
                "COALESCE(r.ref_cnt, 0) AS ref_cnt " +
                "FROM meta.gov_data_element e " +
                "LEFT JOIN meta.gov_code_set cs ON cs.id = e.code_set_id " +
                "LEFT JOIN (SELECT element_id, COUNT(*) AS ref_cnt FROM meta.gov_model_field WHERE element_id > 0 GROUP BY element_id) r ON r.element_id = e.id " +
                "WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isEmpty()) { sql.append(" AND e.category=?"); args.add(category); }
        if (status != null && !status.isEmpty()) { sql.append(" AND e.status=?"); args.add(status); }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (e.code LIKE ? OR e.name LIKE ? OR e.en_name LIKE ?)");
            String k = "%" + keyword + "%"; args.add(k); args.add(k); args.add(k);
        }
        sql.append(" ORDER BY e.id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping("/element")
    public Map<String, Object> createElement(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        long codeSetId = lng(b.get("code_set_id"));
        String valueDomain = codeSetId > 0 ? buildValueDomain(codeSetId) : str(b.get("value_domain"));
        jdbc.update("INSERT INTO meta.gov_data_element" +
                        "(id, code, name, en_name, category, data_type, length, precision_, scale_, " +
                        "unit, data_format, security_level, owner, code_set_id, definition, value_domain, version, status, create_time, update_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), str(b.get("en_name")), str(b.get("category")),
                str(b.get("data_type")), num(b.get("length")), num(b.get("precision_")), num(b.get("scale_")),
                str(b.get("unit")), str(b.get("data_format")), str(b.get("security_level")), str(b.get("owner")),
                codeSetId, str(b.get("definition")), valueDomain, 1, str(b.getOrDefault("status", "NORMAL")), now, now);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/element")
    public Map<String, Object> updateElement(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        long codeSetId = lng(b.get("code_set_id"));
        String valueDomain = codeSetId > 0 ? buildValueDomain(codeSetId) : str(b.get("value_domain"));
        jdbc.update("UPDATE meta.gov_data_element SET code=?, name=?, en_name=?, category=?, data_type=?, length=?, precision_=?, scale_=?, " +
                        "unit=?, data_format=?, security_level=?, owner=?, code_set_id=?, definition=?, value_domain=?, status=?, update_time=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("en_name")), str(b.get("category")),
                str(b.get("data_type")), num(b.get("length")), num(b.get("precision_")), num(b.get("scale_")),
                str(b.get("unit")), str(b.get("data_format")), str(b.get("security_level")), str(b.get("owner")),
                codeSetId, str(b.get("definition")), valueDomain, str(b.getOrDefault("status", "NORMAL")), now, id);
        return Map.of("success", true);
    }

    @DeleteMapping("/element")
    public Map<String, Object> deleteElement(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_data_element WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 数据元被哪些模型字段引用（引用统计明细） */
    @GetMapping("/element/refs")
    public Map<String, Object> elementRefs(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> refs = jdbc.queryForList(
                "SELECT f.id AS field_id, f.name AS field_name, f.data_type, f.table_id, t.name AS table_name, m.name AS model_name " +
                "FROM meta.gov_model_field f " +
                "LEFT JOIN meta.gov_model_table t ON t.id = f.table_id " +
                "LEFT JOIN meta.gov_model m ON m.id = t.model_id " +
                "WHERE f.element_id=? ORDER BY f.id", id);
        return Map.of("ref_count", refs.size(), "refs", refs);
    }

    // ==================== 标准落标 + 合规扫描 ====================

    /** 落标统计：总字段数 / 已落标 / 落标率 / 引用最多的数据元 top5 / 未落标字段清单。 */
    @GetMapping("/landing-stats")
    public Map<String, Object> landingStats() {
        Authz.require(Authz.SYS_ADMIN);
        long total = cnt("SELECT COUNT(*) FROM meta.gov_model_field");
        long landed = cnt("SELECT COUNT(*) FROM meta.gov_model_field WHERE element_id>0");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("landed", landed);
        out.put("rate", total == 0 ? 0 : Math.round(landed * 100.0 / total));
        out.put("topElements", safeList(
                "SELECT e.name, e.code, r.c AS refs FROM meta.gov_data_element e " +
                        "JOIN (SELECT element_id, COUNT(*) AS c FROM meta.gov_model_field WHERE element_id>0 GROUP BY element_id ORDER BY c DESC LIMIT 5) r ON r.element_id=e.id ORDER BY r.c DESC"));
        out.put("unlanded", safeList(
                "SELECT f.name AS field, f.data_type, t.name AS table_name, m.name AS model_name " +
                        "FROM meta.gov_model_field f LEFT JOIN meta.gov_model_table t ON t.id=f.table_id " +
                        "LEFT JOIN meta.gov_model m ON m.id=t.model_id WHERE f.element_id<=0 OR f.element_id IS NULL ORDER BY f.id LIMIT 200"));
        return out;
    }

    /** 合规扫描：已落标字段的类型基名 vs 数据元类型基名是否一致。 */
    @GetMapping("/compliance-scan")
    public Map<String, Object> complianceScan() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = safeList(
                "SELECT f.name AS field, f.data_type AS field_type, e.name AS element, e.data_type AS element_type, t.name AS table_name " +
                        "FROM meta.gov_model_field f JOIN meta.gov_data_element e ON e.id=f.element_id " +
                        "LEFT JOIN meta.gov_model_table t ON t.id=f.table_id WHERE f.element_id>0");
        List<Map<String, Object>> fail = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (!baseName(str(r.get("field_type"))).equalsIgnoreCase(baseName(str(r.get("element_type"))))) fail.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("pass", rows.size() - fail.size());
        out.put("fail", fail.size());
        out.put("failList", fail);
        return out;
    }

    private static String baseName(String t) {
        if (t == null) return "";
        int i = t.indexOf('(');
        return (i < 0 ? t : t.substring(0, i)).trim().toUpperCase();
    }

    // ==================== 代码集 ====================

    @GetMapping("/code-set")
    public List<Map<String, Object>> listCodeSet(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.code, s.name, s.category, s.description, s.status, s.create_time, " +
                "COALESCE(r.ref_cnt, 0) AS ref_cnt, " +
                "COALESCE(i.item_cnt, 0) AS item_cnt " +
                "FROM meta.gov_code_set s " +
                "LEFT JOIN (SELECT code_set_id, COUNT(*) AS ref_cnt FROM meta.gov_data_element WHERE code_set_id > 0 GROUP BY code_set_id) r ON r.code_set_id = s.id " +
                "LEFT JOIN (SELECT set_id, COUNT(*) AS item_cnt FROM meta.gov_code_item GROUP BY set_id) i ON i.set_id = s.id " +
                "WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isEmpty()) { sql.append(" AND s.category=?"); args.add(category); }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (s.code LIKE ? OR s.name LIKE ?)");
            String k = "%" + keyword + "%"; args.add(k); args.add(k);
        }
        sql.append(" ORDER BY s.id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @PostMapping("/code-set")
    public Map<String, Object> createCodeSet(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_code_set(id, code, name, category, description, status, create_time) VALUES (?,?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), str(b.get("category")), str(b.get("description")),
                str(b.getOrDefault("status", "NORMAL")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/code-set")
    public Map<String, Object> updateCodeSet(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_code_set SET code=?, name=?, category=?, description=?, status=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("category")), str(b.get("description")),
                str(b.getOrDefault("status", "NORMAL")), lng(b.get("id")));
        return Map.of("success", true);
    }

    @DeleteMapping("/code-set")
    public Map<String, Object> deleteCodeSet(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_code_item WHERE set_id=?", id);
        jdbc.update("UPDATE meta.gov_data_element SET code_set_id=0 WHERE code_set_id=?", id); // 解除引用
        jdbc.update("DELETE FROM meta.gov_code_set WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 代码集详情（含代码项，供数据元编辑下拉预览） */
    @GetMapping("/code-set/detail")
    public Map<String, Object> codeSetDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> set = jdbc.queryForMap(
                "SELECT id, code, name, category, description, status, create_time FROM meta.gov_code_set WHERE id=?", id);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id, set_id, code, name, sort, is_enabled, remark FROM meta.gov_code_item WHERE set_id=? ORDER BY sort, id", id);
        Map<String, Object> r = new LinkedHashMap<>(set);
        r.put("items", items);
        return r;
    }

    /** 代码集被哪些数据元引用 */
    @GetMapping("/code-set/refs")
    public Map<String, Object> codeSetRefs(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> refs = jdbc.queryForList(
                "SELECT id, code, name, en_name, category, status FROM meta.gov_data_element WHERE code_set_id=? ORDER BY id", id);
        return Map.of("ref_count", refs.size(), "refs", refs);
    }

    // ==================== 代码项 ====================

    @GetMapping("/code-item")
    public List<Map<String, Object>> listCodeItem(@RequestParam long setId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, set_id, code, name, sort, is_enabled, remark FROM meta.gov_code_item WHERE set_id=? ORDER BY sort, id", setId);
    }

    @PostMapping("/code-item")
    public Map<String, Object> createCodeItem(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        long setId = lng(b.get("set_id"));
        jdbc.update("INSERT INTO meta.gov_code_item(id, set_id, code, name, sort, is_enabled, remark) VALUES (?,?,?,?,?,?,?)",
                id, setId, str(b.get("code")), str(b.get("name")), num(b.get("sort")),
                boolOr(b.get("is_enabled"), true), str(b.get("remark")));
        refreshValueDomain(setId);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/code-item")
    public Map<String, Object> updateCodeItem(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long setId = lng(b.get("set_id"));
        jdbc.update("UPDATE meta.gov_code_item SET code=?, name=?, sort=?, is_enabled=?, remark=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), num(b.get("sort")),
                boolOr(b.get("is_enabled"), true), str(b.get("remark")), lng(b.get("id")));
        if (setId > 0) refreshValueDomain(setId);
        return Map.of("success", true);
    }

    @DeleteMapping("/code-item")
    public Map<String, Object> deleteCodeItem(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT set_id FROM meta.gov_code_item WHERE id=?", id);
        long setId = rows.isEmpty() ? 0 : lng(rows.get(0).get("set_id"));
        jdbc.update("DELETE FROM meta.gov_code_item WHERE id=?", id);
        if (setId > 0) refreshValueDomain(setId);
        return Map.of("success", true);
    }

    // ==================== 取值域联动 ====================

    /** 把代码集下启用项拼成 "code1=name1, code2=name2" */
    private String buildValueDomain(long codeSetId) {
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT code, name FROM meta.gov_code_item WHERE set_id=? AND is_enabled ORDER BY sort, id", codeSetId);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> it : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.get("code")).append("=").append(it.get("name"));
        }
        return sb.toString();
    }

    /** 代码项增删改后，重算所有引用该代码集的数据元 value_domain */
    private void refreshValueDomain(long codeSetId) {
        if (codeSetId <= 0) return;
        jdbc.update("UPDATE meta.gov_data_element SET value_domain=? WHERE code_set_id=?", buildValueDomain(codeSetId), codeSetId);
    }

    // ==================== 助手 ====================

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private long cnt(String sql, Object... args) { try { return args.length == 0 ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, args); } catch (Exception e) { return 0; } }
    private List<Map<String, Object>> safeList(String sql, Object... args) { try { return args.length == 0 ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args); } catch (Exception e) { return List.of(); } }
    private static boolean boolOr(Object o, boolean def) {
        if (o == null) return def;
        return Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o));
    }
}
