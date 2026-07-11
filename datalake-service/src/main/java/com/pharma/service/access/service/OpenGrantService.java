package com.pharma.service.access.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.util.SqlBuilder;
import com.pharma.service.security.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据开放授权服务：基于已审核通过的表资产，派生查询 SQL + 建 data_service + 生成 appkey。
 * <p>登记式平台代查：调用方凭 appkey 走 /openapi/{appKey}，平台用自身 JDBC 代查，不暴露 DB 账号。
 */
@Component
public class OpenGrantService {

    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public static class GrantInput {
        public String name;
        public long assetId;
        public String openType;       // API / TABLE
        public List<String> fields;   // 开放字段（空=全表）
        public String paramField;     // API 方式的等值参数字段（可空）
        public String grantee;        // 被授权方
        public long limitCount;       // 限次（0=不限）
        public int limitQps;          // 限流 QPS（0=不限）
        public Timestamp expireTime;  // 有效期（null=不限时长）
        public String createBy;
    }

    public static class GrantResult {
        public long id;
        public String appKey;
        public String appSecret;
        public String serviceCode;
    }

    /** 创建授权：资产校验 → 派生 SQL → 建 data_service → 生成 appkey → 建 grant */
    public GrantResult create(GrantInput in) {
        // 1. 资产校验
        Map<String, Object> asset;
        try {
            asset = jdbc.queryForMap("SELECT id, name, source_type, source_id, status FROM meta.asset WHERE id=?", in.assetId);
        } catch (Exception e) {
            throw new AccessDeniedException("资产不存在");
        }
        if (!"通过".equals(str(asset.get("status")))) throw new AccessDeniedException("资产未审核通过，禁止开放");
        if (!"meta_table".equals(str(asset.get("source_type")))) throw new AccessDeniedException("仅支持表类型资产开放");

        // 2. 库表元数据
        Map<String, Object> meta;
        try {
            meta = jdbc.queryForMap("SELECT ds_id, schema_name, table_name, columns_json FROM meta.gov_meta_table WHERE id=?", lng(asset.get("source_id")));
        } catch (Exception e) {
            throw new AccessDeniedException("资产的库表元数据缺失");
        }
        long dsId = lng(meta.get("ds_id"));
        String schema = str(meta.get("schema_name"));
        String table = str(meta.get("table_name"));
        SqlBuilder.ident(schema);
        SqlBuilder.ident(table);

        // 3. 字段白名单（授权 fields / paramField 必须 ⊆ 表实际字段）
        Set<String> tableCols = parseColumns(str(meta.get("columns_json")));
        List<String> fields = (in.fields == null || in.fields.isEmpty()) ? List.of() : in.fields;
        for (String f : fields) {
            SqlBuilder.ident(f);
            if (!tableCols.isEmpty() && !tableCols.contains(f)) throw new AccessDeniedException("字段不在表中: " + f);
        }
        if (in.paramField != null && !in.paramField.isEmpty()) {
            SqlBuilder.ident(in.paramField);
            if (!tableCols.isEmpty() && !tableCols.contains(in.paramField)) throw new AccessDeniedException("参数字段不在表中: " + in.paramField);
        }

        // 4. 派生查询 SQL
        String colList = fields.isEmpty() ? "*" : joinQuoted(fields);
        String tableRef = (schema.isEmpty() ? "" : "`" + schema + "`.") + "`" + table + "`";
        StringBuilder sql = new StringBuilder("SELECT ").append(colList).append(" FROM ").append(tableRef);
        if ("API".equals(in.openType) && in.paramField != null && !in.paramField.isEmpty()) {
            sql.append(" WHERE `").append(in.paramField).append("`='{").append(in.paramField).append("}'");
        }
        sql.append(" LIMIT 1000");
        String sqlText = sql.toString();

        // 5. 派生 data_service（PUBLISHED → 集市可见；asset_id → executor 自动校验资产仍“通过”）
        long svcId = System.currentTimeMillis();
        String code = "open_" + in.assetId + "_" + Long.toHexString(svcId);
        String params = ("API".equals(in.openType) && in.paramField != null && !in.paramField.isEmpty())
                ? jsonArr(in.paramField) : "";
        jdbc.update("INSERT INTO meta.data_service(id, code, name, sql_text, datasource_id, method, params, path, auth, status, asset_id, description, owner, verified, create_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                svcId, code, in.name, sqlText, dsId, "GET", params, "", false, "PUBLISHED", in.assetId,
                "资产开放(" + in.openType + ")", in.grantee, true, new Timestamp(svcId));

        // 6. 生成 appkey + 建 grant
        long grantId = svcId + 1;
        String appKey = uuid();
        String appSecret = uuid();
        String fieldsJson;
        try { fieldsJson = json.writeValueAsString(fields); } catch (Exception e) { fieldsJson = "[]"; }
        jdbc.update("INSERT INTO meta.data_open_grant(id, name, asset_id, open_type, app_key, app_secret, grantee, fields_json, service_code, limit_count, limit_qps, expire_time, status, create_by, create_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                grantId, in.name, in.assetId, in.openType, appKey, appSecret, in.grantee, fieldsJson, code,
                in.limitCount, in.limitQps, in.expireTime, "ACTIVE", in.createBy, new Timestamp(grantId));

        GrantResult r = new GrantResult();
        r.id = grantId;
        r.appKey = appKey;
        r.appSecret = appSecret;
        r.serviceCode = code;
        return r;
    }

    /** 删除授权 + 关联派生的 data_service */
    public void delete(long grantId) {
        String code;
        try {
            code = jdbc.queryForObject("SELECT service_code FROM meta.data_open_grant WHERE id=?", String.class, grantId);
        } catch (Exception e) {
            code = null;
        }
        jdbc.update("DELETE FROM meta.data_open_grant WHERE id=?", grantId);
        if (code != null) {
            Long svcId = null;
            try { svcId = jdbc.queryForObject("SELECT id FROM meta.data_service WHERE code=?", Long.class, code); } catch (Exception ignored) {}
            if (svcId != null) jdbc.update("DELETE FROM meta.data_service_log WHERE service_id=?", svcId);
            jdbc.update("DELETE FROM meta.data_service WHERE code=?", code);
        }
    }

    // ---- 工具 ----
    private Set<String> parseColumns(String columnsJson) {
        Set<String> set = new HashSet<>();
        if (columnsJson == null || columnsJson.isEmpty()) return set;
        try {
            Object obj = json.readValue(columnsJson, Object.class);
            if (obj instanceof List) {
                for (Object o : (List<?>) obj) {
                    if (o instanceof String) set.add((String) o);
                    else if (o instanceof Map) {
                        Object n = ((Map<?, ?>) o).get("name");
                        if (n != null) set.add(String.valueOf(n));
                    }
                }
            }
        } catch (Exception ignored) {}
        return set;
    }

    private static String joinQuoted(List<String> fs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("`").append(fs.get(i)).append("`");
        }
        return sb.toString();
    }

    private String jsonArr(String field) {
        try { return json.writeValueAsString(List.of(field)); } catch (Exception e) { return ""; }
    }

    private static String uuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
}
