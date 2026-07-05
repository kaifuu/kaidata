package com.pharma.service.controller;

import com.pharma.service.access.util.CryptoUtil;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据安全 [SYS_ADMIN]：安全标准/脱敏/密钥/告警/黑白名单/敏感数据/数据权限(表级)。 */
@RestController
@RequestMapping("/api/security")
@CrossOrigin(origins = "*")
public class SecurityController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private CryptoUtil crypto;

    // ===== ①安全标准 =====
    @GetMapping("/std/list")
    public List<Map<String, Object>> listStd() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT id, code, name, level, description FROM meta.sec_standard ORDER BY level"); }
    @PostMapping("/std")
    public Map<String, Object> createStd(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = System.currentTimeMillis(); jdbc.update("INSERT INTO meta.sec_standard(id, code, name, level, description, create_time) VALUES (?,?,?,?,?,?)", id, str(b.get("code")), str(b.get("name")), num(b.get("level")), str(b.get("description")), new Timestamp(id)); return Map.of("success", true, "id", id); }
    @PutMapping("/std")
    public Map<String, Object> updateStd(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.sec_standard SET code=?, name=?, level=?, description=? WHERE id=?", str(b.get("code")), str(b.get("name")), num(b.get("level")), str(b.get("description")), lng(b.get("id"))); return Map.of("success", true); }
    @DeleteMapping("/std")
    public Map<String, Object> deleteStd(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_standard WHERE id=?", id); return Map.of("success", true); }

    // ===== ②数据脱敏：规则 + 绑定 =====
    @GetMapping("/mask/rule")
    public List<Map<String, Object>> listMaskRule() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT id, name, mask_type, pattern, replacement, description FROM meta.sec_mask_rule ORDER BY id"); }
    @PostMapping("/mask/rule")
    public Map<String, Object> createMaskRule(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = System.currentTimeMillis(); jdbc.update("INSERT INTO meta.sec_mask_rule(id, name, mask_type, pattern, replacement, description) VALUES (?,?,?,?,?,?)", id, str(b.get("name")), str(b.get("mask_type")), str(b.get("pattern")), str(b.get("replacement")), str(b.get("description"))); return Map.of("success", true, "id", id); }
    @PutMapping("/mask/rule")
    public Map<String, Object> updateMaskRule(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.sec_mask_rule SET name=?, mask_type=?, pattern=?, replacement=?, description=? WHERE id=?", str(b.get("name")), str(b.get("mask_type")), str(b.get("pattern")), str(b.get("replacement")), str(b.get("description")), lng(b.get("id"))); return Map.of("success", true); }
    @DeleteMapping("/mask/rule")
    public Map<String, Object> deleteMaskRule(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_mask_rel WHERE rule_id=?", id); jdbc.update("DELETE FROM meta.sec_mask_rule WHERE id=?", id); return Map.of("success", true); }
    @GetMapping("/mask/rel")
    public List<Map<String, Object>> listMaskRel() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT r.id, r.rule_id, m.name AS rule_name, r.source_table, r.source_column FROM meta.sec_mask_rel r LEFT JOIN meta.sec_mask_rule m ON m.id=r.rule_id ORDER BY r.id"); }
    @PostMapping("/mask/rel")
    public Map<String, Object> bindMask(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); jdbc.update("INSERT INTO meta.sec_mask_rel(id, rule_id, source_table, source_column) VALUES (?,?,?,?)", System.currentTimeMillis(), lng(b.get("rule_id")), str(b.get("source_table")), str(b.get("source_column"))); return Map.of("success", true); }
    @DeleteMapping("/mask/rel")
    public Map<String, Object> unbindMask(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_mask_rel WHERE id=?", id); return Map.of("success", true); }

    // ===== ③密钥管理（key_value 加密存储，列表不回显） =====
    @GetMapping("/key/list")
    public List<Map<String, Object>> listKey() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT id, name, algo, status, create_time FROM meta.sec_key ORDER BY id"); }
    @PostMapping("/key")
    public Map<String, Object> createKey(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = System.currentTimeMillis(); jdbc.update("INSERT INTO meta.sec_key(id, name, algo, key_value, status, create_time) VALUES (?,?,?,?,?,?)", id, str(b.get("name")), str(b.getOrDefault("algo", "AES")), crypto.encrypt(str(b.get("key_value"))), str(b.getOrDefault("status", "NORMAL")), new Timestamp(id)); return Map.of("success", true, "id", id); }
    @PutMapping("/key")
    public Map<String, Object> updateKey(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = lng(b.get("id")); Object v = b.get("key_value"); if (v != null && !String.valueOf(v).isEmpty()) jdbc.update("UPDATE meta.sec_key SET name=?, algo=?, key_value=?, status=? WHERE id=?", str(b.get("name")), str(b.get("algo")), crypto.encrypt(String.valueOf(v)), str(b.getOrDefault("status", "NORMAL")), id); else jdbc.update("UPDATE meta.sec_key SET name=?, algo=?, status=? WHERE id=?", str(b.get("name")), str(b.get("algo")), str(b.getOrDefault("status", "NORMAL")), id); return Map.of("success", true); }
    @DeleteMapping("/key")
    public Map<String, Object> deleteKey(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_key WHERE id=?", id); return Map.of("success", true); }

    // ===== ④告警管理：定义 + 事件 + 处理 =====
    @GetMapping("/alert/def")
    public List<Map<String, Object>> listAlertDef() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT id, name, source, condition_cfg, notify_channels, enabled FROM meta.sec_alert_def ORDER BY id"); }
    @PostMapping("/alert/def")
    public Map<String, Object> createAlertDef(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = System.currentTimeMillis(); jdbc.update("INSERT INTO meta.sec_alert_def(id, name, source, condition_cfg, notify_channels, enabled, create_time) VALUES (?,?,?,?,?,?,?)", id, str(b.get("name")), str(b.get("source")), str(b.get("condition_cfg")), str(b.get("notify_channels")), bool(b.get("enabled")), new Timestamp(id)); return Map.of("success", true, "id", id); }
    @PutMapping("/alert/def")
    public Map<String, Object> updateAlertDef(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.sec_alert_def SET name=?, source=?, condition_cfg=?, notify_channels=?, enabled=? WHERE id=?", str(b.get("name")), str(b.get("source")), str(b.get("condition_cfg")), str(b.get("notify_channels")), bool(b.get("enabled")), lng(b.get("id"))); return Map.of("success", true); }
    @DeleteMapping("/alert/def")
    public Map<String, Object> deleteAlertDef(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_alert_def WHERE id=?", id); return Map.of("success", true); }
    @GetMapping("/alert/event")
    public List<Map<String, Object>> listAlertEvent(@RequestParam(required = false) String status) { Authz.require(Authz.SYS_ADMIN); if (status == null || status.isEmpty()) return jdbc.queryForList("SELECT id, def_id, level, message, status, created_time FROM meta.sec_alert_event ORDER BY id DESC LIMIT 200"); return jdbc.queryForList("SELECT id, def_id, level, message, status, created_time FROM meta.sec_alert_event WHERE status=? ORDER BY id DESC LIMIT 200", status); }
    @PostMapping("/alert/handle")
    public Map<String, Object> handleAlert(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.sec_alert_event SET status='已处理' WHERE id=?", id); return Map.of("success", true); }
    /** 供其他模块（探查/质量）触发告警事件。 */
    public void raiseEvent(Long defId, String level, String message) { try { jdbc.update("INSERT INTO meta.sec_alert_event(id, def_id, level, message, status, created_time) VALUES (?,?,?,?,?,?)", System.currentTimeMillis(), defId == null ? 0 : defId, level, message, "未处理", new Timestamp(System.currentTimeMillis())); } catch (Exception ignored) {} }

    // ===== ⑤黑白名单 =====
    @GetMapping("/ip/list")
    public List<Map<String, Object>> listIp() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT id, ip, list_type, scope, comment, create_time FROM meta.sec_ip_list ORDER BY id"); }
    @PostMapping("/ip")
    public Map<String, Object> createIp(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = System.currentTimeMillis(); jdbc.update("INSERT INTO meta.sec_ip_list(id, ip, list_type, scope, comment, create_time) VALUES (?,?,?,?,?,?)", id, str(b.get("ip")), str(b.getOrDefault("list_type", "黑")), str(b.get("scope")), str(b.get("comment")), new Timestamp(id)); return Map.of("success", true, "id", id); }
    @PutMapping("/ip")
    public Map<String, Object> updateIp(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.sec_ip_list SET ip=?, list_type=?, scope=?, comment=? WHERE id=?", str(b.get("ip")), str(b.get("list_type")), str(b.get("scope")), str(b.get("comment")), lng(b.get("id"))); return Map.of("success", true); }
    @DeleteMapping("/ip")
    public Map<String, Object> deleteIp(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_ip_list WHERE id=?", id); return Map.of("success", true); }

    // ===== ⑥敏感数据 =====
    @GetMapping("/sensitive/list")
    public List<Map<String, Object>> listSensitive() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT s.id, s.source_table, s.source_column, s.sensitive_type, s.level, s.mask_rule_id, m.name AS mask_rule_name, s.description FROM meta.sec_sensitive s LEFT JOIN meta.sec_mask_rule m ON m.id=s.mask_rule_id ORDER BY s.id"); }
    @PostMapping("/sensitive")
    public Map<String, Object> createSensitive(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = System.currentTimeMillis(); jdbc.update("INSERT INTO meta.sec_sensitive(id, source_table, source_column, sensitive_type, level, mask_rule_id, description) VALUES (?,?,?,?,?,?,?)", id, str(b.get("source_table")), str(b.get("source_column")), str(b.get("sensitive_type")), str(b.getOrDefault("level", "敏感")), lng(b.get("mask_rule_id")), str(b.get("description"))); return Map.of("success", true, "id", id); }
    @PutMapping("/sensitive")
    public Map<String, Object> updateSensitive(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.sec_sensitive SET source_table=?, source_column=?, sensitive_type=?, level=?, mask_rule_id=?, description=? WHERE id=?", str(b.get("source_table")), str(b.get("source_column")), str(b.get("sensitive_type")), str(b.get("level")), lng(b.get("mask_rule_id")), str(b.get("description")), lng(b.get("id"))); return Map.of("success", true); }
    @DeleteMapping("/sensitive")
    public Map<String, Object> deleteSensitive(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_sensitive WHERE id=?", id); return Map.of("success", true); }

    // ===== ⑦数据权限（表级） =====
    @GetMapping("/perm/list")
    public List<Map<String, Object>> listPerm() { Authz.require(Authz.SYS_ADMIN); return jdbc.queryForList("SELECT p.id, p.role_id, r.name AS role_name, p.target_db, p.target_table, p.permission FROM meta.sec_data_perm p LEFT JOIN meta.sys_role r ON r.id=p.role_id ORDER BY p.id"); }
    @PostMapping("/perm")
    public Map<String, Object> createPerm(@RequestBody Map<String, Object> b) { Authz.require(Authz.SYS_ADMIN); long id = System.currentTimeMillis(); jdbc.update("INSERT INTO meta.sec_data_perm(id, role_id, target_db, target_table, permission, create_time) VALUES (?,?,?,?,?,?)", id, lng(b.get("role_id")), str(b.get("target_db")), str(b.get("target_table")), str(b.getOrDefault("permission", "select")), new Timestamp(id)); return Map.of("success", true, "id", id); }
    @DeleteMapping("/perm")
    public Map<String, Object> deletePerm(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("DELETE FROM meta.sec_data_perm WHERE id=?", id); return Map.of("success", true); }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static boolean bool(Object o) { return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o))); }
}
