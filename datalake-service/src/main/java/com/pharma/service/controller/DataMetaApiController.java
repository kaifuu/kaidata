package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 接口元数据 [SYS_ADMIN]：对「数据服务」发布的接口做补录（只编辑，新建在「数据服务 - 发布服务」）。
 */
@RestController
@RequestMapping("/api/data-gov/meta/api")
@CrossOrigin(origins = "*")
public class DataMetaApiController {

    @Autowired private JdbcTemplate jdbc;

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) String kw) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT s.id AS service_id, s.code, s.name AS service_name, s.method, s.path, s.status, " +
                "m.id AS meta_id, m.cn_name, m.dept, m.subject_id, m.share_type, m.admin_owner, " +
                "m.data_category, m.security_level, m.fill_percent, m.mount_status " +
                "FROM meta.data_service s LEFT JOIN meta.gov_meta_api m ON m.service_id=s.id";
        List<Object> args = new ArrayList<>();
        if (kw != null && !kw.isEmpty()) {
            sql += " WHERE s.name LIKE ? OR s.code LIKE ? OR m.cn_name LIKE ?";
            String p = "%" + kw + "%"; args.add(p); args.add(p); args.add(p);
        }
        sql += " ORDER BY s.id LIMIT 500";
        return args.isEmpty() ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args.toArray());
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long serviceId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> svc = jdbc.queryForMap(
                "SELECT id, code, name, sql_text, method, path, params, status FROM meta.data_service WHERE id=?", serviceId);
        List<Map<String, Object>> m = jdbc.queryForList("SELECT * FROM meta.gov_meta_api WHERE service_id=?", serviceId);
        svc.put("meta", m.isEmpty() ? null : m.get(0));
        return svc;
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long serviceId = lng(b.get("service_id"));
        if (serviceId == 0) throw new RuntimeException("service_id 必填");
        int fill = calcFill(b);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Map<String, Object>> exist = jdbc.queryForList("SELECT id FROM meta.gov_meta_api WHERE service_id=?", serviceId);
        if (exist.isEmpty()) {
            jdbc.update("INSERT INTO meta.gov_meta_api(id, service_id, cn_name, dept, app_system, subject_id, share_type, " +
                            "admin_owner, admin_contact, data_category, security_level, description, fill_percent, mount_status, create_time, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis() + (long) (Math.random() * 1000), serviceId, str(b.get("cn_name")), str(b.get("dept")),
                    str(b.get("app_system")), lng(b.get("subject_id")), str(b.get("share_type")), str(b.get("admin_owner")),
                    str(b.get("admin_contact")), str(b.get("data_category")), str(b.get("security_level")), str(b.get("description")),
                    fill, "NONE", now, now);
        } else {
            jdbc.update("UPDATE meta.gov_meta_api SET cn_name=?, dept=?, app_system=?, subject_id=?, share_type=?, admin_owner=?, " +
                            "admin_contact=?, data_category=?, security_level=?, description=?, fill_percent=?, update_time=? WHERE id=?",
                    str(b.get("cn_name")), str(b.get("dept")), str(b.get("app_system")), lng(b.get("subject_id")), str(b.get("share_type")),
                    str(b.get("admin_owner")), str(b.get("admin_contact")), str(b.get("data_category")), str(b.get("security_level")),
                    str(b.get("description")), fill, now, ((Number) exist.get(0).get("id")).longValue());
        }
        return Map.of("success", true, "fill_percent", fill);
    }

    private int calcFill(Map<String, Object> b) {
        String[] req = {"cn_name", "dept", "subject_id", "admin_owner", "data_category", "security_level", "description"};
        int filled = 0;
        for (String k : req) {
            Object v = b.get(k);
            if ("subject_id".equals(k)) { if (lng(v) != 0) filled++; }
            else { if (v != null && !String.valueOf(v).trim().isEmpty()) filled++; }
        }
        return filled * 100 / req.length;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
}
