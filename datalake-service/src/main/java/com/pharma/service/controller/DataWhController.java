package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 数据仓库 [SYS_ADMIN]：分层管理 + 层级-数据源绑定 + 主题域。 */
@RestController
@RequestMapping("/api/data-gov/wh")
@CrossOrigin(origins = "*")
public class DataWhController {

    @Autowired private JdbcTemplate jdbc;

    // ===== 分层 =====
    @GetMapping("/layer")
    public List<Map<String, Object>> listLayer() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT code, name, sort, status FROM meta.gov_layer ORDER BY sort");
    }
    @PostMapping("/layer")
    public Map<String, Object> createLayer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("INSERT INTO meta.gov_layer(code, name, sort, status) VALUES (?,?,?,?)",
                str(b.get("code")), str(b.get("name")), num(b.get("sort")), str(b.getOrDefault("status", "NORMAL")));
        return Map.of("success", true);
    }
    @PutMapping("/layer")
    public Map<String, Object> updateLayer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_layer SET name=?, sort=?, status=? WHERE code=?",
                str(b.get("name")), num(b.get("sort")), str(b.getOrDefault("status", "NORMAL")), str(b.get("code")));
        return Map.of("success", true);
    }
    @DeleteMapping("/layer")
    public Map<String, Object> deleteLayer(@RequestParam String code) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_layer_datasource WHERE layer_code=?", code);
        jdbc.update("DELETE FROM meta.gov_layer WHERE code=?", code);
        return Map.of("success", true);
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
    @DeleteMapping("/subject")
    public Map<String, Object> deleteSubject(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_subject WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
