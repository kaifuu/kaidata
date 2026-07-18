package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 治理驾驶舱 [SYS_ADMIN]：聚合数据治理各模块指标，一页展示治理水位（对标 DataWorks 治理中心首页）。
 * 纯读现有表聚合，每项 try/catch 防缺表，无副作用。
 */
@RestController
@RequestMapping("/api/data-gov/dashboard")
@CrossOrigin(origins = "*")
public class DataGovDashboardController {

    @Autowired private JdbcTemplate jdbc;

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("standard", standard());
        out.put("quality", quality());
        out.put("asset", asset());
        out.put("meta", meta());
        out.put("tag", tag());
        out.put("model", model());
        out.put("collect", cnt("SELECT COUNT(*) FROM meta.gov_meta_collect_job"));
        return out;
    }

    private Map<String, Object> standard() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("elements", cnt("SELECT COUNT(*) FROM meta.gov_data_element"));
        m.put("codeSets", cnt("SELECT COUNT(*) FROM meta.gov_code_set"));
        return m;
    }

    private Map<String, Object> quality() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT overall_score, grade FROM meta.gov_quality_report ORDER BY id DESC LIMIT 1");
            if (!r.isEmpty()) { m.put("score", r.get(0).get("overall_score")); m.put("grade", r.get(0).get("grade")); }
            else { m.put("score", 0); m.put("grade", "-"); }
        } catch (Exception e) { m.put("score", 0); m.put("grade", "-"); }
        m.put("rules", cnt("SELECT COUNT(*) FROM meta.gov_quality_rule"));
        return m;
    }

    private Map<String, Object> asset() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", cnt("SELECT COUNT(*) FROM meta.asset"));
        m.put("byStatus", safeList("SELECT status, COUNT(*) AS c FROM meta.asset GROUP BY status"));
        return m;
    }

    private Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fillOverall", fillOverall());
        m.put("lineageEdges", cnt("SELECT COUNT(*) FROM meta.gov_meta_lineage_edge"));
        long total = cnt("SELECT COUNT(*) FROM meta.gov_meta_table");
        long withEdge = cnt("SELECT COUNT(*) FROM (SELECT src_table t FROM meta.gov_meta_lineage_edge WHERE src_table<>'' " +
                "UNION SELECT tgt_table FROM meta.gov_meta_lineage_edge WHERE tgt_schema<>'EXTERNAL' AND tgt_table<>'') x");
        m.put("lineageCoverage", total == 0 ? 0 : Math.round(withEdge * 100.0 / total));
        return m;
    }

    private Map<String, Object> tag() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tags", cnt("SELECT COUNT(*) FROM meta.gov_tag"));
        m.put("relations", cnt("SELECT COUNT(*) FROM meta.gov_tag_relation"));
        return m;
    }

    private Map<String, Object> model() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("models", cnt("SELECT COUNT(*) FROM meta.gov_model"));
        m.put("tables", cnt("SELECT COUNT(*) FROM meta.gov_model_table"));
        long fields = cnt("SELECT COUNT(*) FROM meta.gov_model_field");
        long landed = cnt("SELECT COUNT(*) FROM meta.gov_model_field WHERE element_id>0");
        m.put("fields", fields);
        m.put("landed", landed);
        m.put("landingRate", fields == 0 ? 0 : Math.round(landed * 100.0 / fields));
        return m;
    }

    /** 三类资产补录完成率（fill_percent>=100 占比，复用 DataMetaController.fillAgg 思路）。 */
    private long fillOverall() {
        long t = cnt("SELECT COUNT(*) FROM meta.gov_meta_table") + cnt("SELECT COUNT(*) FROM meta.gov_meta_api") + cnt("SELECT COUNT(*) FROM meta.gov_meta_file");
        long f = cnt("SELECT COUNT(*) FROM meta.gov_meta_table WHERE fill_percent>=100")
                + cnt("SELECT COUNT(*) FROM meta.gov_meta_api WHERE fill_percent>=100")
                + cnt("SELECT COUNT(*) FROM meta.gov_meta_file WHERE fill_percent>=100");
        return t == 0 ? 0 : Math.round(f * 100.0 / t);
    }

    private long cnt(String sql, Object... args) {
        try { return args.length == 0 ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, args); }
        catch (Exception e) { return 0; }
    }

    private List<Map<String, Object>> safeList(String sql, Object... args) {
        try { return args.length == 0 ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args); }
        catch (Exception e) { return List.of(); }
    }
}
