package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import com.pharma.service.security.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据资产 [SYS_ADMIN]：资产编目（目录树）+ 资产挂载（资产 CRUD）+ 资产审核（状态机）。 */
@RestController
@RequestMapping("/api/asset")
@CrossOrigin(origins = "*")
public class AssetController {

    @Autowired private JdbcTemplate jdbc;

    // ===== 资产编目（目录树） =====
    @GetMapping("/catalog/tree")
    public List<Map<String, Object>> catalogTree() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> flat = jdbc.queryForList("SELECT id, code, name, parent_id, node_type, sort FROM meta.asset_catalog ORDER BY sort, id");
        Map<Long, List<Map<String, Object>>> byParent = new HashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : flat) {
            Object p = n.get("parent_id");
            if (p == null || lng(p) == 0) roots.add(n);
            else byParent.computeIfAbsent(lng(p), k -> new ArrayList<>()).add(n);
        }
        for (Map<String, Object> r : roots) r.put("children", byParent.getOrDefault(lng(r.get("id")), List.of()));
        return roots;
    }
    @PostMapping("/catalog")
    public Map<String, Object> createCatalog(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.asset_catalog(id, code, name, parent_id, node_type, sort) VALUES (?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), lng(b.get("parent_id")), str(b.getOrDefault("node_type", "分类")), num(b.get("sort")));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/catalog")
    public Map<String, Object> updateCatalog(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.asset_catalog SET code=?, name=?, parent_id=?, node_type=?, sort=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), lng(b.get("parent_id")), str(b.get("node_type")), num(b.get("sort")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping("/catalog")
    public Map<String, Object> deleteCatalog(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        long child = jdbc.queryForObject("SELECT COUNT(*) FROM meta.asset_catalog WHERE parent_id=?", Long.class, id);
        if (child > 0) throw new com.pharma.service.security.AccessDeniedException("存在子节点，禁止删除");
        long a = jdbc.queryForObject("SELECT COUNT(*) FROM meta.asset WHERE catalog_id=?", Long.class, id);
        if (a > 0) throw new com.pharma.service.security.AccessDeniedException("该目录下有资产，禁止删除");
        jdbc.update("DELETE FROM meta.asset_catalog WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 资产（含挂载） =====
    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long catalogId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String kw) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT a.id, a.catalog_id, a.name, a.asset_type, a.source_type, a.source_id, " +
                "a.owner, a.security_level, a.description, a.status, a.create_by, a.create_time, c.name AS catalog_name " +
                "FROM meta.asset a LEFT JOIN meta.asset_catalog c ON c.id=a.catalog_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (catalogId != null) { sql.append(" AND a.catalog_id=?"); args.add(catalogId); }
        if (status != null && !status.isEmpty()) { sql.append(" AND a.status=?"); args.add(status); }
        if (kw != null && !kw.isEmpty()) { sql.append(" AND (a.name LIKE ? OR a.description LIKE ?)"); args.add("%" + kw + "%"); args.add("%" + kw + "%"); }
        sql.append(" ORDER BY a.id DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.asset(id, catalog_id, name, asset_type, source_type, source_id, owner, security_level, description, status, create_by, create_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, lng(b.get("catalog_id")), str(b.get("name")), str(b.getOrDefault("asset_type", "表")),
                str(b.get("source_type")), lng(b.get("source_id")), str(b.get("owner")),
                str(b.getOrDefault("security_level", "内部")), str(b.get("description")),
                "草稿", currentUser(), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.asset SET catalog_id=?, name=?, asset_type=?, source_type=?, source_id=?, owner=?, security_level=?, description=? WHERE id=?",
                lng(b.get("catalog_id")), str(b.get("name")), str(b.get("asset_type")), str(b.get("source_type")),
                lng(b.get("source_id")), str(b.get("owner")), str(b.get("security_level")), str(b.get("description")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.asset_audit WHERE asset_id=?", id);
        jdbc.update("DELETE FROM meta.asset WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 审核状态机：草稿→待审→通过/驳回 =====
    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return transition(id, "待审", "提交", "");
    }
    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestParam long id, @RequestBody(required = false) Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        return transition(id, "通过", "通过", b == null ? "" : str(b.get("comment")));
    }
    @PostMapping("/reject")
    public Map<String, Object> reject(@RequestParam long id, @RequestBody(required = false) Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        return transition(id, "驳回", "驳回", b == null ? "" : str(b.get("comment")));
    }

    // ===== 上下线 / 解绑：资产挂载后的生命周期管理 =====
    // 设计要点：运行时调用(DataServiceExecutor)、新发布服务、新建开放授权、集市可见列表均为
    // status='通过' 的正向白名单。因此「下线」(status 改为非「通过」)后，上述链路自动阻断/隐藏，
    // 无需级联改 data_service / data_open_grant；记录保留，重新「上线」即恢复可用。
    /** 下线：通过 → 下线。 */
    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestParam long id, @RequestBody(required = false) Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String cur = currentStatus(id);
        if (!"通过".equals(cur)) throw new AccessDeniedException("仅「通过」状态的资产可下线（当前：" + cur + "）");
        return transition(id, "下线", "下线", b == null ? "" : str(b.get("comment")));
    }

    /** 上线：下线 → 通过（重新上架；此前保留的授权/服务恢复可用）。 */
    @PostMapping("/online")
    public Map<String, Object> online(@RequestParam long id, @RequestBody(required = false) Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String cur = currentStatus(id);
        if (!"下线".equals(cur)) throw new AccessDeniedException("仅「下线」状态的资产可重新上线（当前：" + cur + "）");
        return transition(id, "通过", "上线", b == null ? "" : str(b.get("comment")));
    }

    /** 解绑：清空挂载来源（source_type/source_id）。仅 下线/草稿/驳回 态可解绑，保护在途/在用资产。 */
    @PostMapping("/unbind")
    public Map<String, Object> unbind(@RequestParam long id, @RequestBody(required = false) Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String cur = currentStatus(id);
        if ("通过".equals(cur) || "待审".equals(cur)) throw new AccessDeniedException("资产处于「" + cur + "」状态，请先下线/撤回再解绑来源");
        jdbc.update("UPDATE meta.asset SET source_type='', source_id=0 WHERE id=?", id);
        jdbc.update("INSERT INTO meta.asset_audit(id, asset_id, action, comment, auditor, audit_time) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), id, "解绑", b == null ? "" : str(b.get("comment")), currentUser(), new Timestamp(System.currentTimeMillis()));
        return Map.of("success", true);
    }

    private Map<String, Object> transition(long id, String status, String action, String comment) {
        jdbc.update("UPDATE meta.asset SET status=? WHERE id=?", status, id);
        jdbc.update("INSERT INTO meta.asset_audit(id, asset_id, action, comment, auditor, audit_time) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), id, action, comment, currentUser(), new Timestamp(System.currentTimeMillis()));
        return Map.of("success", true, "status", status);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam long assetId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, asset_id, action, comment, auditor, audit_time FROM meta.asset_audit WHERE asset_id=? ORDER BY id DESC", assetId);
    }

    // ===== 挂载源（来自元数据表） =====
    @GetMapping("/source-tables")
    public List<Map<String, Object>> sourceTables(@RequestParam(required = false) Long dsId) {
        Authz.require(Authz.SYS_ADMIN);
        if (dsId == null) return jdbc.queryForList("SELECT id, ds_id, schema_name, table_name, comment FROM meta.gov_meta_table ORDER BY ds_id, table_name");
        return jdbc.queryForList("SELECT id, ds_id, schema_name, table_name, comment FROM meta.gov_meta_table WHERE ds_id=? ORDER BY table_name", dsId);
    }
    @GetMapping("/source-columns")
    public List<Map<String, Object>> sourceColumns(@RequestParam long metaTableId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> row = jdbc.queryForMap("SELECT columns_json FROM meta.gov_meta_table WHERE id=?", metaTableId);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(str(row.get("columns_json")), List.class);
        } catch (Exception e) { return List.of(); }
    }

    // -------- 助手 --------
    private String currentStatus(long id) {
        return str(jdbc.queryForObject("SELECT status FROM meta.asset WHERE id=?", String.class, id));
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static String currentUser() { try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); } catch (Exception e) { return "system"; } }
}
