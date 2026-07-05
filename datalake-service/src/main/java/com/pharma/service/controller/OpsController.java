package com.pharma.service.controller;

import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.develop.DevScriptExecutor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

/** 运维中心 [SYS_ADMIN]：交互式分析 + 数据/任务概览 + 资源/集群/执行器/连接器管理。多为只读聚合。 */
@RestController
@RequestMapping("/api/ops")
@CrossOrigin(origins = "*")
public class OpsController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevScriptExecutor scriptExecutor;
    @Autowired private DataSourceAdapterRegistry registry;

    /** ①交互式分析：复用数据开发 SQL 执行器（运维查询工作台）。 */
    @PostMapping("/query/run")
    public Map<String, Object> query(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long dsId = lng(b.get("datasource_id"));
        String content = str(b.get("content"));
        return scriptExecutor.runAdhoc(dsId, content);
    }

    /** ②数据概览：平台 KPI。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasources", cnt("SELECT COUNT(*) FROM meta.ing_datasource"));
        out.put("filestores", cnt("SELECT COUNT(*) FROM meta.ing_filestore"));
        out.put("metaTables", cnt("SELECT COUNT(*) FROM meta.gov_meta_table"));
        out.put("assets", cnt("SELECT COUNT(*) FROM meta.asset"));
        out.put("assetsApproved", cnt("SELECT COUNT(*) FROM meta.asset WHERE status='通过'"));
        out.put("profileJobs", cnt("SELECT COUNT(*) FROM meta.ing_profile_job"));
        out.put("qualityRules", cnt("SELECT COUNT(*) FROM meta.gov_quality_rule"));
        out.put("workflows", cnt("SELECT COUNT(*) FROM meta.dev_workflow"));
        out.put("scripts", cnt("SELECT COUNT(*) FROM meta.dev_script"));
        out.put("exports", cnt("SELECT COUNT(*) FROM meta.dev_export"));
        out.put("datasourceByType", safeList(() -> jdbc.queryForList("SELECT type, COUNT(*) c FROM meta.ing_datasource GROUP BY type")));
        out.put("assetByStatus", safeList(() -> jdbc.queryForList("SELECT status, COUNT(*) c FROM meta.asset GROUP BY status")));
        return out;
    }

    /** ③任务中心：各域任务统一视图。 */
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        addTasks(out, "探查", "SELECT id, name, status FROM meta.ing_profile_job");
        addTasks(out, "质量任务", "SELECT id, name, status FROM meta.gov_quality_task");
        addTasks(out, "工作流", "SELECT id, name, status FROM meta.dev_workflow");
        addTasks(out, "数据接出", "SELECT id, name, 'ENABLED' AS status FROM meta.dev_export");
        addTasks(out, "离线接入", "SELECT id, name, status FROM meta.ing_offline_job");
        return out;
    }

    /** ④任务概览：各执行历史统计。 */
    @GetMapping("/task-stats")
    public Map<String, Object> taskStats() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profile", runStats("ing_profile_run"));
        out.put("quality", runStats("gov_quality_result"));
        out.put("workflow", runStats("dev_workflow_run"));
        out.put("export", runStats("dev_export_run"));
        out.put("script", runStats("dev_script_run"));
        out.put("offline", runStats("ing_offline_run"));
        return out;
    }

    /** ⑤资源监控：StarRocks 磁盘 + JVM 内存。 */
    @GetMapping("/resource")
    public Map<String, Object> resource() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        out.put("jvmMaxMB", rt.maxMemory() / 1024 / 1024);
        out.put("jvmUsedMB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        out.put("processors", rt.availableProcessors());
        out.put("starrocksBackends", safeList(() -> jdbc.queryForList("SHOW BACKENDS")));
        return out;
    }

    /** ⑥集群管理：StarRocks FE/BE + 组件探活。 */
    @GetMapping("/cluster")
    public Map<String, Object> cluster() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("starrocksFe", safeList(() -> jdbc.queryForList("SHOW FRONTENDS")));
        out.put("starrocksBe", safeList(() -> jdbc.queryForList("SHOW BACKENDS")));
        out.put("components", probeComponents());
        return out;
    }

    /** ⑦执行器管理：在线调度任务（探查/工作流）。 */
    @GetMapping("/executors")
    public Map<String, Object> executors() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileOnline", safeList(() -> jdbc.queryForList("SELECT id, name, cron, status FROM meta.ing_profile_job WHERE status='ONLINE'")));
        out.put("workflowOnline", safeList(() -> jdbc.queryForList("SELECT id, name, cron, status FROM meta.dev_workflow WHERE status='ONLINE'")));
        out.put("registeredAdapters", registry.all().size());
        return out;
    }

    /** ⑧连接器管理：适配器类型 + 可用性 + 已登记数。 */
    @GetMapping("/connectors")
    public List<Map<String, Object>> connectors() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Long> registered = new HashMap<>();
        for (Map<String, Object> r : safeList(() -> jdbc.queryForList("SELECT type, COUNT(*) c FROM meta.ing_datasource GROUP BY type"))) {
            registered.put(str(r.get("type")), ((Number) r.get("c")).longValue());
        }
        for (DataSourceAdapter a : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", a.type());
            m.put("driverAvailable", a.driverAvailable());
            if (a.jarHint() != null) m.put("jarHint", a.jarHint());
            m.put("registered", registered.getOrDefault(a.type(), 0L));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("type"))));
        return out;
    }

    // -------- 助手 --------
    private void addTasks(List<Map<String, Object>> out, String domain, String sql) {
        for (Map<String, Object> r : safeList(() -> jdbc.queryForList(sql))) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("domain", domain);
            t.put("id", r.get("id"));
            t.put("name", r.get("name"));
            t.put("status", r.get("status"));
            out.add(t);
        }
    }
    private Map<String, Object> runStats(String table) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", cnt("SELECT COUNT(*) FROM meta." + table));
        m.put("success", cnt("SELECT COUNT(*) FROM meta." + table + " WHERE status IN ('SUCCESS','PASS','通过')"));
        m.put("fail", cnt("SELECT COUNT(*) FROM meta." + table + " WHERE status IN ('FAIL','ERROR','驳回')"));
        return m;
    }
    private List<Map<String, Object>> probeComponents() {
        List<Map<String, Object>> out = new ArrayList<>();
        String[][] comps = {{"StarRocks", "127.0.0.1", "9030"}, {"Kafka", "127.0.0.1", "9094"}, {"Flink", "127.0.0.1", "8081"}, {"MinIO", "127.0.0.1", "9000"}};
        for (String[] c : comps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c[0]); m.put("host", c[1]); m.put("port", Integer.parseInt(c[2]));
            m.put("alive", alive(c[1], Integer.parseInt(c[2])));
            out.add(m);
        }
        return out;
    }
    private boolean alive(String host, int port) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), 1200); return true; }
        catch (Exception e) { return false; }
    }
    private long cnt(String sql) { try { Long v = jdbc.queryForObject(sql, Long.class); return v == null ? 0 : v; } catch (Exception e) { return 0; } }
    interface Q { List<Map<String, Object>> run(); }
    private List<Map<String, Object>> safeList(Q q) { try { return q.run(); } catch (Exception e) { return List.of(); } }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
