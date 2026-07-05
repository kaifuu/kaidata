package com.pharma.service.access.kafka;

import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JDBC→Kafka 轮询器：每个 RUNNING 的 JDBC_TO_KAFKA 作业按 schedule_cron（秒间隔）轮询源 SQL，
 * 每行转 JSON 投递到 Kafka topic。
 * <p>非真实 CDC（拉式轮询），毫秒级实时性留 Phase B（Debezium）。
 */
@Component
public class JdbcToKafkaRunner {

    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private KafkaProducerHolder producer;
    @Autowired private JdbcTemplate jdbc;

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "jdbc-to-kafka"); t.setDaemon(true); return t;
    });
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    /** 启动一个作业的定时轮询。 */
    public void start(long jobId, long dsId, String sourceQuery, String topic, int periodSec) {
        stop(jobId);
        ScheduledFuture<?> f = pool.scheduleAtFixedRate(() -> runOnce(jobId, dsId, sourceQuery, topic),
                0, Math.max(periodSec, 1), TimeUnit.SECONDS);
        tasks.put(jobId, f);
    }

    public void stop(long jobId) {
        ScheduledFuture<?> f = tasks.remove(jobId);
        if (f != null) f.cancel(false);
    }

    public boolean isRunning(long jobId) { return tasks.containsKey(jobId); }

    private void runOnce(long jobId, long dsId, String sourceQuery, String topic) {
        try {
            DataSourceDescriptor ds = loader.load(dsId);
            DataSource pool = registry.getPool(ds);
            long out = 0;
            try (Connection c = pool.getConnection();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sourceQuery)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String key = null;
                    for (int i = 1; i <= n; i++) {
                        String col = md.getColumnLabel(i);
                        Object v = rs.getObject(i);
                        row.put(col, v);
                        if (key == null) key = v == null ? null : String.valueOf(v);
                    }
                    producer.send(topic, key == null ? String.valueOf(System.nanoTime()) : key, row);
                    out++;
                }
            }
            recordRun(jobId, out);
        } catch (Exception e) {
            recordFail(jobId, rootMsg(e));
        }
    }

    private void recordRun(long jobId, long out) {
        try {
            long id = System.currentTimeMillis();
            jdbc.update("INSERT INTO meta.ing_stream_run(id, job_id, start_time, end_time, status, rows_in, rows_out, error_msg) " +
                    "VALUES (?,?,?,?,?,?,?,?)", id, jobId, new java.sql.Timestamp(id), new java.sql.Timestamp(System.currentTimeMillis()),
                    "SUCCESS", out, out, "");
        } catch (Exception ignored) {}
    }

    private void recordFail(long jobId, String msg) {
        try {
            long id = System.currentTimeMillis();
            jdbc.update("INSERT INTO meta.ing_stream_run(id, job_id, start_time, end_time, status, rows_in, rows_out, error_msg) " +
                    "VALUES (?,?,?,?,?,?,?,?)", id, jobId, new java.sql.Timestamp(id), new java.sql.Timestamp(System.currentTimeMillis()),
                    "ERROR", 0, 0, msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        } catch (Exception ignored) {}
    }

    @PreDestroy
    public void shutdown() { pool.shutdownNow(); }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
