package com.pharma.service.access.ingest;

import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.access.util.TypeMapper;
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
 * 离线抽取执行器：从源数据源 SELECT → 按列元数据自动建 StarRocks 目标表 → 批量写入。
 * <ul>
 *   <li>FULL：DUPLICATE KEY 表，truncate + 批量 insert</li>
 *   <li>INCREMENTAL：PRIMARY KEY 表（按业务键），批量 insert（StarRocks 主键模型自动去重 upsert）</li>
 * </ul>
 * INSERT 列顺序严格按 {@link StarRocksDdlBuilder#plan} 的重排结果，与建表列序一致。
 */
@Component
public class IngestExecutor {

    @Autowired private JdbcTemplate target;

    public static class Result {
        public final long rowsRead;
        public final long rowsWritten;
        public final List<String> columns;
        public Result(long read, long written, List<String> columns) { this.rowsRead = read; this.rowsWritten = written; this.columns = columns; }
    }

    /**
     * @param sourceSql   源查询 SQL（FULL 为 SELECT *，INCREMENTAL 已含 WHERE）
     * @param targetDb    目标库
     * @param targetTable 目标表
     * @param incremental true=增量(PRIMARY KEY 表)，false=全量(DUPLICATE KEY 表+truncate)
     * @param bizKey      业务唯一键（增量模式下作主键；全量模式下作 DUPLICATE KEY，空则取首列）
     */
    public Result execute(DataSource source, String sourceSql, String targetDb, String targetTable,
                          boolean incremental, String bizKey) throws Exception {
        StarRocksDdlBuilder.ident(targetDb);
        StarRocksDdlBuilder.ident(targetTable);
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
                    String srType = TypeMapper.toStarRocks(md.getColumnType(i), md.getColumnTypeName(i),
                            md.getPrecision(i), md.getScale(i));
                    cols.add(new StarRocksDdlBuilder.ColumnDef(md.getColumnLabel(i), srType));
                }
                StarRocksDdlBuilder.KeyPlan plan = StarRocksDdlBuilder.plan(cols, bizKey);

                // 建表（IF NOT EXISTS）
                target.execute(StarRocksDdlBuilder.build(targetDb, targetTable, cols, bizKey, incremental));
                if (!incremental) target.execute("TRUNCATE TABLE `" + targetDb + "`.`" + targetTable + "`");

                // 源列名 → rs 索引（按原始顺序）
                Map<String, Integer> srcIdx = new HashMap<>();
                for (int i = 0; i < cols.size(); i++) srcIdx.put(cols.get(i).name.toLowerCase(), i + 1);

                // INSERT 语句（列顺序 = plan.ordered）
                String colList = plan.ordered.stream().map(cd -> "`" + cd.name + "`")
                        .collect(Collectors.joining(","));
                String placeholders = plan.ordered.stream().map(x -> "?").collect(Collectors.joining(","));
                String insert = "INSERT INTO `" + targetDb + "`.`" + targetTable + "` (" + colList
                        + ") VALUES (" + placeholders + ")";

                List<Object[]> batch = new ArrayList<>(1000);
                while (rs.next()) {
                    Object[] row = new Object[plan.ordered.size()];
                    for (int j = 0; j < plan.ordered.size(); j++) {
                        Integer idx = srcIdx.get(plan.ordered.get(j).name.toLowerCase());
                        row[j] = idx == null ? null : rs.getObject(idx);
                    }
                    batch.add(row);
                    read++;
                    if (batch.size() >= 1000) {
                        written += doBatch(insert, batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) written += doBatch(insert, batch);

                List<String> colNames = plan.ordered.stream().map(cd -> cd.name).collect(Collectors.toList());
                return new Result(read, written, colNames);
            }
        }
    }

    private int doBatch(String insert, List<Object[]> batch) {
        int[] n = target.batchUpdate(insert, new BatchPreparedStatementSetter() {
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
