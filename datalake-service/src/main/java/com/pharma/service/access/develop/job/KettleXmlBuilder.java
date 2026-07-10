package com.pharma.service.access.develop.job;

import com.pharma.service.access.adapter.DataSourceDescriptor;

import java.util.*;

/**
 * 把 DAG(nodes/edges) + 数据源 翻译成 Hop .hpl XML（pipeline_configuration 不包装，hop-run.sh 直接吃 .hpl）。
 * <p>Hop .hpl 用 {@code <pipeline>} 根 + {@code <transform>} 标签（非 Kettle 的 step）+ {@code <pipeline_type>}。
 * <p>参考：pentaho-kettle-master/assemblies/samples .ktr 结构 + Hop 容器内 samples/*.hpl。
 * <p>阶段1 实现 core kind：table_input/table(输入)、filter、table_output(输出)。
 * 其余 kind 抛 UnsupportedOperationException（阶段2补全量）。
 */
public final class KettleXmlBuilder {

    /** 生成完整 .hpl XML。nodes/edges 来自 dag_json，ds 任务绑定数据源。 */
    public static String buildPipeline(String name, List<Map<String, Object>> nodes,
                                       List<Map<String, Object>> edges, DataSourceDescriptor ds, String connName) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<pipeline>\n");
        // info
        sb.append("  <info>\n    <name>").append(esc(name)).append("</name>\n");
        sb.append("    <name_sync_with_filename>Y</name_sync_with_filename>\n");
        sb.append("    <description/>\n    <pipeline_type>Normal</pipeline_type>\n");
        sb.append("    <parameters/>\n  </info>\n  <notepads/>\n");
        // 先建 id→stepName 映射，并填充 edges 的 _fromName/_toName（buildOrder 需要）
        Map<String, String> idToName = new HashMap<>();
        for (Map<String, Object> n : nodes) idToName.put(str(n.get("id")), stepName(n));
        for (Map<String, Object> e : edges) {
            e.put("_fromName", idToName.get(str(e.get("source"))));
            e.put("_toName", idToName.get(str(e.get("target"))));
        }
        // 连接定义外置到 metadata/rdbms/{connName}.json（Hop 不读 .hpl 内嵌 connection），由 KettleHopExecutor 单独写文件
        // order（hops）
        sb.append(buildOrder(edges));
        // transforms
        Map<String, List<String>> downstream = downstreamMap(edges);
        int[] loc = {0, 0};
        for (Map<String, Object> n : nodes) {
            String id = str(n.get("id"));
            sb.append(buildTransform(n, idToName, downstream, connName, loc, edges));
            loc[0]++; if (loc[0] >= 5) { loc[0] = 0; loc[1]++; }
        }
        sb.append("  <step_error_handling/>\n");
        sb.append("  <slave-step-copy-partition-distribution/>\n");
        sb.append("  <slave_transformation>N</slave_transformation>\n");
        sb.append("</pipeline>\n");
        return sb.toString();
    }

    /** 数据源 → Hop rdbms 连接 metadata JSON（外置到 metadata/rdbms/{connName}.json，.hpl 引用 connName）。 */
    static String buildConnectionJson(String connName, DataSourceDescriptor ds) {
        String host = ds.host;
        if (host == null || host.isBlank() || "localhost".equals(host) || "127.0.0.1".equals(host)) {
            host = "host.docker.internal";  // Docker Desktop: 容器访问宿主机
        }
        String pluginId = kettleDbPlugin(ds.type);
        return "{\"rdbms\":{\"" + pluginId + "\":{\"databaseName\":\"" + escJson(ds.dbName)
                + "\",\"pluginId\":\"" + pluginId + "\",\"accessType\":0,\"hostname\":\"" + escJson(host)
                + "\",\"password\":\"" + escJson(str(ds.password)) + "\",\"pluginName\":\"" + pluginId
                + "\",\"port\":\"" + ds.port + "\",\"username\":\"" + escJson(str(ds.username))
                + "\"}},\"name\":\"" + escJson(connName) + "\"}";
    }
    static String kettleDbPlugin(String type) {
        if (type == null) return "MySQL";
        switch (type.toLowerCase()) {
            case "starrocks": case "mysql": case "doris": return "MySQL";
            case "postgresql": case "postgres": return "PostgreSQL";
            case "oracle": return "Oracle";
            case "sqlserver": case "mssql": return "MSSQL";
            default: return "MySQL";
        }
    }

    static String buildOrder(List<Map<String, Object>> edges) {
        StringBuilder sb = new StringBuilder("  <order>\n");
        for (Map<String, Object> e : edges) {
            sb.append("    <hop><from>").append(esc(str(e.get("_fromName")))).append("</from>")
              .append("<to>").append(esc(str(e.get("_toName")))).append("</to><enabled>Y</enabled></hop>\n");
        }
        sb.append("  </order>\n");
        return sb.toString();
    }

    /** 单个 <transform>，按 kind 分发。 */
    @SuppressWarnings("unchecked")
    static String buildTransform(Map<String, Object> node, Map<String, String> idToName,
                                 Map<String, List<String>> downstream, String connName, int[] loc,
                                 List<Map<String, Object>> edges) {
        String id = str(node.get("id"));
        String name = idToName.get(id);
        Map<String, Object> data = (Map<String, Object>) node.getOrDefault("data", Map.of());
        String kind = str(data.get("kind"));
        Map<String, Object> cfg = (Map<String, Object>) data.getOrDefault("config", Map.of());
        String type = str(node.get("type"));
        StringBuilder sb = new StringBuilder();
        sb.append("  <transform>\n    <name>").append(esc(name)).append("</name>\n");
        switch (kind) {
            // 输入
            case "table_input": case "table":
                if ("sink".equals(type)) sb.append(tableOutputXml(cfg, connName)); else sb.append(tableInputXml(cfg, connName)); break;
            case "csv_input": sb.append(csvInputXml(cfg)); break;
            case "excel_input": sb.append(fileInputXml(cfg, "ExcelInput")); break;
            case "kafka_input": sb.append(kafkaInputXml(cfg)); break;
            case "json_input": sb.append(fileInputXml(cfg, "JsonInput")); break;
            case "generate_rows": sb.append(generateRowsXml(cfg)); break;
            case "rest_input": case "rest_client": sb.append(restInputXml(cfg)); break;
            case "xml_input": sb.append(fileInputXml(cfg, "getXMLData")); break;
            case "text_input": sb.append(fileInputXml(cfg, "TextFileInput2")); break;
            // 转换
            case "filter": sb.append(filterXml(cfg, downstream.get(id), idToName)); break;
            case "select": sb.append(selectXml(cfg)); break;
            case "sort": sb.append(sortXml(cfg)); break;
            case "aggregate": case "univariate": sb.append(groupByXml(cfg)); break;
            case "dedup": case "dup_check": sb.append(uniqueRowsXml(cfg)); break;
            case "value_map": sb.append(valueMapperXml(cfg)); break;
            case "join": sb.append(mergeJoinXml(cfg, edges, id, idToName)); break;
            case "string_replace": sb.append(replaceStringXml(cfg)); break;
            case "string_ops": case "string_to_date": case "url_check": case "id_check": case "regex_check": case "data_validate": sb.append(formulaXml(cfg, kind)); break;
            case "split_field": sb.append(fieldSplitterXml(cfg)); break;
            case "js_code": case "java_code": case "mask_partial": case "mask_delete": case "mask_random": case "encrypt": sb.append(scriptValueXml(cfg, kind)); break;
            case "exec_sql": sb.append(dbExecuteXml(cfg, connName)); break;
            case "num_range": sb.append(numberRangeXml(cfg)); break;
            case "null_check": sb.append(filterXml(cfg, downstream.get(id), idToName)); break;
            case "stream_lookup": sb.append(streamLookupXml(cfg)); break;
            case "sampling": sb.append(samplingXml(cfg)); break;
            case "switch_case": sb.append(switchCaseXml(cfg, downstream.get(id), idToName)); break;
            // 输出
            case "table_output": sb.append(tableOutputXml(cfg, connName)); break;
            case "insert_update": sb.append(insertUpdateXml(cfg, connName)); break;
            case "kafka_output": sb.append(kafkaOutputXml(cfg)); break;
            case "excel_output": sb.append(fileOutputXml(cfg, "TypeExitExcelWriterTransform")); break;
            case "json_output": sb.append(fileOutputXml(cfg, "JsonOutput")); break;
            default:
                throw new UnsupportedOperationException("算子[" + kind + "]的 .hpl 翻译尚未实现");
        }
        sb.append("    <GUI><xloc>").append(loc[0] * 200).append("</xloc><yloc>").append(loc[1] * 150).append("</yloc></GUI>\n");
        sb.append("  </transform>\n");
        return sb.toString();
    }

    // —— 核心 Step XML ——
    @SuppressWarnings("unchecked")
    private static String tableInputXml(Map<String, Object> cfg, String connName) {
        String table = str(cfg.get("tableName"));
        String schema = str(cfg.get("schemaName"));
        List<String> fields = fieldList(cfg.get("fields"));
        String cols = fields.isEmpty() ? "*" : String.join(", ", fields);
        String tableRef = schema.isEmpty() ? table : schema + "." + table;
        String sql = "SELECT " + cols + " FROM " + tableRef;
        String limit = String.valueOf(intOr(cfg.get("limit"), 0));
        return "    <type>TableInput</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <connection>" + esc(connName) + "</connection>\n" +
                "    <sql>" + esc(sql) + "</sql>\n" +
                "    <limit>" + esc(limit) + "</limit>\n" +
                "    <lazy_conversion_active>N</lazy_conversion_active>\n    <first_step>N</first_step>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }

    private static String filterXml(Map<String, Object> cfg, List<String> downstream, Map<String, String> idToName) {
        String trueTo = (downstream != null && !downstream.isEmpty()) ? idToName.get(downstream.get(0)) : "";
        String expr = str(cfg.get("expression"));
        String[] cond = parseSimpleCondition(expr);  // [left, func, right]
        return "    <type>FilterRows</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <send_true_to>" + esc(trueTo) + "</send_true_to>\n    <send_false_to/>\n" +
                "    <compare><condition><negated>N</negated>" +
                "<leftvalue>" + esc(cond[0]) + "</leftvalue>" +
                "<function>" + esc(cond[1]) + "</function>" +
                "<rightvalue>" + esc(cond[2]) + "</rightvalue>" +
                "</condition></compare>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }

    private static String tableOutputXml(Map<String, Object> cfg, String connName) {
        String table = str(cfg.get("tableName"));
        return "    <type>TableOutput</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <connection>" + esc(connName) + "</connection>\n    <schema/>\n    <table>" + esc(table) + "</table>\n" +
                "    <commit>1000</commit>\n    <truncate>N</truncate>\n    <ignore_errors>N</ignore_errors>\n" +
                "    <use_batch>N</use_batch>\n    <specify_fields>N</specify_fields>\n" +
                "    <partitioning_enabled>N</partitioning_enabled>\n    <partitioning_field/>\n" +
                "    <partitioning_daily>N</partitioning_daily>\n    <partitioning_monthly>Y</partitioning_monthly>\n" +
                "    <tablename_in_field>N</tablename_in_field>\n    <tablename_field/>\n    <tablename_in_table>Y</tablename_in_table>\n" +
                "    <return_keys>N</return_keys>\n    <return_field/>\n    <fields/>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }

    // —— 阶段2 简易实现（其余算子）——
    @SuppressWarnings("unchecked")
    private static String selectXml(Map<String, Object> cfg) {
        List<String> fields = fieldList(cfg.get("fields"));
        StringBuilder sb = new StringBuilder("    <type>SelectValues</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n");
        sb.append("    <partitioning><method>none</method><schema_name/></partitioning>\n    <fields>\n");
        for (String f : fields) sb.append("      <field><name>").append(esc(f)).append("</name><rename/><length>-2</length><precision>-2</precision></field>\n");
        sb.append("    </fields>\n    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n");
        return sb.toString();
    }
    private static String sortXml(Map<String, Object> cfg) {
        String orderBy = str(cfg.get("orderBy"));
        StringBuilder sb = new StringBuilder("    <type>SortRows</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n");
        sb.append("    <partitioning><method>none</method><schema_name/></partitioning>\n    <fields>\n");
        for (String p : orderBy.split(",")) {
            String[] tk = p.trim().split("\\s+");
            if (tk[0].isEmpty()) continue;
            sb.append("      <field><name>").append(esc(tk[0])).append("</name><ascending>").append(tk.length > 1 && tk[1].equalsIgnoreCase("DESC") ? "N" : "Y").append("</ascending><case_sensitive>Y</case_sensitive></field>\n");
        }
        sb.append("    </fields>\n    <directory>${java.io.tmpdir}</directory>\n    <prefix>sort</prefix>\n    <sort_size>1000</sort_size>\n    <free_memory></free_memory>\n    <compress>N</compress>\n    <compress_variable></compress_variable>\n    <unique_rows>N</unique_rows>\n    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n");
        return sb.toString();
    }
    @SuppressWarnings("unchecked")
    private static String groupByXml(Map<String, Object> cfg) {
        List<String> groupKeys = fieldList(cfg.get("groupKeys"));
        StringBuilder sb = new StringBuilder("    <type>MemoryGroupBy</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n");
        sb.append("    <partitioning><method>none</method><schema_name/></partitioning>\n    <group>\n");
        for (String g : groupKeys) sb.append("      <field><name>").append(esc(g)).append("</name></field>\n");
        sb.append("    </group>\n    <fields>\n");
        String aggExpr = str(cfg.get("aggExpr"));
        for (String a : aggExpr.split(",")) {
            a = a.trim(); if (a.isEmpty()) continue;
            String[] parts = a.split("(?i)\\s+AS\\s+", 2);
            String aggPart = parts[0], alias = parts.length > 1 ? parts[1].trim() : "agg";
            String type = "SUM", subject = "";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(SUM|COUNT|AVG|MIN|MAX)\\s*\\(\\s*(\\*|\\w+)\\s*\\)").matcher(aggPart);
            if (m.find()) { type = m.group(1).toUpperCase(); subject = m.group(2); }
            sb.append("      <field><subject><name>").append(esc(subject)).append("</name></subject><aggregate><name>").append(esc(alias)).append("</name><type>").append(type).append("</type></field>\n");
        }
        sb.append("    </fields>\n    <give_back_row>N</give_back_row>\n    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n");
        return sb.toString();
    }
    @SuppressWarnings("unchecked")
    private static String uniqueRowsXml(Map<String, Object> cfg) {
        List<String> fields = fieldList(cfg.get("fields"));
        StringBuilder sb = new StringBuilder("    <type>UniqueRows</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n");
        sb.append("    <partitioning><method>none</method><schema_name/></partitioning>\n    <fields>\n");
        for (String f : fields) sb.append("      <field><name>").append(esc(f)).append("</name></field>\n");
        sb.append("    </fields>\n    <count_rows>N</count_rows>\n    <count_field/>\n    <reject_duplicate_row>N</reject_duplicate_row>\n    <error_description/>\n    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n");
        return sb.toString();
    }
    private static String valueMapperXml(Map<String, Object> cfg) {
        String col = str(cfg.get("col"));
        String mappingJson = str(cfg.get("mapping"));
        StringBuilder sb = new StringBuilder("    <type>ValueMapper</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n");
        sb.append("    <partitioning><method>none</method><schema_name/></partitioning>\n");
        sb.append("    <field_to_use>").append(esc(col)).append("</field_to_use>\n    <target_field/>\n    <default_value/>\n    <values>\n");
        try {
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(mappingJson, Map.class);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append("      <value><source>").append(esc(e.getKey())).append("</source><target>").append(esc(String.valueOf(e.getValue()))).append("</target></value>\n");
            }
        } catch (Exception ignored) {}
        sb.append("    </values>\n    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n");
        return sb.toString();
    }

    // —— 阶段2：其余 kind 的 XML ——
    private static String csvInputXml(Map<String, Object> cfg) {
        return "    <type>CSVInput</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <filename>" + esc(str(cfg.get("path"))) + "</filename>\n    <filename_field/>\n    <include_filename>N</include_filename>\n" +
                "    <rownum>N</rownum>\n    <rownum_field/>\n    <delimiter>" + esc(str(cfg.getOrDefault("delimiter", ","))) + "</delimiter>\n" +
                "    <enclosure>\"</enclosure>\n    <header_present>Y</header_present>\n    <buffer_size>50000</buffer_size>\n" +
                "    <lazy_conversion_active>N</lazy_conversion_active>\n    <noempty_lines>N</noempty_lines>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String fileInputXml(Map<String, Object> cfg, String type) {
        return "    <type>" + type + "</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <filename>" + esc(str(cfg.get("path"))) + "</filename>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String kafkaInputXml(Map<String, Object> cfg) {
        return "    <type>KafkaConsumer</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <bootstrap_servers>localhost:9092</bootstrap_servers>\n    <topic>" + esc(str(cfg.get("topic"))) + "</topic>\n" +
                "    <key_field/>\n    <message_field>message</message_field>\n    <batch_size>500</batch_size>\n    <batch_duration>1000</batch_duration>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String generateRowsXml(Map<String, Object> cfg) {
        return "    <type>GenerateRows</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n    <never_exceed>10</never_exceed>\n    <fields>\n" +
                "      <field><name>value</name><type>Integer</type><value>1</value></field>\n    </fields>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String restInputXml(Map<String, Object> cfg) {
        return "    <type>Rest</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <applicationType>JSON</applicationType>\n    <method>GET</method>\n    <url>" + esc(str(cfg.get("url"))) + "</url>\n" +
                "    <body/>\n    <httpStatus>200</httpStatus>\n    <resultFieldName>response</resultFieldName>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String mergeJoinXml(Map<String, Object> cfg, List<Map<String, Object>> edges, String id, Map<String, String> idToName) {
        List<String> up = new ArrayList<>();
        for (Map<String, Object> e : edges) if (id.equals(str(e.get("target")))) up.add(str(e.get("source")));
        if (up.size() < 2) throw new RuntimeException("Join 节点[" + id + "]需两个上游输入");
        String jt = str(cfg.get("joinType")).toUpperCase();
        String kettleJt = jt.equals("LEFT") ? "LEFT OUTER" : jt.equals("RIGHT") ? "RIGHT OUTER" : jt.equals("FULL") ? "FULL OUTER" : "INNER";
        String onExpr = str(cfg.get("onExpr"));
        List<String> k1 = new ArrayList<>(), k2 = new ArrayList<>();
        for (String c : onExpr.split("(?i)\\s+AND\\s+")) {
            String[] s = c.trim().split("=");
            if (s.length == 2) { k1.add(stripAlias(s[0].trim())); k2.add(stripAlias(s[1].trim())); }
        }
        StringBuilder sb = new StringBuilder("    <type>MergeJoin</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n");
        sb.append("    <partitioning><method>none</method><schema_name/></partitioning>\n");
        sb.append("    <join_type>").append(kettleJt).append("</join_type>\n");
        sb.append("    <keys_1>"); for (String k : k1) sb.append("<key>").append(esc(k)).append("</key>"); sb.append("</keys_1>\n");
        sb.append("    <keys_2>"); for (String k : k2) sb.append("<key>").append(esc(k)).append("</key>"); sb.append("</keys_2>\n");
        sb.append("    <transform1>").append(esc(idToName.get(up.get(0)))).append("</transform1>\n");
        sb.append("    <transform2>").append(esc(idToName.get(up.get(1)))).append("</transform2>\n");
        sb.append("    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n");
        return sb.toString();
    }
    private static String replaceStringXml(Map<String, Object> cfg) {
        return "    <type>ReplaceString</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n    <fields>\n" +
                "      <field><in_stream_name>" + esc(str(cfg.get("col"))) + "</in_stream_name><out_stream_name>" + esc(str(cfg.get("col"))) + "</out_stream_name><use_regex>N</use_regex><replace_string>" + esc(str(cfg.get("from"))) + "</replace_string><replace_by_string>" + esc(str(cfg.get("to"))) + "</replace_by_string></field>\n" +
                "    </fields>\n    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String formulaXml(Map<String, Object> cfg, String kind) {
        String col = str(cfg.get("col")), expr = str(cfg.getOrDefault("expression", cfg.get("pattern")));
        String formula = kind.equals("string_to_date") ? "DATEPARSE(" + col + ")" : (kind.equals("regex_check") || kind.equals("url_check") || kind.equals("id_check")) ? "REGEXP(" + col + ",\"" + esc(expr) + "\")" : expr;
        return "    <type>Formula</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n    <formulas>\n" +
                "      <formula><field_name>result</field_name><formula>" + esc(formula) + "</formula><value_type>0</value_type><value_length>-1</value_length><value_precision>-1</value_precision><replace_field/></formula>\n" +
                "    </formulas>\n    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String fieldSplitterXml(Map<String, Object> cfg) {
        return "    <type>FieldSplitter</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <splitfield>" + esc(str(cfg.get("col"))) + "</splitfield>\n    <delimiter>" + esc(str(cfg.getOrDefault("delimiter", ","))) + "</delimiter>\n    <enclosure/>\n    <escape_string/>\n    <fields>\n    </fields>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String scriptValueXml(Map<String, Object> cfg, String kind) {
        String col = str(cfg.get("col")), expr = str(cfg.get("expression"));
        String script;
        switch (kind) {
            case "mask_partial": script = "var v=" + col + ";" + col + "=v.substring(0," + intOr(cfg.get("keepHead"),0) + ")+repeat('*'," + intOr(cfg.get("keepTail"),0) + ")+v.substring(v.length()-" + intOr(cfg.get("keepTail"),0) + ");"; break;
            case "mask_delete": script = col + "=null;"; break;
            case "mask_random": script = col + "='****';"; break;
            case "encrypt": script = col + "=encrypt(" + col + ");"; break;
            default: script = expr;
        }
        return "    <type>ScriptValueMod</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n    <optimizationLevel>9</optimizationLevel>\n" +
                "    <jsScripts><jsScript><jsScript_type>0</jsScript_type><jsScript_name>Script 1</jsScript_name><jsScript_script>" + esc(script) + "</jsScript_script></jsScript></jsScripts>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String dbExecuteXml(Map<String, Object> cfg, String connName) {
        return "    <type>DBExecute</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <connection>" + esc(connName) + "</connection>\n    <execute_each_row>N</execute_each_row>\n    <params/>\n    <sql>" + esc(str(cfg.get("expression"))) + "</sql>\n    <set_params>N</set_params>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String numberRangeXml(Map<String, Object> cfg) {
        return "    <type>NumberRange</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <inputField>" + esc(str(cfg.get("col"))) + "</inputField>\n    <outputField>range</outputField>\n    <fallBackValue>unknown</fallBackValue>\n    <rules/>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String streamLookupXml(Map<String, Object> cfg) {
        return "    <type>StreamLookup</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <from_transform/>\n    <input_sorted>N</input_sorted>\n    <preserve_memory>Y</preserve_memory>\n    <sorted_list>N</sorted_list>\n    <key><name>" + esc(str(cfg.get("col"))) + "</name><field>" + esc(str(cfg.get("col"))) + "</field></key>\n    <lookup/>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String samplingXml(Map<String, Object> cfg) {
        return "    <type>ReservoirSampling</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <sample_size>" + intOr(cfg.get("size"), 100) + "</sample_size>\n    <seed>-1</seed>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String switchCaseXml(Map<String, Object> cfg, List<String> downstream, Map<String, String> idToName) {
        String trueTo = (downstream != null && !downstream.isEmpty()) ? idToName.get(downstream.get(0)) : "";
        return "    <type>SwitchCase</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <fieldname>" + esc(str(cfg.get("col"))) + "</fieldname>\n    <case_value_type>String</case_value_type>\n    <cases/>\n" +
                "    <default_target_transform>" + esc(trueTo) + "</default_target_transform>\n    <use_contains>N</use_contains>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String insertUpdateXml(Map<String, Object> cfg, String connName) {
        String col = str(cfg.get("col")), table = str(cfg.get("tableName"));
        return "    <type>InsertUpdate</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <connection>" + esc(connName) + "</connection>\n    <commit>1000</commit>\n    <update_bypassed>N</update_bypassed>\n" +
                "    <lookup><schema/><table>" + esc(table) + "</table>\n      <key><name>" + esc(col) + "</name><field>" + esc(col) + "</field><condition>=</condition><name2/></key>\n    </lookup>\n" +
                "    <value><name>" + esc(col) + "</name><rename>" + esc(col) + "</rename><update>Y</update></value>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String kafkaOutputXml(Map<String, Object> cfg) {
        return "    <type>KafkaProducerOutput</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <bootstrap_servers>localhost:9092</bootstrap_servers>\n    <topic>" + esc(str(cfg.get("topic"))) + "</topic>\n    <key_field/>\n    <message_field>message</message_field>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }
    private static String fileOutputXml(Map<String, Object> cfg, String type) {
        String path = str(cfg.get("path"));
        // 相对路径转容器挂载目录 /opt/hop/output/（宿主机 docker/hop-output 可见）
        if (!path.startsWith("/")) path = "/opt/hop/output/" + path;
        // Kettle 文件输出用 <file><name> + <extention> 嵌套结构（非 <filename>）
        return "    <type>" + type + "</type>\n    <description/>\n    <distribute>Y</distribute>\n    <copies>1</copies>\n" +
                "    <partitioning><method>none</method><schema_name/></partitioning>\n" +
                "    <file><name>" + esc(path) + "</name><extention>xlsx</extention><do_not_open_newfile_init>N</do_not_open_newfile_init><create_parent_folder>Y</create_parent_folder><split>N</split><add_date>N</add_date><add_time>N</add_time></file>\n" +
                "    <append>N</append>\n    <header>Y</header>\n    <footer>N</footer>\n    <encoding/>\n    <add_to_result_filenames>Y</add_to_result_filenames>\n" +
                "    <attributes/>\n    <cluster_schema/>\n    <remotesteps><input/><output/></remotesteps>\n";
    }

    // —— 辅助 ——
    static String kettleDbType(String type) {
        if (type == null) return "MYSQL";
        switch (type.toLowerCase()) {
            case "starrocks": case "mysql": case "doris": return "MYSQL";
            case "postgresql": case "postgres": return "POSTGRESQL";
            case "oracle": return "ORACLE";
            case "sqlserver": case "mssql": return "MSSQL";
            default: return "MYSQL";
        }
    }
    /** 简单条件 "field OP value" → [left, func, right]。OP: = != > < >= <= IS NULL IS NOT NULL LIKE。 */
    static String[] parseSimpleCondition(String expr) {
        if (expr == null || expr.isBlank()) throw new RuntimeException("过滤条件为空");
        expr = expr.trim();
        String[] ops = {"IS NOT NULL", "IS NULL", ">=", "<=", "!=", "<>", ">", "<", "=", "LIKE"};
        for (String op : ops) {
            int idx = expr.toUpperCase().indexOf(op);
            if (idx > 0) {
                String left = expr.substring(0, idx).trim();
                String right = idx + op.length() < expr.length() ? expr.substring(idx + op.length()).trim() : "";
                String func = op.equals("=") ? "=" : op.equals("!=") || op.equals("<>") ? "<>" :
                        op.equals(">=") ? ">=" : op.equals("<=") ? "<=" : op.equals(">") ? ">" :
                        op.equals("<") ? "<" : op;
                return new String[]{left, func, right};
            }
        }
        throw new RuntimeException("无法解析过滤条件: " + expr);
    }
    @SuppressWarnings("unchecked")
    static List<String> fieldList(Object o) {
        if (o instanceof List) return (List<String>) o;
        if (o instanceof String s && !s.isBlank()) return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
        return Collections.emptyList();
    }
    static Map<String, List<String>> downstreamMap(List<Map<String, Object>> edges) {
        Map<String, List<String>> m = new HashMap<>();
        for (Map<String, Object> e : edges) {
            String s = str(e.get("source")), t = str(e.get("target"));
            m.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
        }
        return m;
    }
    static String stepName(Map<String, Object> node) {
        Map<String, Object> data = (Map<String, Object>) node.get("data");
        String label = data == null ? "" : str(data.get("label"));
        return (label == null || label.isBlank() ? "step_" : label + "_") + sanitize(str(node.get("id")));
    }
    static String stepNameById(List<Map<String, Object>> edges, String id) { return id; }
    static String sanitize(String s) { return s == null ? "x" : s.replaceAll("[^a-zA-Z0-9]", "_"); }
    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
    static String stripAlias(String field) {
        int dot = field.indexOf('.');
        return dot >= 0 ? field.substring(dot + 1) : field;
    }
    static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    static int intOr(Object o, int def) { if (o == null) return def; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return def; } }

    private KettleXmlBuilder() {}
}
