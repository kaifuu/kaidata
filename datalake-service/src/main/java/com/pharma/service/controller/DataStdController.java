package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.security.AuthContext;
import com.pharma.service.security.Authz;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

/**
 * 数据标准 [SYS_ADMIN]：数据元 + 代码集 + 代码项。
 * <p>对标大数据平台：数据元可引用代码集作取值域（value_domain 联动生成）、
 * 支持分类/状态/关键字筛选、引用统计（数据元被模型字段引用、代码集被数据元引用）。
 * <p>P1 升级：① 改版存快照（gov_std_element_version，支持版本对比）；② 删除数据元时解除模型字段引用；
 * ③ 合规扫描增强（长度 + 代码集活检查）；④ 数据元 Excel 导入导出；⑤ 落标推荐（列名↔数据元相似度）。
 */
@RestController
@RequestMapping("/api/data-gov/std")
@CrossOrigin(origins = "*")
public class DataStdController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

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
        // 改版留痕：更新前把当前行快照存入版本表，version+1
        snapshotElement(id);
        jdbc.update("UPDATE meta.gov_data_element SET code=?, name=?, en_name=?, category=?, data_type=?, length=?, precision_=?, scale_=?, " +
                        "unit=?, data_format=?, security_level=?, owner=?, code_set_id=?, definition=?, value_domain=?, status=?, version=version+1, update_time=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("en_name")), str(b.get("category")),
                str(b.get("data_type")), num(b.get("length")), num(b.get("precision_")), num(b.get("scale_")),
                str(b.get("unit")), str(b.get("data_format")), str(b.get("security_level")), str(b.get("owner")),
                codeSetId, str(b.get("definition")), valueDomain, str(b.getOrDefault("status", "NORMAL")), now, id);
        return Map.of("success", true);
    }

    @DeleteMapping("/element")
    public Map<String, Object> deleteElement(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        // 修一致性 bug：删除前解除模型字段引用，避免 element_id 悬挂、引用统计失真（与 deleteCodeSet 行为对齐）
        jdbc.update("UPDATE meta.gov_model_field SET element_id=0 WHERE element_id=?", id);
        jdbc.update("DELETE FROM meta.gov_std_landing WHERE element_id=?", id);
        jdbc.update("DELETE FROM meta.gov_data_element WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 更新前快照：把当前整行（版本 v）存入 gov_std_element_version。 */
    private void snapshotElement(long id) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, code, name, en_name, category, data_type, length, precision_, scale_, unit, data_format, " +
                            "security_level, owner, code_set_id, definition, value_domain, version, status FROM meta.gov_data_element WHERE id=?", id);
            if (rows.isEmpty()) return;
            Map<String, Object> row = rows.get(0);
            int ver = row.get("version") == null ? 1 : ((Number) row.get("version")).intValue();
            jdbc.update("INSERT INTO meta.gov_std_element_version(id, element_id, version_n, snapshot_json, change_detail, create_by, create_time) VALUES (?,?,?,?,?,?,?)",
                    System.currentTimeMillis(), id, ver, json.writeValueAsString(row), "修改前快照", AuthContext.username(), new Timestamp(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    /** 数据元版本列表（快照按版本倒序）。 */
    @GetMapping("/element/versions")
    public List<Map<String, Object>> elementVersions(@RequestParam long elementId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, element_id, version_n, change_detail, create_by, create_time " +
                "FROM meta.gov_std_element_version WHERE element_id=? ORDER BY version_n DESC", elementId);
    }

    /** 版本对比：两份快照逐字段 diff（只返回差异项）。 */
    @GetMapping("/element/version-compare")
    @SuppressWarnings("unchecked")
    public Map<String, Object> elementVersionCompare(@RequestParam long elementId, @RequestParam int v1, @RequestParam int v2) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> s1 = versionSnapshot(elementId, v1);
        Map<String, Object> s2 = versionSnapshot(elementId, v2);
        List<Map<String, Object>> changed = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        if (s1 != null) keys.addAll(s1.keySet());
        if (s2 != null) keys.addAll(s2.keySet());
        for (String k : keys) {
            String a = s1 == null || s1.get(k) == null ? "" : String.valueOf(s1.get(k));
            String b2 = s2 == null || s2.get(k) == null ? "" : String.valueOf(s2.get(k));
            if (!a.equals(b2)) changed.add(Map.of("field", k, "old", a, "new", b2));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v1", v1);
        out.put("v2", v2);
        out.put("v1Snapshot", s1);
        out.put("v2Snapshot", s2);
        out.put("changed", changed);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> versionSnapshot(long elementId, int versionN) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT snapshot_json FROM meta.gov_std_element_version WHERE element_id=? AND version_n=? ORDER BY id DESC LIMIT 1", elementId, versionN);
            if (rows.isEmpty()) return null;
            return json.readValue(str(rows.get(0).get("snapshot_json")), Map.class);
        } catch (Exception e) { return null; }
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

    /**
     * 合规扫描（增强版）：已落标模型字段三层检查。
     * <ul>
     *   <li>类型基名：field VARCHAR(4) vs element VARCHAR → 基名不一致即 FAIL</li>
     *   <li>长度：field 类型括号里的长度 > 数据元 length → FAIL（超长风险）</li>
     *   <li>live=true 时：对 gov_std_landing 每条落标跑真实数据检查——
     *       代码集 → COUNT(col NOT IN 代码集)；长度 → COUNT(LENGTH(col)>len)</li>
     * </ul>
     */
    @GetMapping("/compliance-scan")
    public Map<String, Object> complianceScan(@RequestParam(required = false, defaultValue = "false") boolean live) {
        Authz.require(Authz.SYS_ADMIN);
        // ---- 静态层：模型字段 vs 数据元（类型基名 + 长度） ----
        List<Map<String, Object>> rows = safeList(
                "SELECT f.name AS field, f.data_type AS field_type, e.name AS element, e.data_type AS element_type, " +
                        "e.length AS element_length, t.name AS table_name " +
                        "FROM meta.gov_model_field f JOIN meta.gov_data_element e ON e.id=f.element_id " +
                        "LEFT JOIN meta.gov_model_table t ON t.id=f.table_id WHERE f.element_id>0");
        List<Map<String, Object>> fail = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String fieldType = str(r.get("field_type"));
            String elemType = str(r.get("element_type"));
            if (!baseName(fieldType).equalsIgnoreCase(baseName(elemType))) {
                Map<String, Object> f = new LinkedHashMap<>(r);
                f.put("check_type", "TYPE");
                f.put("reason", "类型基名不一致: " + baseName(fieldType) + " vs " + baseName(elemType));
                fail.add(f);
                continue;
            }
            int elemLen = r.get("element_length") == null ? 0 : ((Number) r.get("element_length")).intValue();
            int fieldLen = parenLen(fieldType);
            if (elemLen > 0 && fieldLen > 0 && fieldLen > elemLen) {
                Map<String, Object> f = new LinkedHashMap<>(r);
                f.put("check_type", "LENGTH");
                f.put("reason", "字段长度 " + fieldLen + " 超出数据元定义 " + elemLen);
                fail.add(f);
            }
        }

        // ---- 活检层：落标物理列真实取值检查（live=true） ----
        List<Map<String, Object>> liveFails = new ArrayList<>();
        List<Map<String, Object>> liveChecked = new ArrayList<>();
        if (live) {
            for (Map<String, Object> l : safeList(
                    "SELECT l.element_id, l.ds_id, l.table_name, l.column_name, e.name AS element, e.code_set_id, e.length " +
                            "FROM meta.gov_std_landing l LEFT JOIN meta.gov_data_element e ON e.id=l.element_id")) {
                long codeSetId = lng(l.get("code_set_id"));
                int len = l.get("length") == null ? 0 : ((Number) l.get("length")).intValue();
                String expr = null;
                String check = null;
                if (codeSetId > 0) {
                    List<String> codes = new ArrayList<>();
                    for (Map<String, Object> it : safeList("SELECT code FROM meta.gov_code_item WHERE set_id=? AND is_enabled", codeSetId))
                        codes.add("'" + str(it.get("code")).replace("'", "''") + "'");
                    if (!codes.isEmpty()) { expr = str(l.get("column_name")) + " NOT IN (" + String.join(",", codes) + ")"; check = "ENUM"; }
                } else if (len > 0) {
                    expr = "LENGTH(" + str(l.get("column_name")) + ") > " + len;
                    check = "LENGTH";
                }
                if (expr == null) continue;
                long violate = qCountLive(lng(l.get("ds_id")), "SELECT COUNT(*) FROM " + str(l.get("table_name")) + " WHERE " + expr);
                Map<String, Object> item = new LinkedHashMap<>(l);
                item.put("check_type", check);
                item.put("violate", violate);
                liveChecked.add(item);
                if (violate > 0) {
                    item.put("reason", "真实数据存在 " + violate + " 条违规（" + check + "）");
                    liveFails.add(item);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("pass", rows.size() - fail.size());
        out.put("fail", fail.size());
        out.put("failList", fail);
        out.put("live", live);
        out.put("liveChecked", liveChecked.size());
        out.put("liveFail", liveFails.size());
        out.put("liveFailList", liveFails);
        return out;
    }

    /** 按数据源跑一条 COUNT(*)（失败返回 -1，不阻塞扫描）。 */
    private long qCountLive(long dsId, String sql) {
        try {
            DataSourceDescriptor ds = loader.load(dsId);
            registry.adapter(ds.type);
            try (Connection c = registry.getPool(ds).getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) { return -1; }
    }

    /** 括号里的长度值：VARCHAR(64) → 64；无括号/解析失败 → 0。 */
    private static int parenLen(String type) {
        if (type == null) return 0;
        int i = type.indexOf('('), j = type.indexOf(')');
        if (i < 0 || j <= i) return 0;
        try { return Integer.parseInt(type.substring(i + 1, j).split(",")[0].trim()); } catch (Exception e) { return 0; }
    }

    private static String baseName(String t) {
        if (t == null) return "";
        int i = t.indexOf('(');
        return (i < 0 ? t : t.substring(0, i)).trim().toUpperCase();
    }

    // ==================== 标准落标 → 派生质量规则（标准↔质量通道） ====================

    /** 可落标数据源下拉（质量规则执行取数用）。 */
    @GetMapping("/datasources")
    public List<Map<String, Object>> datasources() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, type, host, port, db_name FROM meta.ing_datasource ORDER BY id");
    }

    // ==================== 数据元 Excel 导入导出 ====================

    private static final String[] EXCEL_HEADERS = {"编码", "名称", "英文名", "分类", "类型", "长度", "精度", "小数位", "单位", "格式",
            "安全分级", "负责人", "状态", "取值域", "定义"};

    /** 导出全部数据元为 Excel(.xlsx)。 */
    @GetMapping("/element/excel")
    public ResponseEntity<byte[]> elementExcel() throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT code, name, en_name, category, data_type, length, precision_, scale_, unit, data_format, security_level, owner, status, value_domain, definition " +
                        "FROM meta.gov_data_element ORDER BY id");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("数据元");
            Row hr = sheet.createRow(0);
            for (int i = 0; i < EXCEL_HEADERS.length; i++) hr.createCell(i).setCellValue(EXCEL_HEADERS[i]);
            int r = 1;
            for (Map<String, Object> e : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(str(e.get("code")));
                row.createCell(1).setCellValue(str(e.get("name")));
                row.createCell(2).setCellValue(str(e.get("en_name")));
                row.createCell(3).setCellValue(str(e.get("category")));
                row.createCell(4).setCellValue(str(e.get("data_type")));
                row.createCell(5).setCellValue(num(e.get("length")));
                row.createCell(6).setCellValue(num(e.get("precision_")));
                row.createCell(7).setCellValue(num(e.get("scale_")));
                row.createCell(8).setCellValue(str(e.get("unit")));
                row.createCell(9).setCellValue(str(e.get("data_format")));
                row.createCell(10).setCellValue(str(e.get("security_level")));
                row.createCell(11).setCellValue(str(e.get("owner")));
                row.createCell(12).setCellValue(str(e.get("status")));
                row.createCell(13).setCellValue(str(e.get("value_domain")));
                row.createCell(14).setCellValue(str(e.get("definition")));
            }
            wb.write(out);
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode("数据元.xlsx", "UTF-8").replace("+", "%20"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    /** Excel 批量导入：按编码 upsert（存在→更新，不存在→新增），返回计数。 */
    @PostMapping("/element/import")
    public Map<String, Object> elementImport(@RequestParam("file") MultipartFile file) throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        int inserted = 0, updated = 0;
        try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
            XSSFSheet sheet = wb.getSheetAt(0);
            Row hr = sheet.getRow(0);
            if (hr == null) throw new IllegalArgumentException("Excel 为空");
            Map<String, Integer> colIdx = new HashMap<>();
            for (int i = 0; i < EXCEL_HEADERS.length; i++) colIdx.put(EXCEL_HEADERS[i], -1);
            for (Cell c : hr) {
                if (c.getCellType() == CellType.STRING && colIdx.containsKey(c.getStringCellValue().trim()))
                    colIdx.put(c.getStringCellValue().trim(), c.getColumnIndex());
            }
            if (colIdx.get("编码") < 0 || colIdx.get("名称") < 0)
                throw new IllegalArgumentException("表头必须包含「编码」「名称」列");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String code = cellStr(row, colIdx.get("编码"));
                String name = cellStr(row, colIdx.get("名称"));
                if (code.isEmpty() || name.isEmpty()) continue;
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("code", code);
                b.put("name", name);
                b.put("en_name", cellStr(row, colIdx.get("英文名")));
                b.put("category", cellStr(row, colIdx.get("分类")));
                b.put("data_type", defaultStr(cellStr(row, colIdx.get("类型")), "VARCHAR"));
                b.put("length", cellNum(row, colIdx.get("长度")));
                b.put("precision_", cellNum(row, colIdx.get("精度")));
                b.put("scale_", cellNum(row, colIdx.get("小数位")));
                b.put("unit", cellStr(row, colIdx.get("单位")));
                b.put("data_format", cellStr(row, colIdx.get("格式")));
                b.put("security_level", cellStr(row, colIdx.get("安全分级")));
                b.put("owner", cellStr(row, colIdx.get("负责人")));
                b.put("status", defaultStr(cellStr(row, colIdx.get("状态")), "NORMAL"));
                b.put("value_domain", cellStr(row, colIdx.get("取值域")));
                b.put("definition", cellStr(row, colIdx.get("定义")));
                List<Map<String, Object>> exist = jdbc.queryForList("SELECT id FROM meta.gov_data_element WHERE code=?", code);
                if (exist.isEmpty()) { b.remove("id"); createElement(b); inserted++; }
                else { b.put("id", lng(exist.get(0).get("id"))); updateElement(b); updated++; }
            }
        }
        return Map.of("success", true, "inserted", inserted, "updated", updated);
    }

    private static String cellStr(Row row, int idx) {
        if (idx < 0) return "";
        Cell c = row.getCell(idx);
        if (c == null) return "";
        try {
            return switch (c.getCellType()) {
                case STRING -> c.getStringCellValue().trim();
                case NUMERIC -> (long) c.getNumericCellValue() + "";
                case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
                default -> "";
            };
        } catch (Exception e) { return ""; }
    }
    private static int cellNum(Row row, int idx) {
        String s = cellStr(row, idx);
        try { return s.isEmpty() ? 0 : Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
    private static String defaultStr(String s, String def) { return s == null || s.isEmpty() ? def : s; }

    // ==================== 落标推荐（列名 ↔ 数据元相似度） ====================

    /**
     * 落标推荐：对某元数据表的每个物理列，按名称相似度推荐 Top3 数据元。
     * 评分：列名==数据元名 100 / ==英文名 95 / 包含 80 / 分词重合率×70；≥60 才推荐。
     * 前端确认后调 /element/land 落标。
     */
    @GetMapping("/element/recommend")
    public Map<String, Object> recommend(@RequestParam long metaId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> meta = jdbc.queryForMap("SELECT ds_id, schema_name, table_name, columns_json FROM meta.gov_meta_table WHERE id=?", metaId);
        List<Map<String, Object>> elements = jdbc.queryForList(
                "SELECT id, code, name, en_name, category, data_type FROM meta.gov_data_element WHERE status='NORMAL'");
        String tableName = str(meta.get("schema_name")).isEmpty() ? str(meta.get("table_name")) : str(meta.get("schema_name")) + "." + str(meta.get("table_name"));
        List<Map<String, Object>> columns = new ArrayList<>();
        try {
            List<?> cols = json.readValue(str(meta.get("columns_json")), List.class);
            for (Object o : cols) {
                String cn, ct, cm;
                if (o instanceof Map) { Map<?, ?> c = (Map<?, ?>) o; cn = str(c.get("name")); ct = str(c.get("type")); cm = str(c.get("comment")); }
                else { cn = str(o); ct = ""; cm = ""; }
                if (cn.isEmpty()) continue;
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column", cn);
                col.put("type", ct);
                col.put("comment", cm);
                col.put("suggestions", topMatches(cn, elements, 3));
                columns.add(col);
            }
        } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metaId", metaId);
        out.put("dsId", lng(meta.get("ds_id")));
        out.put("tableName", tableName);
        out.put("columns", columns);
        return out;
    }

    private List<Map<String, Object>> topMatches(String colName, List<Map<String, Object>> elements, int topN) {
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> e : elements) {
            int s = similarity(colName, str(e.get("name")), str(e.get("en_name")));
            if (s >= 60) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("element_id", lng(e.get("id")));
                m.put("code", str(e.get("code")));
                m.put("name", str(e.get("name")));
                m.put("category", str(e.get("category")));
                m.put("data_type", str(e.get("data_type")));
                m.put("score", s);
                scored.add(m);
            }
        }
        scored.sort((a, b2) -> ((Number) b2.get("score")).intValue() - ((Number) a.get("score")).intValue());
        return scored.size() > topN ? new ArrayList<>(scored.subList(0, topN)) : scored;
    }

    /** 相似度评分 0-100：精确=100/95，包含=80，分词重合率×70（英文/驼峰/下划线分词）。 */
    static int similarity(String col, String name, String enName) {
        String c = norm(col);
        if (c.isEmpty()) return 0;
        if (!name.isEmpty()) {
            String n = norm(name);
            if (c.equals(n)) return 100;
            if (n.length() >= 2 && (c.contains(n) || n.contains(c))) return 80;
        }
        if (!enName.isEmpty()) {
            String e = norm(enName);
            if (c.equals(e)) return 95;
            if (e.length() >= 2 && (c.contains(e) || e.contains(c))) return 75;
        }
        Set<String> ct = tokens(col);
        Set<String> nt = new HashSet<>();
        if (!name.isEmpty()) nt.addAll(tokens(name));
        if (!enName.isEmpty()) nt.addAll(tokens(enName));
        if (ct.isEmpty() || nt.isEmpty()) return 0;
        int hit = 0;
        for (String t : ct) if (nt.contains(t)) hit++;
        return (int) (hit * 70.0 / ct.size());
    }

    private static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(); }

    /** 分词：下划线/驼峰切开，去停用词（id/no/type 等通用词干扰大）。 */
    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.trim().toLowerCase().split("[_\\s]+")) {
            for (String w : t.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase().split("\\s+")) {
                if (w.length() >= 2 && !Set.of("id", "no", "num", "type", "code", "name", "flag", "status", "time", "date", "data").contains(w))
                    out.add(w);
            }
        }
        return out;
    }

    /**
     * 落标：把数据元绑定到物理列，并按数据元特征派生可执行的质量规则。
     * <p>code_set_id>0 → CONSISTENCY 枚举规则（{col} NOT IN 代码集）；否则 length>0 → VALIDITY 长度规则。
     * 同(element,table,col)幂等刷新：先删旧派生规则与登记，再重建。
     */
    @PostMapping("/element/land")
    public Map<String, Object> land(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long elementId = lng(b.get("elementId"));
        long dsId = lng(b.get("dsId"));
        String table = str(b.get("tableName"));
        String col = str(b.get("columnName"));
        if (elementId <= 0 || dsId <= 0 || table.isEmpty() || col.isEmpty()) {
            throw new IllegalArgumentException("elementId/dsId/tableName/columnName 不能为空");
        }
        // 标识符校验（防注入）：列名单段；表名按 '.' 分段，至多两段
        StarRocksDdlBuilder.ident(col);
        String[] segs = table.split("\\.", -1);
        if (segs.length < 1 || segs.length > 2) throw new IllegalArgumentException("非法表名: " + table);
        for (String s : segs) StarRocksDdlBuilder.ident(s);

        // 取数据元
        Map<String, Object> el = jdbc.queryForMap(
                "SELECT name, code_set_id, length, data_type, security_level FROM meta.gov_data_element WHERE id=?", elementId);
        long codeSetId = lng(el.get("code_set_id"));
        int length = num(el.get("length"));
        String elName = str(el.get("name"));

        // 幂等刷新：删同(element,table,col)旧登记及其派生规则
        List<Map<String, Object>> old = jdbc.queryForList(
                "SELECT rule_ids FROM meta.gov_std_landing WHERE element_id=? AND table_name=? AND column_name=?", elementId, table, col);
        for (Map<String, Object> r : old) {
            for (String rid : str(r.get("rule_ids")).split(",")) {
                try { jdbc.update("DELETE FROM meta.gov_quality_rule WHERE id=?", Long.parseLong(rid.trim())); } catch (Exception ignored) {}
            }
        }
        jdbc.update("DELETE FROM meta.gov_std_landing WHERE element_id=? AND table_name=? AND column_name=?", elementId, table, col);

        // 派生规则（枚举优先，否则长度）
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        List<Long> ruleIds = new ArrayList<>();
        List<Map<String, Object>> rules = new ArrayList<>();
        String sev = severityOf(el.get("security_level"));
        if (codeSetId > 0) {
            List<Map<String, Object>> items = jdbc.queryForList(
                    "SELECT code FROM meta.gov_code_item WHERE set_id=? AND is_enabled ORDER BY sort, id", codeSetId);
            StringBuilder inList = new StringBuilder();
            for (Map<String, Object> it : items) {
                if (inList.length() > 0) inList.append(",");
                inList.append("'").append(str(it.get("code")).replace("'", "''")).append("'");
            }
            String expr = inList.length() == 0 ? "" : col + " NOT IN (" + inList + ")";
            String rname = "[落标] " + elName + " 枚举合规 @ " + table + "." + col;
            jdbc.update("INSERT INTO meta.gov_quality_rule(id, name, dimension, ds_id, table_name, column_name, expression, threshold, severity, description, status, create_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, rname, "CONSISTENCY", dsId, table, col, expr, 0.0, sev,
                    "数据元[" + elName + "]落标自动派生：取值必须在代码集内", "ENABLED", now);
            ruleIds.add(id);
            rules.add(ruleView(id, rname, "CONSISTENCY", expr));
        } else if (length > 0) {
            String expr = "LENGTH(" + col + ") > " + length;
            String rname = "[落标] " + elName + " 长度合规 @ " + table + "." + col;
            jdbc.update("INSERT INTO meta.gov_quality_rule(id, name, dimension, ds_id, table_name, column_name, expression, threshold, severity, description, status, create_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, rname, "VALIDITY", dsId, table, col, expr, 0.0, sev,
                    "数据元[" + elName + "]落标自动派生：长度不得超过 " + length, "ENABLED", now);
            ruleIds.add(id);
            rules.add(ruleView(id, rname, "VALIDITY", expr));
        }

        // 登记落标
        long landingId = id + 100;
        String ruleCsv = ruleIds.stream().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse("");
        jdbc.update("INSERT INTO meta.gov_std_landing(id, element_id, ds_id, table_name, column_name, rule_ids, create_time) VALUES (?,?,?,?,?,?,?)",
                landingId, elementId, dsId, table, col, ruleCsv, now);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("landingId", landingId);
        out.put("ruleIds", ruleCsv);
        out.put("rules", rules);
        return out;
    }

    /** 某数据元的落标清单（关联数据源名，供前端展示）。 */
    @GetMapping("/element/landings")
    public List<Map<String, Object>> landings(@RequestParam long elementId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList(
                "SELECT l.id, l.element_id, l.ds_id, l.table_name, l.column_name, l.rule_ids, l.create_time, " +
                        "d.name AS ds_name FROM meta.gov_std_landing l " +
                        "LEFT JOIN meta.ing_datasource d ON d.id=l.ds_id WHERE l.element_id=? ORDER BY l.id", elementId);
    }

    /** 解除落标：删登记 + 派生规则。 */
    @DeleteMapping("/landing")
    public Map<String, Object> deleteLanding(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT rule_ids FROM meta.gov_std_landing WHERE id=?", id);
        for (Map<String, Object> r : rows) {
            for (String rid : str(r.get("rule_ids")).split(",")) {
                try { jdbc.update("DELETE FROM meta.gov_quality_rule WHERE id=?", Long.parseLong(rid.trim())); } catch (Exception ignored) {}
            }
        }
        jdbc.update("DELETE FROM meta.gov_std_landing WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String severityOf(Object securityLevel) {
        String s = securityLevel == null ? "" : String.valueOf(securityLevel);
        return switch (s) { case "SENSITIVE" -> "CRITICAL"; case "INTERNAL" -> "MAJOR"; case "PUBLIC" -> "MINOR"; default -> "MAJOR"; };
    }
    private static Map<String, Object> ruleView(long id, String name, String dimension, String expression) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("dimension", dimension); m.put("expression", expression);
        return m;
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
