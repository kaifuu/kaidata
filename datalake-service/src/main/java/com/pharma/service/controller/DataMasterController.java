package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.security.AuthContext;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 主数据 [SYS_ADMIN]：主数据定义（字段定义 JSON）+ 主数据记录 + 变更审计 + 引用。
 * <p>P0 升级：记录按字段定义校验（必填/类型）+ 编码字段查重；增删改全量审计
 * （gov_master_audit 存前后值）；引用统计（模型字段/元数据列引用了本主数据的编码字段）。
 * <p>gov_master_record 为 DUPLICATE KEY：不支持 UPDATE，编辑走 DELETE+INSERT（审计留痕）。
 */
@RestController
@RequestMapping("/api/data-gov/master")
@CrossOrigin(origins = "*")
public class DataMasterController {

    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    // ===== 主数据定义 =====
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList(
                "SELECT m.id, m.code, m.name, m.description, m.fields_json, m.create_time, " +
                "COALESCE(r.cnt, 0) AS record_count FROM meta.gov_master m " +
                "LEFT JOIN (SELECT master_id, COUNT(*) AS cnt FROM meta.gov_master_record GROUP BY master_id) r ON r.master_id = m.id " +
                "ORDER BY m.id");
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        validateFields(str(b.get("fields_json")));
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_master(id, code, name, description, fields_json, create_time) VALUES (?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), str(b.get("description")), str(b.get("fields_json")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        validateFields(str(b.get("fields_json")));
        jdbc.update("UPDATE meta.gov_master SET code=?, name=?, description=?, fields_json=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("description")), str(b.get("fields_json")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_master_record WHERE master_id=?", id);
        jdbc.update("DELETE FROM meta.gov_master_audit WHERE master_id=?", id);
        jdbc.update("DELETE FROM meta.gov_master WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 主数据记录（动态表单校验 + 编码查重 + 审计） =====
    @GetMapping("/record")
    public List<Map<String, Object>> listRecord(@RequestParam long masterId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, master_id, data_json, create_time FROM meta.gov_master_record WHERE master_id=? ORDER BY id DESC", masterId);
    }

    /** 新增记录：data_json 按字段定义校验（必填/类型），编码字段查重，写 CREATE 审计。 */
    @PostMapping("/record")
    public Map<String, Object> createRecord(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long masterId = lng(b.get("master_id"));
        Map<String, Object> def = masterDef(masterId);
        List<Map<String, String>> fields = parseFields(str(def.get("fields_json")));
        String dataJson = normalizeData(str(b.get("data_json")), fields);
        checkDuplicate(masterId, fields, dataJson, 0);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_master_record(id, master_id, data_json, create_time) VALUES (?,?,?,?)",
                id, masterId, dataJson, new Timestamp(id));
        audit(masterId, id, "CREATE", null, dataJson);
        return Map.of("success", true, "id", id);
    }

    /** 编辑记录：DUPLICATE KEY 不支持 UPDATE → DELETE+INSERT；写 UPDATE 审计（含旧值）。 */
    @PutMapping("/record")
    public Map<String, Object> updateRecord(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        List<Map<String, Object>> old = jdbc.queryForList("SELECT master_id, data_json FROM meta.gov_master_record WHERE id=?", id);
        if (old.isEmpty()) throw new IllegalArgumentException("记录不存在: " + id);
        long masterId = lng(old.get(0).get("master_id"));
        String oldJson = str(old.get(0).get("data_json"));
        List<Map<String, String>> fields = parseFields(str(masterDef(masterId).get("fields_json")));
        String dataJson = normalizeData(str(b.get("data_json")), fields);
        checkDuplicate(masterId, fields, dataJson, id);
        jdbc.update("DELETE FROM meta.gov_master_record WHERE id=?", id);
        jdbc.update("INSERT INTO meta.gov_master_record(id, master_id, data_json, create_time) VALUES (?,?,?,?)",
                id, masterId, dataJson, new Timestamp(System.currentTimeMillis()));
        audit(masterId, id, "UPDATE", oldJson, dataJson);
        return Map.of("success", true);
    }

    @DeleteMapping("/record")
    public Map<String, Object> deleteRecord(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> old = jdbc.queryForList("SELECT master_id, data_json FROM meta.gov_master_record WHERE id=?", id);
        jdbc.update("DELETE FROM meta.gov_master_record WHERE id=?", id);
        if (!old.isEmpty()) audit(lng(old.get(0).get("master_id")), id, "DELETE", str(old.get(0).get("data_json")), null);
        return Map.of("success", true);
    }

    /** 编码实时查重（表单失焦即调）：dup=true 时带冲突记录 id。 */
    @GetMapping("/record/check-duplicate")
    public Map<String, Object> checkDuplicateApi(@RequestParam long masterId,
                                                 @RequestParam(defaultValue = "0") long recordId,
                                                 @RequestParam String code) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> def = masterDef(masterId);
        List<Map<String, String>> fields = parseFields(str(def.get("fields_json")));
        String codeField = codeFieldOf(fields);
        if (codeField.isEmpty()) return Map.of("dup", false, "codeField", "");
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, data_json FROM meta.gov_master_record WHERE master_id=?", masterId)) {
            if (lng(r.get("id")) == recordId) continue;
            if (code.equals(jsonValue(str(r.get("data_json")), codeField))) hits.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dup", !hits.isEmpty());
        out.put("codeField", codeField);
        out.put("conflictId", hits.isEmpty() ? 0 : lng(hits.get(0).get("id")));
        return out;
    }

    /** 变更审计列表（该主数据下全部记录操作留痕）。 */
    @GetMapping("/record/audit")
    public List<Map<String, Object>> recordAudit(@RequestParam long masterId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, master_id, record_id, action, old_json, new_json, operator, create_time " +
                "FROM meta.gov_master_audit WHERE master_id=? ORDER BY id DESC LIMIT 200", masterId);
    }

    /** 引用统计：模型字段（注释/名称引用编码字段）+ 元数据列。供前端展示"被谁引用"。 */
    @GetMapping("/refs")
    public Map<String, Object> refs(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> m = masterDef(id);
        String code = str(m.get("code"));
        List<String> fieldNames = new ArrayList<>();
        for (Map<String, String> f : parseFields(str(m.get("fields_json")))) fieldNames.add(f.get("name"));
        List<Map<String, Object>> modelRefs = jdbc.queryForList(
                "SELECT f.name AS field_name, f.comment, t.name AS table_name, mo.name AS model_name " +
                        "FROM meta.gov_model_field f LEFT JOIN meta.gov_model_table t ON t.id=f.table_id " +
                        "LEFT JOIN meta.gov_model mo ON mo.id=t.model_id " +
                        "WHERE f.comment LIKE CONCAT('%', ?, '%') OR f.name LIKE CONCAT('%', ?, '%') ORDER BY f.id LIMIT 100", code, code);
        List<Map<String, Object>> metaRefs = jdbc.queryForList(
                "SELECT table_name, schema_name, columns_json FROM meta.gov_meta_table WHERE columns_json LIKE CONCAT('%', ?, '%') LIMIT 100", code);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("modelRefs", modelRefs);
        out.put("metaRefs", metaRefs);
        return out;
    }

    // ===== 助手 =====

    private Map<String, Object> masterDef(long masterId) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT id, code, name, fields_json FROM meta.gov_master WHERE id=?", masterId);
        if (r.isEmpty()) throw new IllegalArgumentException("主数据不存在: " + masterId);
        return r.get(0);
    }

    /** 字段定义 JSON 校验：必须是 [{name,type,...}] 数组且 name/type 非空。 */
    private void validateFields(String fieldsJson) {
        List<Map<String, String>> fields = parseFields(fieldsJson);
        Set<String> seen = new HashSet<>();
        for (Map<String, String> f : fields) {
            if (!seen.add(f.get("name"))) throw new IllegalArgumentException("字段定义重复: " + f.get("name"));
        }
    }

    private List<Map<String, String>> parseFields(String fieldsJson) {
        try {
            List<?> raw = json.readValue(fieldsJson == null || fieldsJson.isBlank() ? "[]" : fieldsJson, List.class);
            List<Map<String, String>> out = new ArrayList<>();
            for (Object o : raw) {
                if (!(o instanceof Map)) throw new IllegalArgumentException("字段定义元素必须是对象");
                Map<String, String> f = new LinkedHashMap<>();
                Map<?, ?> m = (Map<?, ?>) o;
                f.put("name", str(m.get("name")));
                f.put("type", str(m.get("type")));
                f.put("required", String.valueOf(m.get("required")));
                if (f.get("name").isEmpty() || f.get("type").isEmpty())
                    throw new IllegalArgumentException("字段定义的 name/type 不能为空");
                out.add(f);
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("字段定义 JSON 解析失败: " + e.getMessage());
        }
    }

    /** 记录数据校验与规范化：JSON 对象；必填缺失拦截；按字段类型做轻校验；输出紧凑 JSON。 */
    private String normalizeData(String dataJson, List<Map<String, String>> fields) {
        Map<String, Object> data;
        try {
            @SuppressWarnings("unchecked") Map<String, Object> parsed = json.readValue(dataJson == null || dataJson.isBlank() ? "{}" : dataJson, Map.class);
            data = parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("记录数据必须是 JSON 对象: " + e.getMessage());
        }
        for (Map<String, String> f : fields) {
            String name = f.get("name");
            Object v = data.get(name);
            boolean required = "true".equalsIgnoreCase(f.get("required"));
            if ((v == null || String.valueOf(v).isEmpty())) {
                if (required) throw new IllegalArgumentException("必填字段缺失: " + name);
                continue;
            }
            String type = f.get("type").toUpperCase();
            if (type.startsWith("INT") || type.startsWith("BIGINT") || type.startsWith("LONG")) {
                try { Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { throw new IllegalArgumentException(name + " 应为整数: " + v); }
            } else if (type.startsWith("DECIMAL") || type.startsWith("DOUBLE") || type.startsWith("FLOAT")) {
                try { Double.parseDouble(String.valueOf(v).trim()); } catch (Exception e) { throw new IllegalArgumentException(name + " 应为数字: " + v); }
            }
        }
        try { return json.writeValueAsString(data); } catch (Exception e) { return dataJson; }
    }

    /** 编码字段查重：取名为 code 的字段（无则第一个字段）；同一主数据下值必须唯一。excludeId=编辑时排除自身。 */
    private void checkDuplicate(long masterId, List<Map<String, String>> fields, String dataJson, long excludeId) {
        String codeField = codeFieldOf(fields);
        if (codeField.isEmpty()) return;
        String value = jsonValue(dataJson, codeField);
        if (value.isEmpty()) return;
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, data_json FROM meta.gov_master_record WHERE master_id=?", masterId)) {
            if (lng(r.get("id")) == excludeId) continue;
            if (value.equals(jsonValue(str(r.get("data_json")), codeField)))
                throw new IllegalArgumentException("编码重复: " + codeField + "=" + value + "（已存在记录 " + r.get("id") + "）");
        }
    }

    /** 编码字段定位：名为 code 的字段，无则第一个字段。 */
    private static String codeFieldOf(List<Map<String, String>> fields) {
        for (Map<String, String> f : fields) if ("code".equalsIgnoreCase(f.get("name"))) return f.get("name");
        return fields.isEmpty() ? "" : fields.get(0).get("name");
    }

    /** 从记录 data_json 取字段值（解析失败返回空串）。 */
    private String jsonValue(String dataJson, String field) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> d = json.readValue(dataJson == null || dataJson.isBlank() ? "{}" : dataJson, Map.class);
            return str(d.get(field));
        } catch (Exception e) { return ""; }
    }

    private void audit(long masterId, long recordId, String action, String oldJson, String newJson) {
        try {
            jdbc.update("INSERT INTO meta.gov_master_audit(id, master_id, record_id, action, old_json, new_json, operator, create_time) " +
                            "VALUES (?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis() + (long) (Math.random() * 1000), masterId, recordId, action,
                    oldJson, newJson, AuthContext.username(), new Timestamp(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
