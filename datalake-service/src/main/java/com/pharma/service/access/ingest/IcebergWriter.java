package com.pharma.service.access.ingest;

import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.IcebergAdapter;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFileFactory;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Iceberg 湖表写入器：离线接入目标为 iceberg 数据源时替代 JDBC 写入路径
 * （REST Catalog 提交元数据 + S3FileIO 把 Parquet 数据文件写 MinIO）。
 * <ul>
 *   <li>FULL：{@code newOverwrite().overwriteByRowFilter(alwaysTrue())} —— 快照级原子替换整表数据</li>
 *   <li>INCREMENTAL：{@code newAppend()} —— 追加新数据文件（新快照，可回溯）</li>
 *   <li>表不存在按源 ResultSetMetaData 推断建表（zstd 压缩、无分区）；已存在则按列名（忽略大小写）
 *       对齐既有 Schema：多出的源列跳过、缺失的目标列写 null</li>
 * </ul>
 * StarRocks 侧经 External Catalog 三段名（iceberg_catalog.ns.tbl）写入即可查。
 */
@Component
public class IcebergWriter {

    /** 单数据文件行数上限（到达即滚动新文件，防单文件过大）。 */
    private static final int ROWS_PER_FILE = 100_000;

    public IngestExecutor.Result execute(DataSource source, String sourceSql, DataSourceDescriptor targetDs,
                                         String ns, String table, boolean append) throws Exception {
        ns = IcebergAdapter.nsOf(ns);   // 兼容三段名前缀形态（iceberg_catalog.demo → demo）
        StarRocksDdlBuilder.ident(ns);
        StarRocksDdlBuilder.ident(table);
        try (RESTCatalog catalog = IcebergAdapter.catalog(targetDs)) {
            try {
                catalog.createNamespace(Namespace.of(ns));
            } catch (AlreadyExistsException ignored) {}

            try (Connection c = source.getConnection();
                 PreparedStatement ps = c.prepareStatement(sourceSql,
                         ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(1000);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();

                    TableIdentifier tid = TableIdentifier.of(ns, table);
                    Table tbl;
                    if (catalog.tableExists(tid)) {
                        tbl = catalog.loadTable(tid);
                    } else {
                        List<Types.NestedField> fields = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) {
                            fields.add(Types.NestedField.optional(i, md.getColumnLabel(i),
                                    icebergType(md.getColumnType(i), md.getPrecision(i), md.getScale(i))));
                        }
                        Map<String, String> props = new HashMap<>();
                        props.put("write.parquet.compression-codec", "zstd");
                        tbl = catalog.createTable(tid, new Schema(fields), PartitionSpec.unpartitioned(), props);
                    }

                    // 湖表字段序 → 源 rs 列序（1-based；0=源缺失该列，写 null）
                    Schema schema = tbl.schema();
                    int[] idxFor = new int[schema.columns().size()];
                    for (int fi = 0; fi < idxFor.length; fi++) {
                        idxFor[fi] = findIdx(md, n, schema.columns().get(fi).name());
                    }

                    // 写 Parquet（GenericAppenderFactory 公开 API；按行数滚动多文件）
                    List<DataFile> files = new ArrayList<>();
                    OutputFileFactory off = OutputFileFactory.builderFor(tbl, 0, 0).build();
                    GenericAppenderFactory factory = new GenericAppenderFactory(schema);
                    try { factory.setAll(tbl.properties()); } catch (Exception ignored) {}
                    GenericRecord rec = GenericRecord.create(schema);
                    long read = 0;
                    DataWriter<Record> w = null;
                    while (rs.next()) {
                        if (w == null) {
                            w = factory.newDataWriter(off.newOutputFile(), FileFormat.PARQUET, null);
                        }
                        for (int fi = 0; fi < idxFor.length; fi++) {
                            rec.set(fi, idxFor[fi] == 0 ? null : convert(rs, idxFor[fi], schema.columns().get(fi)));
                        }
                        w.write(rec.copy());
                        read++;
                        if (read % ROWS_PER_FILE == 0) {
                            files.add(toDataFile(w));
                            w = null;
                        }
                    }
                    if (w != null) files.add(toDataFile(w));

                    // 提交快照：FULL=原子替换整表数据；INCREMENTAL=追加
                    if (append) {
                        var af = tbl.newAppend();
                        files.forEach(af::appendFile);
                        af.commit();
                    } else {
                        var ow = tbl.newOverwrite();
                        ow.overwriteByRowFilter(Expressions.alwaysTrue());
                        files.forEach(ow::addFile);
                        ow.commit();
                    }

                    long written = files.stream().mapToLong(DataFile::recordCount).sum();
                    List<String> colNames = schema.columns().stream().map(Types.NestedField::name).collect(Collectors.toList());
                    List<String[]> colTypes = schema.columns().stream()
                            .map(f -> new String[]{f.name(), f.type().toString()})
                            .collect(Collectors.toList());
                    return new IngestExecutor.Result(read, written, colNames, colTypes);
                }
            }
        }
    }

    // ---------------- 助手 ----------------

    /** 源 rs 中与湖表字段同名（忽略大小写）的列序；找不到返回 0。 */
    private static int findIdx(ResultSetMetaData md, int n, String fieldName) throws SQLException {
        String target = fieldName.toLowerCase();
        for (int i = 1; i <= n; i++) {
            if (md.getColumnLabel(i).toLowerCase().equals(target)) return i;
        }
        return 0;
    }

    /** 关闭写入器并取 DataFile（路径/大小/指标由 DataWriter 统计，无需手工拼）。 */
    private static DataFile toDataFile(DataWriter<Record> w) {
        try {
            w.close();
            return w.toDataFile();
        } catch (IOException e) {
            throw new RuntimeException("Parquet 数据文件关闭失败：" + e.getMessage(), e);
        }
    }

    // ===================== 模型落地：按列定义建湖表 =====================

    /**
     * 按列定义建湖表（数据模型「建物理表」目标为 iceberg 数据源时走此路径）。
     *
     * @param cols 每列 {name, type, comment?}，type 为模型类型名（STRING/BIGINT/DECIMAL(18,2)/…），
     *             经 {@link #modelIcebergType} 映射；类型不认识时 string 兜底（不阻断建模）
     * @return true=新建成功；false=表已存在（幂等，不报错）
     */
    public boolean createTable(DataSourceDescriptor targetDs, String ns, String table,
                               List<Map<String, Object>> cols) {
        StarRocksDdlBuilder.ident(ns);
        StarRocksDdlBuilder.ident(table);
        try (RESTCatalog catalog = IcebergAdapter.catalog(targetDs)) {
            try {
                catalog.createNamespace(Namespace.of(ns));
            } catch (AlreadyExistsException ignored) {}
            TableIdentifier tid = TableIdentifier.of(ns, table);
            if (catalog.tableExists(tid)) return false;
            List<Types.NestedField> fields = new ArrayList<>(cols.size());
            for (int i = 0; i < cols.size(); i++) {
                Map<String, Object> c = cols.get(i);
                String nm = String.valueOf(c.get("name"));
                String doc = c.get("comment") == null ? null : String.valueOf(c.get("comment"));
                fields.add(Types.NestedField.optional(i + 1, nm, modelIcebergType(String.valueOf(c.get("type"))), doc));
            }
            Map<String, String> props = new HashMap<>();
            props.put("write.parquet.compression-codec", "zstd");
            catalog.createTable(tid, new Schema(fields), PartitionSpec.unpartitioned(), props);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("湖表建表失败：" + rootMsg(e), e);
        }
    }

    /** 模型类型名（STRING/BIGINT/DECIMAL(18,2)…）→ Iceberg 类型；未知一律 string 兜底。 */
    static org.apache.iceberg.types.Type modelIcebergType(String t) {
        String s = t == null ? "" : t.trim().toUpperCase();
        if (s.startsWith("DECIMAL") || s.startsWith("NUMERIC")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+)\\s*,\\s*(\\d+)\\)").matcher(s);
            if (m.find()) {
                int p = Integer.parseInt(m.group(1)), sc = Integer.parseInt(m.group(2));
                if (p >= 1 && p <= 38 && sc >= 0 && sc <= p) return Types.DecimalType.of(p, sc);
            }
            return Types.DecimalType.of(38, 10);
        }
        switch (s) {
            case "BOOLEAN": case "BOOL": return Types.BooleanType.get();
            case "TINYINT": case "SMALLINT": case "INT": case "INTEGER": return Types.IntegerType.get();
            case "BIGINT": case "LONG": case "LARGEINT": return Types.LongType.get();
            case "FLOAT": case "REAL": return Types.FloatType.get();
            case "DOUBLE": return Types.DoubleType.get();
            case "DATE": return Types.DateType.get();
            case "DATETIME": case "TIMESTAMP": return Types.TimestampType.withoutZone();
            case "BINARY": case "BYTES": return Types.BinaryType.get();
            default: return Types.StringType.get();   // STRING/VARCHAR/CHAR/TEXT/JSON/未知
        }
    }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }

    /** JDBC 列类型 → Iceberg 类型（超界 decimal 降级 string；未知类型一律 string 兜底）。 */
    static org.apache.iceberg.types.Type icebergType(int jdbcType, int precision, int scale) {
        switch (jdbcType) {
            case java.sql.Types.BOOLEAN:
            case java.sql.Types.BIT:
                return Types.BooleanType.get();
            case java.sql.Types.TINYINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.INTEGER:
                return Types.IntegerType.get();
            case java.sql.Types.BIGINT:
                return Types.LongType.get();
            case java.sql.Types.FLOAT:
            case java.sql.Types.REAL:
                return Types.FloatType.get();
            case java.sql.Types.DOUBLE:
                return Types.DoubleType.get();
            case java.sql.Types.DECIMAL:
            case java.sql.Types.NUMERIC:
                if (precision >= 1 && precision <= 38 && scale >= 0 && scale <= precision) {
                    return Types.DecimalType.of(precision, scale);
                }
                return Types.StringType.get();
            case java.sql.Types.DATE:
                return Types.DateType.get();
            case java.sql.Types.TIMESTAMP:
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:   // 统一落 withoutZone（与 SR DATETIME 对读友好）
                return Types.TimestampType.withoutZone();
            case java.sql.Types.BINARY:
            case java.sql.Types.VARBINARY:
            case java.sql.Types.LONGVARBINARY:
            case java.sql.Types.BLOB:
                return Types.BinaryType.get();
            default:   // CHAR/VARCHAR/TEXT/CLOB/JSON/枚举等
                return Types.StringType.get();
        }
    }

    /** 按湖表字段类型转换 rs 值（decimal 对齐 scale；date→epoch day；timestamp→微秒）。 */
    private static Object convert(ResultSet rs, int i, Types.NestedField f) throws SQLException {
        org.apache.iceberg.types.Type t = f.type();
        if (t instanceof Types.StringType) return rs.getString(i);
        if (t instanceof Types.BooleanType) { boolean v = rs.getBoolean(i); return rs.wasNull() ? null : v; }
        if (t instanceof Types.IntegerType) { int v = rs.getInt(i); return rs.wasNull() ? null : v; }
        if (t instanceof Types.LongType) { long v = rs.getLong(i); return rs.wasNull() ? null : v; }
        if (t instanceof Types.FloatType) { float v = rs.getFloat(i); return rs.wasNull() ? null : v; }
        if (t instanceof Types.DoubleType) { double v = rs.getDouble(i); return rs.wasNull() ? null : v; }
        if (t instanceof Types.DecimalType) {
            BigDecimal v = rs.getBigDecimal(i);
            if (v == null) return null;
            int scale = ((Types.DecimalType) t).scale();
            return v.scale() == scale ? v : v.setScale(scale, RoundingMode.HALF_UP);
        }
        if (t instanceof Types.DateType) {
            java.sql.Date v = rs.getDate(i);
            return v == null ? null : (int) v.toLocalDate().toEpochDay();
        }
        if (t instanceof Types.TimestampType) {
            Timestamp ts = rs.getTimestamp(i);
            return ts == null ? null : ts.getTime() * 1000 + ts.getNanos() / 1000 % 1000;   // 毫秒→微秒+亚毫秒微秒
        }
        if (t instanceof Types.BinaryType) return rs.getBytes(i);
        return rs.getObject(i);
    }
}
