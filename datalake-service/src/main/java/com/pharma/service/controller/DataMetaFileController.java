package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 文件元数据 [SYS_ADMIN]：对文件资源（ing_filestore/ing_file）补录元数据 + 文件 schema。
 */
@RestController
@RequestMapping("/api/data-gov/meta/file")
@CrossOrigin(origins = "*")
public class DataMetaFileController {

    @Autowired private JdbcTemplate jdbc;

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long storeId,
                                          @RequestParam(required = false) String kw) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT f.id, f.cn_name, f.path, f.store_id, f.subject_id, f.share_type, f.admin_owner, " +
                "f.data_category, f.security_level, f.fill_percent, f.mount_status, f.file_format, " +
                "st.name AS store_name, st.type AS store_type " +
                "FROM meta.gov_meta_file f LEFT JOIN meta.ing_filestore st ON st.id=f.store_id WHERE 1=1";
        List<Object> args = new ArrayList<>();
        if (storeId != null) { sql += " AND f.store_id=?"; args.add(storeId); }
        if (kw != null && !kw.isEmpty()) {
            sql += " AND (f.path LIKE ? OR f.cn_name LIKE ?)";
            String p = "%" + kw + "%"; args.add(p); args.add(p);
        }
        sql += " ORDER BY f.id LIMIT 500";
        return jdbc.queryForList(sql, args.toArray());
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT f.*, st.name AS store_name " +
                "FROM meta.gov_meta_file f LEFT JOIN meta.ing_filestore st ON st.id=f.store_id WHERE f.id=?", id);
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        int fill = calcFill(b);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        String colsJson = str(b.get("columns_json"));
        if (colsJson.length() > 65000) colsJson = colsJson.substring(0, 65000);
        if (id == 0) {
            id = System.currentTimeMillis() + (long) (Math.random() * 1000);
            jdbc.update("INSERT INTO meta.gov_meta_file(id, file_id, store_id, path, cn_name, dept, app_system, subject_id, share_type, " +
                            "admin_owner, admin_contact, data_category, security_level, description, fill_percent, mount_status, " +
                            "file_format, encoding, delimiter, has_header, columns_json, create_time, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, lng(b.get("file_id")), lng(b.get("store_id")), str(b.get("path")), str(b.get("cn_name")), str(b.get("dept")),
                    str(b.get("app_system")), lng(b.get("subject_id")), str(b.get("share_type")), str(b.get("admin_owner")),
                    str(b.get("admin_contact")), str(b.get("data_category")), str(b.get("security_level")), str(b.get("description")),
                    fill, "NONE", str(b.get("file_format")), str(b.get("encoding")), str(b.get("delimiter")),
                    bool(b.get("has_header")), colsJson, now, now);
        } else {
            jdbc.update("UPDATE meta.gov_meta_file SET cn_name=?, dept=?, app_system=?, subject_id=?, share_type=?, admin_owner=?, " +
                            "admin_contact=?, data_category=?, security_level=?, description=?, fill_percent=?, file_format=?, encoding=?, " +
                            "delimiter=?, has_header=?, columns_json=?, update_time=? WHERE id=?",
                    str(b.get("cn_name")), str(b.get("dept")), str(b.get("app_system")), lng(b.get("subject_id")), str(b.get("share_type")),
                    str(b.get("admin_owner")), str(b.get("admin_contact")), str(b.get("data_category")), str(b.get("security_level")),
                    str(b.get("description")), fill, str(b.get("file_format")), str(b.get("encoding")), str(b.get("delimiter")),
                    bool(b.get("has_header")), colsJson, now, id);
        }
        return Map.of("success", true, "id", id, "fill_percent", fill);
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_meta_file WHERE id=?", id);
        return Map.of("success", true);
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

    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
