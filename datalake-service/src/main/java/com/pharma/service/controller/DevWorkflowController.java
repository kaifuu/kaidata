package com.pharma.service.controller;

import com.pharma.service.access.develop.DevWorkflowExecutor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 工作流 [SYS_ADMIN]：任务链 CRUD（含节点）+ 执行 + 上下线（周期）+ 历史。 */
@RestController
@RequestMapping("/api/data-dev/workflow")
@CrossOrigin(origins = "*")
public class DevWorkflowController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevWorkflowExecutor executor;

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, cron, status, create_time FROM meta.dev_workflow ORDER BY id");
    }
    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> wf = jdbc.queryForMap("SELECT id, name, cron, status FROM meta.dev_workflow WHERE id=?", id);
        wf.put("nodes", jdbc.queryForList("SELECT id, workflow_id, node_type, node_id, sort FROM meta.dev_workflow_node WHERE workflow_id=? ORDER BY sort, id", id));
        return wf;
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        saveWf(id, b, true);
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        saveWf(id, b, false);
        executor.stop(id);
        if ("ONLINE".equals(str(b.get("status"))) && !str(b.get("cron")).isEmpty()) executor.start(id, str(b.get("cron")));
        return Map.of("success", true);
    }
    private void saveWf(long id, Map<String, Object> b, boolean create) {
        String name = str(b.get("name")), cron = str(b.get("cron")), status = str(b.getOrDefault("status", "OFFLINE"));
        if (create) jdbc.update("INSERT INTO meta.dev_workflow(id, name, cron, status, create_time) VALUES (?,?,?,?,?)", id, name, cron, status, new Timestamp(id));
        else jdbc.update("UPDATE meta.dev_workflow SET name=?, cron=?, status=? WHERE id=?", name, cron, status, id);
        jdbc.update("DELETE FROM meta.dev_workflow_node WHERE workflow_id=?", id);
        int sort = 1;
        for (Object o : (List<?>) b.getOrDefault("nodes", List.of())) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> n = (Map<String, Object>) o;
            jdbc.update("INSERT INTO meta.dev_workflow_node(id, workflow_id, node_type, node_id, sort) VALUES (?,?,?,?,?)",
                    System.currentTimeMillis() + (long) (Math.random() * 1000), id, str(n.get("node_type")), lng(n.get("node_id")), sort++);
        }
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        executor.stop(id);
        jdbc.update("DELETE FROM meta.dev_workflow_node WHERE workflow_id=?", id);
        jdbc.update("DELETE FROM meta.dev_workflow_run WHERE workflow_id=?", id);
        jdbc.update("DELETE FROM meta.dev_workflow WHERE id=?", id);
        return Map.of("success", true);
    }
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return executor.run(id);
    }
    @PostMapping("/online")
    public Map<String, Object> online(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.dev_workflow SET status='ONLINE' WHERE id=?", id);
        String cron = jdbc.queryForObject("SELECT cron FROM meta.dev_workflow WHERE id=?", String.class, id);
        if (cron != null && !cron.isEmpty()) executor.start(id, cron);
        return Map.of("success", true);
    }
    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        executor.stop(id);
        jdbc.update("UPDATE meta.dev_workflow SET status='OFFLINE' WHERE id=?", id);
        return Map.of("success", true);
    }
    @GetMapping("/run-list")
    public List<Map<String, Object>> runList(@RequestParam long workflowId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, workflow_id, status, nodes_total, nodes_pass, run_time FROM meta.dev_workflow_run WHERE workflow_id=? ORDER BY id DESC LIMIT 50", workflowId);
    }
    @GetMapping("/run-detail")
    public Map<String, Object> runDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, workflow_id, status, nodes_total, nodes_pass, log_text, run_time FROM meta.dev_workflow_run WHERE id=?", id);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
