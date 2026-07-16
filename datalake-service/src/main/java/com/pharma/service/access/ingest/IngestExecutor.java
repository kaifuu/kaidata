package com.pharma.service.access.ingest;

import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.access.util.TargetDialect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 离线抽取执行器：从源数据源 SELECT → 按列元数据自动建目标表 → 批量写入。
 * <p>目标可为任意关系型数据源（由 targetPool + targetType 指定，方言见 {@link TargetDialect}）：
 * <ul>
 *   <li>FULL：starrocks/doris=DUPLICATE KEY 表，其它=普通表；统一 truncate + 批量 insert</li>
 *   <li>INCREMENTAL：仅 starrocks/doris（PRIMARY KEY 表，主键模型自动去重 upsert），由上层校验拦截</li>
 * </ul>
 * INSERT 列顺序：starrocks/doris 按 {@link StarRocksDdlBuilder#plan} 重排（键列前缀）；其它方言按源顺序。
 */
@Component
public class IngestExecutor {

    /** 主库（target_ds_id 为空时的回退写入目标）。 */
    @Autowired private JdbcTemplate target;

    public static class Result {
        public final long rowsRead;
        public final long rowsWritten;
        public final List<String> columns;
        public Result(long read, long written, List<String> columns) { this.rowsRead = read; this.rowsWritten = written; this.columns = columns; }
    }

    /**
     * @param source      源数据源连接池
     * @param sourceSql   源查询 SQL（FULL=SELECT *，INCREMENTAL 已含 WHERE）
     * @param targetDb    目标库（空=走目标连接默认 schema）
     * @param targetTable 目标表
     * @param incremental true=增量(仅 starrocks/doris，PRIMARY KEY 表)，false=全量
     * @param bizKey      业务唯一键（增量作主键；全量 starrocks 作 DUPLICATE KEY，空取首列）
     * @param targetPool  目标连接池；null=写主库（target_ds_id 为空时）
     * @param targetType  目标数据源类型（决定方言）；null=主库(starrocks)
     */
    public Result execute(DataSource source, String sourceSql, String targetDb, String targetTable,
                          boolean incremental, String bizKey,
                          DataSource targetPool, String targetType) throws Exception {
        StarRocksDdlBuilder.ident(targetDb);
        StarRocksDdlBuilder.ident(targetTable);
        JdbcTemplate tgt = (targetPool == null) ? this.target : new JdbcTemplate(targetPool);
        TargetDialect d = TargetDialect.forType(targetType);
        long read = 0, written = 0;
        try (Connection c = source.getConnection();
             PreparedStatement ps = c.prepareStatement(sourceSql,
                     ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            ps.setFetchSize(1000);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<StarRocksDdlBuilder.ColumnDef> cols = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    String ty = d.mapType(md.getColumnType(i), md.getColumnTypeName(i),
                            md.getPrecision(i), md.getScale(i));
                    cols.add(new StarRocksDdlBuilder.ColumnDef(md.getColumnLabel(i), ty));
                }

                // 建表（IF NOT EXISTS）+ FULL 清表（统一 TRUNCATE）
                tgt.execute(d.createTable(targetDb, targetTable, cols, bizKey, incremental));
                if (!incremental) tgt.execute(d.clearTable(targetDb, targetTable));

                // 源列名 → rs 索引（按原始顺序）
                Map<String, Integer> srcIdx = new HashMap<>();
                for (int i = 0; i < cols.size(); i++) srcIdx.put(cols.get(i).name.toLowerCase(), i + 1);

                // INSERT 列顺序：starrocks/doris 重排键列到前缀；其它方言用源顺序
                List<StarRocksDdlBuilder.ColumnDef> insertCols = d.reorderKeyPrefix()
                        ? StarRocksDdlBuilder.plan(cols, bizKey).ordered : cols;

                String colList = insertCols.stream().map(cd -> d.quote(cd.name))
                        .collect(Collectors.joining(","));
                String placeholders = insertCols.stream().map(x -> "?").collect(Collectors.joining(","));
                String insert = "INSERT INTO " + d.qualify(targetDb, targetTable)
                        + " (" + colList + ") VALUES (" + placeholders + ")";

                List<Object[]> batch = new ArrayList<>(1000);
                while (rs.next()) {
                    Object[] row = new Object[insertCols.size()];
                    for (int j = 0; j < insertCols.size(); j++) {
                        Integer idx = srcIdx.get(insertCols.get(j).name.toLowerCase());
                        row[j] = idx == null ? null : rs.getObject(idx);
                    }
                    batch.add(row);
                    read++;
                    if (batch.size() >= 1000) {
                        written += doBatch(tgt, insert, batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) written += doBatch(tgt, insert, batch);

                List<String> colNames = insertCols.stream().map(cd -> cd.name).collect(Collectors.toList());
                return new Result(read, written, colNames);
            }
        }
    }

    private int doBatch(JdbcTemplate tgt, String insert, List<Object[]> batch) {
        int[] n = tgt.batchUpdate(insert, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws java.sql.SQLException {
                Object[] row = batch.get(i);
                for (int k = 0; k < row.length; k++) ps.setObject(k + 1, row[k]);
            }
            @Override public int getBatchSize() { return batch.size(); }
        });
        int sum = 0;
        for (int x : n) sum += Math.max(x, 0);
        return sum;
    }
}
