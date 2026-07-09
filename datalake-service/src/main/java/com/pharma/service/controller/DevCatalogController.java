package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据开发分类树 [SYS_ADMIN]：离线/实时/脚本三模块共用一表，module_type 区分（OFFLINE/STREAM/SCRIPT）。
 */
@RestController
@RequestMapping("/api/data-dev/catalog")
@CrossOrigin(origins = "*")
public class DevCatalogController {

    @Autowired private JdbcTemplate jdbc;

    @GetMapping("/tree")
    public List<Map<String, Object>> tree(@RequestParam String moduleType) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> flat = jdbc.queryForList(
                "SELECT id, parent_id, name, module_type, sort FROM meta.dev_catalog WHERE module_type=? ORDER BY sort, id", moduleType);
        Map<Long, List<Map<String, Object>>> byParent = new LinkedHashMap<>();
        for (Map<String, Object> n : flat) n.put("children", byParent.computeIfAbsent(lng(n.get("id")), k -> new ArrayList<>()));
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : flat) {
            long pid = lng(n.get("parent_id"));
            if (pid == 0) roots.add(n);
            else { List<Map<String, Object>> sibs = byParent.get(pid); if (sibs != null) sibs.add(n); }
        }
        return roots;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.dev_catalog(id, parent_id, name, module_type, sort, create_by, create_time) VALUES (?,?,?,?,?,?,?)",
                id, lng(b.get("parent_id")), str(b.get("name")), str(b.get("module_type")),
                (int) lng(b.getOrDefault("sort", 0)), currentUser(), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.dev_catalog SET name=?, parent_id=?, sort=? WHERE id=?",
                str(b.get("name")), lng(b.get("parent_id")), (int) lng(b.getOrDefault("sort", 0)), lng(b.get("id")));
        return Map.of("success", true);
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Integer children = jdbc.queryForObject("SELECT COUNT(*) FROM meta.dev_catalog WHERE parent_id=?", Integer.class, id);
        if (children != null && children > 0) throw new RuntimeException("该分类下有子分类，不能删除");
        long refs = 0;
        for (String t : new String[]{"dev_offline_task", "dev_script", "ing_stream_job"}) {
            try { Long c = jdbc.queryForObject("SELECT COUNT(*) FROM meta." + t + " WHERE catalog_id=?", Long.class, id); if (c != null) refs += c; }
            catch (Exception ignored) {}
        }
        if (refs > 0) throw new RuntimeException("该分类下有任务，请先迁移或删除任务");
        jdbc.update("DELETE FROM meta.dev_catalog WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private static String currentUser() {
        try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }
}
