package com.pharma.service.access.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简易 SQL 表名抽取：从 FROM/JOIN 后抽取表名（可含 schema.table 前缀）。
 * 简化版：不处理 CTE / 嵌套子查询别名 / 同义词，结果用作「提示性」血缘。
 * 用于解析 ing_stream_job / dev_export 的 source_query 中的源表。
 */
public final class TableExtractor {

    private static final Pattern P = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_][\\w.]*)");

    private TableExtractor() {}

    public static List<String> parse(String sql) {
        List<String> out = new ArrayList<>();
        if (sql == null || sql.isBlank()) return out;
        Matcher m = P.matcher(sql);
        while (m.find()) {
            String t = m.group(1).toLowerCase();
            if (isKeyword(t) || out.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    private static boolean isKeyword(String t) {
        return switch (t) {
            case "select", "where", "group", "order", "limit", "having", "on", "as",
                 "and", "or", "not", "in", "exists", "dual", "union", "inner", "left",
                 "right", "outer", "cross", "natural", "using", "with", "set" -> true;
            default -> false;
        };
    }
}
