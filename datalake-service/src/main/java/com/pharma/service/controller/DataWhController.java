package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据仓库 [SYS_ADMIN]：分层管理 + 层级-数据源绑定 + 主题域。
 * <p>P1 升级：分层画像（每层物理表数/行数/存储，StarRocks information_schema 聚合）+ 命名规范巡检
 * （按层 naming_pattern 正则扫描存量表名）+ 主题域编辑。
 */
@RestController
@RequestMapping("/api/data-gov/wh")
@CrossOrigin(origins = "*")
public class DataWhController {

    @Autowired private JdbcTemplate jdbc;

    // ===== 分层 =====
    @GetMapping("/layer")
    public List<Map<String, Object>> listLayer() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT code, name, sort, status, naming_pattern FROM meta.gov_layer ORDER BY sort");
    }
    @PostMapping("/layer")
    public Map<String, Object> createLayer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("INSERT INTO meta.gov_layer(code, name, sort, status, naming_pattern) VALUES (?,?,?,?,?)",
                str(b.get("code")), str(b.get("name")), num(b.get("sort")), str(b.getOrDefault("status", "NORMAL")), str(b.get("naming_pattern")));
        return Map.of("success", true);
    }
    @PutMapping("/layer")
    public Map<String, Object> updateLayer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        validatePattern(str(b.get("naming_pattern")));
        jdbc.update("UPDATE meta.gov_layer SET name=?, sort=?, status=?, naming_pattern=? WHERE code=?",
                str(b.get("name")), num(b.get("sort")), str(b.getOrDefault("status", "NORMAL")), str(b.get("naming_pattern")), str(b.get("code")));
        return Map.of("success", true);
    }
    @DeleteMapping("/layer")
    public Map<String, Object> deleteLayer(@RequestParam String code) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_layer_datasource WHERE layer_code=?", code);
        jdbc.update("DELETE FROM meta.gov_layer WHERE code=?", code);
        return Map.of("success", true);
    }

    /** 正则合法性校验（坏正则会让巡检整层报错）。 */
    private static void validatePattern(String p) {
        if (p == null || p.isEmpty()) return;
        try { Pattern.compile(p); } catch (Exception e) { throw new IllegalArgumentException("命名规范正则非法: " + e.getMessage()); }
    }

    /**
     * 分层画像：每层物理表数 + 行数合计。主查 StarRocks information_schema（TABLE_SCHEMA=层编码），
     * 失败（非 SR 环境/无权限）回退数 gov_meta_table 登记。
     */
    @GetMapping("/layer/stats")
    public List<Map<String, Object>> layerStats() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> layers = jdbc.queryForList("SELECT code, name, sort FROM meta.gov_layer ORDER BY sort");
        Map<String, Map<String, Object>> info = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT TABLE_SCHEMA, COUNT(*) AS tables_cnt, COALESCE(SUM(TABLE_ROWS), 0) AS rows_cnt " +
                            "FROM information_schema.tables WHERE TABLE_TYPE='BASE TABLE' GROUP BY TABLE_SCHEMA")) {
                Map<String, Object> m = new HashMap<>();
                m.put("tables", ((Number) r.get("tables_cnt")).longValue());
                m.put("rows", ((Number) r.get("rows_cnt")).longValue());
                info.put(str(r.get("TABLE_SCHEMA")).toLowerCase(), m);
            }
        } catch (Exception ignored) {}
        // 元数据登记数（info 缺层时兜底展示）
        Map<String, Long> metaCnt = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT COALESCE(layer_code, schema_name) AS layer, COUNT(*) AS c FROM meta.gov_meta_table GROUP BY COALESCE(layer_code, schema_name)")) {
                metaCnt.put(str(r.get("layer")).toLowerCase(), ((Number) r.get("c")).longValue());
            }
        } catch (Exception ignored) {}
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : layers) {
            Map<String, Object> row = new LinkedHashMap<>(l);
            String code = str(l.get("code")).toLowerCase();
            Map<String, Object> st = info.get(code);
            row.put("tables", st == null ? metaCnt.getOrDefault(code, 0L) : st.get("tables"));
            row.put("rows", st == null ? 0L : st.get("rows"));
            row.put("source", st == null ? "meta" : "physical");
            out.add(row);
        }
        return out;
    }

    /**
     * 命名规范巡检：按层 naming_pattern 正则扫描存量表（gov_meta_table 的登记表名），
     * 不匹配即违规。返回每层 配置/检查数/违规清单。
     */
    @GetMapping("/layer/naming-check")
    public Map<String, Object> namingCheck() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> layers = jdbc.queryForList("SELECT code, name, naming_pattern FROM meta.gov_layer ORDER BY sort");
        List<Map<String, Object>> metaTables = jdbc.queryForList(
                "SELECT table_name, COALESCE(layer_code, schema_name) AS layer FROM meta.gov_meta_table");
        int total = 0, violate = 0;
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Map<String, Object> l : layers) {
            String pattern = str(l.get("naming_pattern"));
            String code = str(l.get("code"));
            if (pattern.isEmpty()) continue;
            Pattern p;
            try { p = Pattern.compile(pattern); } catch (Exception e) { continue; }
            for (Map<String, Object> t : metaTables) {
                if (!code.equalsIgnoreCase(str(t.get("layer")))) continue;
                total++;
                String name = str(t.get("table_name"));
                if (!p.matcher(name).find()) {
                    violate++;
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("layer", code);
                    v.put("table", name);
                    v.put("pattern", pattern);
                    v.put("suggest", code + "_" + name);
                    violations.add(v);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", total);
        out.put("violate", violate);
        out.put("pass", total - violate);
        out.put("violations", violations);
        return out;
    }

    // ===== 层级-数据源绑定 =====
    @GetMapping("/layer/datasource")
    public List<Map<String, Object>> listLayerDs(@RequestParam String layerCode) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, layer_code, datasource_id FROM meta.gov_layer_datasource WHERE layer_code=?", layerCode);
    }
    @PostMapping("/layer/datasource")
    public Map<String, Object> bindLayerDs(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long layerCode = lng(b.get("datasource_id"));
        String lc = str(b.get("layer_code"));
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM meta.gov_layer_datasource WHERE layer_code=? AND datasource_id=?", Integer.class, lc, layerCode);
        if (c != null && c > 0) return Map.of("success", true, "msg", "已绑定");
        jdbc.update("INSERT INTO meta.gov_layer_datasource(id, layer_code, datasource_id) VALUES (?,?,?)",
                System.currentTimeMillis(), lc, layerCode);
        return Map.of("success", true);
    }
    @DeleteMapping("/layer/datasource")
    public Map<String, Object> unbindLayerDs(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_layer_datasource WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 主题域（树） =====
    @GetMapping("/subject")
    public List<Map<String, Object>> listSubject() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> flat = jdbc.queryForList("SELECT id, code, name, parent_id, sort FROM meta.gov_subject ORDER BY sort, id");
        Map<Long, List<Map<String, Object>>> byParent = new HashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> s : flat) {
            Object p = s.get("parent_id");
            if (p == null || lng(p) == 0) roots.add(s);
            else byParent.computeIfAbsent(lng(p), k -> new ArrayList<>()).add(s);
        }
        for (Map<String, Object> r : roots) r.put("children", byParent.getOrDefault(lng(r.get("id")), List.of()));
        return roots;
    }
    @PostMapping("/subject")
    public Map<String, Object> createSubject(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("INSERT INTO meta.gov_subject(id, code, name, parent_id, sort) VALUES (?,?,?,?,?)",
                System.currentTimeMillis(), str(b.get("code")), str(b.get("name")), lng(b.get("parent_id")), num(b.get("sort")));
        return Map.of("success", true);
    }
    @PutMapping("/subject")
    public Map<String, Object> updateSubject(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        if (id == 0) throw new IllegalArgumentException("id 必填");
        if (id == lng(b.get("parent_id"))) throw new IllegalArgumentException("父节点不能是自身");
        jdbc.update("UPDATE meta.gov_subject SET code=?, name=?, parent_id=?, sort=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), lng(b.get("parent_id")), num(b.get("sort")), id);
        return Map.of("success", true);
    }
    @DeleteMapping("/subject")
    public Map<String, Object> deleteSubject(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM meta.gov_subject WHERE parent_id=?", Integer.class, id);
        if (c != null && c > 0) throw new IllegalArgumentException("存在子主题域，先删子节点");
        jdbc.update("DELETE FROM meta.gov_subject WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
