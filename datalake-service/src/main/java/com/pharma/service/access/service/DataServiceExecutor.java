package com.pharma.service.access.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.develop.DevScriptExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/** 数据服务执行核心：查服务定义 → {param} 替换（白名单防注入）→ 跑 SQL → 记调用日志 → 返回结果。 */
@Component
public class DataServiceExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevScriptExecutor scriptExecutor;
    private final ObjectMapper json = new ObjectMapper();

    public Map<String, Object> invoke(String code, Map<String, String> params, String caller, String ip) {
        Map<String, Object> svc;
        try {
            svc = jdbc.queryForMap("SELECT id, code, sql_text, datasource_id, status FROM meta.data_service WHERE code=?", code);
        } catch (Exception e) {
            return err("服务不存在: " + code);
        }
        if (!"PUBLISHED".equals(str(svc.get("status")))) return err("服务未发布: " + code);
        String sql = str(svc.get("sql_text"));
        // {param} 替换：参数值白名单校验（防 SQL 注入）
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue();
                if (!v.matches("[a-zA-Z0-9_%.\\- ]*")) return err("非法参数值: " + e.getKey());
                sql = sql.replace("{" + e.getKey() + "}", v);
            }
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> result = scriptExecutor.runAdhoc(lng(svc.get("datasource_id")), sql);
        long cost = System.currentTimeMillis() - t0;
        String status = "SUCCESS".equals(str(result.get("status"))) ? "SUCCESS" : "FAIL";
        String paramsJson;
        try { paramsJson = json.writeValueAsString(params == null ? Map.of() : params); } catch (Exception ex) { paramsJson = "{}"; }
        try {
            jdbc.update("INSERT INTO meta.data_service_log(id, service_id, cost_ms, status, params, ip, caller, call_time) VALUES (?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis(), lng(svc.get("id")), cost, status,
                    paramsJson.length() > 1000 ? paramsJson.substring(0, 1000) : paramsJson, ip, caller, new Timestamp(System.currentTimeMillis()));
        } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("service", code);
        out.put("cost_ms", cost);
        return out;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "FAIL"); m.put("msg", msg); m.put("columns", java.util.List.of()); m.put("rows", java.util.List.of()); m.put("rowsRead", 0);
        return m;
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
