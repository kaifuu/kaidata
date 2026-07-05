package com.pharma.service.controller;

import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;

/** 元数据 [SYS_ADMIN]：技术元数据浏览（探查自动同步 + 手动同步），字段存 columns_json。 */
@RestController
@RequestMapping("/api/data-gov/meta")
@CrossOrigin(origins = "*")
public class DataMetaController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long dsId,
                                          @RequestParam(required = false) String kw) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT id, ds_id, schema_name, table_name, comment, row_count, synced_time FROM meta.gov_meta_table WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (dsId != null) { sql.append(" AND ds_id=?"); args.add(dsId); }
        if (kw != null && !kw.isEmpty()) { sql.append(" AND (table_name LIKE ? OR comment LIKE ?)"); args.add("%" + kw + "%"); args.add("%" + kw + "%"); }
        sql.append(" ORDER BY ds_id, schema_name, table_name");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, ds_id, schema_name, table_name, comment, columns_json, row_count, synced_time FROM meta.gov_meta_table WHERE id=?", id);
    }

    /** 手动同步：从指定数据源采集表/字段元数据到 gov_meta_table（upsert）。 */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam long dsId) {
        Authz.require(Authz.SYS_ADMIN);
        DataSourceDescriptor ds = loader.load(dsId);
        DataSourceAdapter a = registry.adapter(ds.type);
        DataSource pool = registry.getPool(ds);
        List<Map<String, Object>> tables = a.listTables(pool, null);
        int count = 0;
        for (Map<String, Object> t : tables) {
            String schema = str(t.get("schema_name"));
            String table = str(t.get("name"));
            if (table.isEmpty()) continue;
            String full = schema.isEmpty() ? table : schema + "." + table;
            try {
                String[] sp = com.pharma.service.access.util.SqlBuilder.splitTable(full);
                List<Map<String, Object>> cols = a.describeTable(pool, sp[0], sp[1]);
                String colsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(cols);
                upsertMeta(dsId, schema, table, str(t.get("comment")), colsJson);
                count++;
            } catch (Exception ignored) {}
        }
        return Map.of("success", true, "synced", count);
    }

    private void upsertMeta(long dsId, String schema, String table, String comment, String colsJson) {
        List<Map<String, Object>> exist = jdbc.queryForList(
                "SELECT id FROM meta.gov_meta_table WHERE ds_id=? AND schema_name=? AND table_name=?", dsId, schema, table);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (exist.isEmpty()) {
            jdbc.update("INSERT INTO meta.gov_meta_table(id, ds_id, schema_name, table_name, comment, columns_json, row_count, synced_time) VALUES (?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis() + (long) (Math.random() * 1000), dsId, schema, table, comment, colsJson, 0L, now);
        } else {
            jdbc.update("UPDATE meta.gov_meta_table SET comment=?, columns_json=?, synced_time=? WHERE id=?",
                    comment, colsJson, now, ((Number) exist.get(0).get("id")).longValue());
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
