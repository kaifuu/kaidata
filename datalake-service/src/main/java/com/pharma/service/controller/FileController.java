package com.pharma.service.controller;

import com.pharma.service.access.file.CsvLoader;
import com.pharma.service.access.file.FileClientFactory;
import com.pharma.service.access.util.CryptoUtil;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.sql.Timestamp;
import java.util.*;

/**
 * 文件管理 [SYS_ADMIN]：存储源 CRUD（FTP/SFTP/HDFS）+ 目录浏览/增删/复制/上传 + 文件接入（CSV/JSONL → StarRocks）。
 */
@RestController
@RequestMapping("/api/data-access/file")
@CrossOrigin(origins = "*")
public class FileController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private FileClientFactory factory;
    @Autowired private CryptoUtil crypto;
    @Autowired private CsvLoader csvLoader;

    // ==================== 存储源 CRUD ====================

    @GetMapping("/store/list")
    public List<Map<String, Object>> storeList() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, name, type, host, port, username, password, base_path, props, status, create_time " +
                "FROM meta.ing_filestore ORDER BY id");
        // 解密密码回传，供编辑表单回显（星号掩码 + 眼睛查看）；列表表格不展示该列
        rows.forEach(r -> r.put("password", crypto.decrypt(str(r.get("password")))));
        return rows;
    }

    @PostMapping("/store")
    public Map<String, Object> createStore(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        jdbc.update("INSERT INTO meta.ing_filestore" +
                        "(id, name, type, host, port, username, password, base_path, props, status, create_by, create_time, update_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("type")), str(b.get("host")), (int) lng(b.get("port")),
                str(b.get("username")), crypto.encrypt(str(b.get("password"))), str(b.get("base_path")),
                str(b.get("props")), str(b.getOrDefault("status", "NORMAL")), currentUser(), now, now);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/store")
    public Map<String, Object> updateStore(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        Object pwd = b.get("password");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (pwd != null && !String.valueOf(pwd).isEmpty() && !"***".equals(pwd)) {
            jdbc.update("UPDATE meta.ing_filestore SET name=?, type=?, host=?, port=?, username=?, password=?, " +
                            "base_path=?, props=?, status=?, update_time=? WHERE id=?",
                    str(b.get("name")), str(b.get("type")), str(b.get("host")), (int) lng(b.get("port")),
                    str(b.get("username")), crypto.encrypt(String.valueOf(pwd)), str(b.get("base_path")),
                    str(b.get("props")), str(b.getOrDefault("status", "NORMAL")), now, id);
        } else {
            jdbc.update("UPDATE meta.ing_filestore SET name=?, type=?, host=?, port=?, username=?, " +
                            "base_path=?, props=?, status=?, update_time=? WHERE id=?",
                    str(b.get("name")), str(b.get("type")), str(b.get("host")), (int) lng(b.get("port")),
                    str(b.get("username")), str(b.get("base_path")), str(b.get("props")),
                    str(b.getOrDefault("status", "NORMAL")), now, id);
        }
        return Map.of("success", true);
    }

    @DeleteMapping("/store")
    public Map<String, Object> deleteStore(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.ing_file WHERE store_id=?", id);
        jdbc.update("DELETE FROM meta.ing_filestore WHERE id=?", id);
        return Map.of("success", true);
    }

    @PostMapping("/store/test")
    public Map<String, Object> storeTest(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> store = (b.get("id") != null) ? loadStore(lng(b.get("id"))) : withPwd(b);
        return factory.get(str(store.get("type"))).testConnection(store);
    }

    // ==================== 文件操作 ====================

    @GetMapping("/browse")
    public List<Map<String, Object>> browse(@RequestParam long storeId, @RequestParam(defaultValue = "") String path) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> store = loadStore(storeId);
        return factory.get(str(store.get("type"))).list(store, path);
    }

    @PostMapping("/mkdir")
    public Map<String, Object> mkdir(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> store = loadStore(lng(b.get("store_id")));
        factory.get(str(store.get("type"))).mkdir(store, str(b.get("path")));
        return Map.of("success", true);
    }

    @DeleteMapping("/delete")
    public Map<String, Object> delete(@RequestParam long storeId, @RequestParam String path) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> store = loadStore(storeId);
        factory.get(str(store.get("type"))).delete(store, path);
        return Map.of("success", true);
    }

    @PostMapping("/copy")
    public Map<String, Object> copy(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> store = loadStore(lng(b.get("store_id")));
        factory.get(str(store.get("type"))).copy(store, str(b.get("src")), str(b.get("dst")));
        return Map.of("success", true);
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam long storeId, @RequestParam String path,
                                      @RequestParam("file") MultipartFile file) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> store = loadStore(storeId);
        try {
            factory.get(str(store.get("type"))).upload(store, path, file.getBytes());
            return Map.of("success", true, "size", file.getSize());
        } catch (Exception e) {
            throw new RuntimeException("上传失败：" + e.getMessage(), e);
        }
    }

    // ==================== 文件接入 ====================

    @GetMapping("/list-ingested")
    public List<Map<String, Object>> listIngested() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, store_id, path, name, size, file_type, target_table, rows_written, " +
                "ingested, create_time FROM meta.ing_file WHERE ingested = 1 ORDER BY id DESC LIMIT 200");
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long storeId = lng(b.get("store_id"));
        String path = str(b.get("path"));
        String fileType = str(b.getOrDefault("file_type", "csv"));
        String targetDb = "ods";
        String targetTable = str(b.get("target_table"));
        if (targetTable.isEmpty()) targetTable = "ods_file_" + Long.toHexString(System.currentTimeMillis()).substring(6);

        Map<String, Object> store = loadStore(storeId);
        byte[] data = factory.get(str(store.get("type"))).download(store, path);
        CsvLoader.Result r = csvLoader.ingest(data, fileType, targetDb, targetTable);

        long fileId = System.currentTimeMillis();
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        jdbc.update("INSERT INTO meta.ing_file(id, store_id, path, name, size, is_dir, file_type, target_table, " +
                        "rows_written, ingested, create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                fileId, storeId, path, name, (long) data.length, false, fileType,
                targetDb + "." + targetTable, r.rowsWritten, true, new Timestamp(fileId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("targetTable", targetDb + "." + targetTable);
        out.put("rowsWritten", r.rowsWritten);
        out.put("columns", r.columns);
        out.put("preview", r.preview);
        return out;
    }

    // ==================== 助手 ====================

    private Map<String, Object> loadStore(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, type, host, port, username, password, base_path, props FROM meta.ing_filestore WHERE id=?", id);
        row.put("password", crypto.decrypt(str(row.get("password"))));
        return row;
    }

    /** 测试未保存的存储源：直接用请求体（明文密码）。 */
    private static Map<String, Object> withPwd(Map<String, Object> b) {
        Map<String, Object> m = new LinkedHashMap<>(b);
        return m;
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
