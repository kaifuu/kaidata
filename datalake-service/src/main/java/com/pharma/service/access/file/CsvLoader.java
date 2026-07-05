package com.pharma.service.access.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件接入：CSV / JSONL 解析 → 列类型推断 → 自动建 StarRocks 表 → 批量写入。
 * <p>类型推断：全整数→BIGINT，全数值→DOUBLE，否则 VARCHAR(255)。目标表 DUPLICATE KEY(首列)。
 */
@Component
public class CsvLoader {

    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public static class Result {
        public final String targetTable;
        public final long rowsWritten;
        public final List<String> columns;
        public final List<Map<String, Object>> preview;
        public Result(String t, long w, List<String> c, List<Map<String, Object>> p) {
            targetTable = t; rowsWritten = w; columns = c; preview = p;
        }
    }

    public Result ingest(byte[] data, String fileType, String targetDb, String targetTable) {
        StarRocksDdlBuilder.ident(targetDb);
        StarRocksDdlBuilder.ident(targetTable);
        List<String> headers = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        String type = fileType == null ? "csv" : fileType.toLowerCase();
        try {
            if ("json".equals(type)) {
                parseJson(data, headers, rows);
            } else if ("xlsx".equals(type) || "xls".equals(type)) {
                throw new IllegalArgumentException("Excel 接入需引入 POI 依赖（当前支持 csv/json）");
            } else {
                parseCsv(data, headers, rows);
            }
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("文件解析失败：" + e.getMessage(), e); }

        if (headers.isEmpty()) throw new IllegalArgumentException("文件无有效列");

        // 类型推断 + 列名清洗
        List<StarRocksDdlBuilder.ColumnDef> colDefs = new ArrayList<>();
        List<String> cleanCols = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String raw = clean(headers.get(i));
            String name = unique(raw, used);
            cleanCols.add(name);
            colDefs.add(new StarRocksDdlBuilder.ColumnDef(name, inferType(rows, i)));
        }

        // 建表 + truncate
        jdbc.execute(StarRocksDdlBuilder.build(targetDb, targetTable, colDefs, null, false));
        jdbc.execute("TRUNCATE TABLE `" + targetDb + "`.`" + targetTable + "`");

        // 批量写入
        String colList = colDefs.stream().map(c -> "`" + c.name + "`").collect(Collectors.joining(","));
        String ph = colDefs.stream().map(x -> "?").collect(Collectors.joining(","));
        String insert = "INSERT INTO `" + targetDb + "`.`" + targetTable + "` (" + colList + ") VALUES (" + ph + ")";
        List<Object[]> batch = new ArrayList<>();
        long written = 0;
        for (String[] r : rows) {
            Object[] row = new Object[colDefs.size()];
            for (int i = 0; i < colDefs.size(); i++) row[i] = i < r.length ? (r[i] == null || r[i].isEmpty() ? null : r[i]) : null;
            batch.add(row);
            if (batch.size() >= 1000) { written += doBatch(insert, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) written += doBatch(insert, batch);

        // 预览前 50 行
        List<Map<String, Object>> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), 50); i++) {
            String[] r = rows.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            for (int j = 0; j < cleanCols.size(); j++) m.put(cleanCols.get(j), j < r.length ? r[j] : null);
            preview.add(m);
        }
        return new Result(targetTable, written, cleanCols, preview);
    }

    private void parseCsv(byte[] data, List<String> headers, List<String[]> rows) throws Exception {
        try (CSVReader reader = new CSVReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            List<String[]> all = reader.readAll();
            if (all.isEmpty()) return;
            String[] h = all.get(0);
            for (String x : h) headers.add(x == null ? "" : x);
            for (int i = 1; i < all.size(); i++) {
                String[] r = all.get(i);
                if (r != null && !(r.length == 1 && r[0].isEmpty())) rows.add(r);
            }
        }
    }

    private void parseJson(byte[] data, List<String> headers, List<String[]> rows) throws Exception {
        String text = new String(data, StandardCharsets.UTF_8).trim();
        List<Map<String, Object>> objs = new ArrayList<>();
        if (text.startsWith("[")) {
            for (Object o : json.readValue(text, List.class)) objs.add((Map<String, Object>) o);
        } else {
            for (String line : text.split("\n")) {
                if (!line.trim().isEmpty()) objs.add(json.readValue(line, Map.class));
            }
        }
        if (objs.isEmpty()) return;
        headers.addAll(objs.get(0).keySet());
        for (Map<String, Object> o : objs) {
            String[] r = new String[headers.size()];
            for (int i = 0; i < headers.size(); i++) {
                Object v = o.get(headers.get(i));
                r[i] = v == null ? "" : String.valueOf(v);
            }
            rows.add(r);
        }
    }

    private String inferType(List<String[]> rows, int col) {
        boolean allInt = true, allNum = true;
        int sampled = 0;
        for (String[] r : rows) {
            if (col >= r.length) continue;
            String v = r[col];
            if (v == null || v.isEmpty()) continue;
            sampled++;
            if (allInt) { try { Long.parseLong(v); } catch (Exception e) { allInt = false; } }
            if (allNum) { try { Double.parseDouble(v); } catch (Exception e) { allNum = false; } }
            if (!allNum) break;
        }
        if (sampled == 0) return "VARCHAR(255)";
        if (allInt) return "BIGINT";
        if (allNum) return "DOUBLE";
        return "VARCHAR(255)";
    }

    private int doBatch(String insert, List<Object[]> batch) {
        int[] n = jdbc.batchUpdate(insert, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                Object[] row = batch.get(i);
                for (int k = 0; k < row.length; k++) ps.setObject(k + 1, row[k]);
            }
            @Override public int getBatchSize() { return batch.size(); }
        });
        int sum = 0; for (int x : n) sum += Math.max(x, 0); return sum;
    }

    private static String clean(String s) {
        String x = s == null ? "" : s.trim().toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (char c : x.toCharArray()) sb.append(Character.isLetterOrDigit(c) ? c : '_');
        if (sb.length() == 0 || !Character.isLetter(sb.charAt(0))) sb.insert(0, 'c');
        return sb.toString();
    }

    private static String unique(String base, java.util.Set<String> used) {
        String n = base; int k = 1;
        while (used.contains(n)) n = base + "_" + (k++);
        used.add(n);
        return n;
    }
}
