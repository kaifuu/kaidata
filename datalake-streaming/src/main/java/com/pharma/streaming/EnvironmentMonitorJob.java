package com.pharma.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.tuple.Tuple6;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.jdbc.JdbcStatementBuilder;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import java.sql.PreparedStatement;
import java.sql.Timestamp;

/**
 * 环境监测 Flink 作业（实时流计算）
 * <p>
 * 数据流：Kafka(pharma-env) → Flink 解析 →
 *   ① 全量写入 Doris ods_env_monitor（实时入仓，OLAP 可查）
 *   ② 越限记录写入 Doris ods_alarm（实时告警）
 * <p>
 * 这是大数据平台"流计算"的核心：数据真正经过 Flink 引擎实时计算并落仓，而非静态 CRUD。
 * <p>
 * 提交：flink run -c com.pharma.streaming.EnvironmentMonitorJob datalake-streaming.jar
 *   --kafka kafka:9092 --doris doris:9030
 * 容器内 Kafka 地址 kafka:9092（INTERNAL listener），Doris 地址 doris:9030。
 */
public class EnvironmentMonitorJob {

    public static void main(String[] args) throws Exception {
        // 参数解析（Flink 用 ParameterTool 更规范，此处简化）
        String kafka = arg(args, "kafka", "kafka:9092");
        String dorisUrl = "jdbc:mysql://" + arg(args, "doris", "starrocks:9030") + "/?useSSL=false";
        String dorisUser = arg(args, "dorisUser", "root");
        String dorisPwd = arg(args, "dorisPwd", "");

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(30000);   // 30s checkpoint，保证 exactly-once

        // ---------- 1. Kafka Source：消费环境数据 ----------
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(kafka)
                .setTopics("pharma-env")
                .setGroupId("pharma-env-consumer")
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST))
                .setValueOnlyDeserializer(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                .build();

        DataStream<String> rawStream = env.fromSource(kafkaSource,
                WatermarkStrategy.noWatermarks(), "pharma-env-source");

        // ---------- 2. 解析 JSON 为 Tuple(deviceId, metric, value, min, max, ts) ----------
        SingleOutputStreamOperator<Tuple6<String, String, Double, Double, Double, Long>> parsed = rawStream
                .map(new EnvParser());

        // ---------- 3. Sink A：全量写 Doris ods_env_monitor ----------
        JdbcConnectionOptions dorisOpts = new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                .withUrl(dorisUrl)
                .withDriverName("com.mysql.cj.jdbc.Driver")
                .withUsername(dorisUser)
                .withPassword(dorisPwd)
                .build();

        parsed.addSink(JdbcSink.sink(
                "INSERT INTO ods.ods_env_monitor(device_id, metric, value, min_val, max_val, ts) VALUES (?,?,?,?,?,?)",
                (JdbcStatementBuilder<Tuple6<String, String, Double, Double, Double, Long>>) (ps, t) -> {
                    ps.setString(1, t.f0);
                    ps.setString(2, t.f1);
                    ps.setDouble(3, t.f2);
                    ps.setDouble(4, t.f3);
                    ps.setDouble(5, t.f4);
                    ps.setTimestamp(6, new Timestamp(t.f5));
                },
                JdbcExecutionOptions.builder().withBatchSize(100).withBatchIntervalMs(2000).build(),
                dorisOpts
        )).name("doris-env-sink");

        // ---------- 4. Sink B：越限记录写 Doris ods_alarm（实时告警） ----------
        SingleOutputStreamOperator<Tuple6<String, String, Double, Double, Double, Long>> alarms = parsed
                .process(new ProcessFunction<Tuple6<String, String, Double, Double, Double, Long>,
                        Tuple6<String, String, Double, Double, Double, Long>>() {
                    @Override
                    public void processElement(Tuple6<String, String, Double, Double, Double, Long> t,
                                               Context ctx, Collector<Tuple6<String, String, Double, Double, Double, Long>> out) {
                        // 越限判定：value > max 或 value < min
                        if (t.f2 > t.f4 || t.f2 < t.f3) {
                            out.collect(t);
                        }
                    }
                });

        alarms.addSink(JdbcSink.sink(
                "INSERT INTO ods.ods_alarm(device_id, metric, value, min_val, max_val, severity, ts) " +
                        "VALUES (?,?,?,?,?,?,?)",
                (JdbcStatementBuilder<Tuple6<String, String, Double, Double, Double, Long>>) (ps, t) -> {
                    ps.setString(1, t.f0);
                    ps.setString(2, t.f1);
                    ps.setDouble(3, t.f2);
                    ps.setDouble(4, t.f3);
                    ps.setDouble(5, t.f4);
                    ps.setString(6, t.f2 > t.f4 ? "CRITICAL" : "WARN");   // 超上限严重，低于下限警告
                    ps.setTimestamp(7, new Timestamp(t.f5));
                },
                JdbcExecutionOptions.builder().withBatchSize(10).build(),
                dorisOpts
        )).name("doris-alarm-sink");

        env.execute("PharmaEnvironmentMonitorJob");
    }

    /** JSON 解析算子：{"deviceId","metric","value","min","max","ts"} → Tuple6 */
    public static class EnvParser
            implements MapFunction<String, Tuple6<String, String, Double, Double, Double, Long>> {
        private transient ObjectMapper mapper;

        @Override
        public Tuple6<String, String, Double, Double, Double, Long> map(String value) throws Exception {
            if (mapper == null) mapper = new ObjectMapper();
            JsonNode n = mapper.readTree(value);
            return Tuple6.of(
                    n.get("deviceId").asText(),
                    n.get("metric").asText(),
                    n.get("value").asDouble(),
                    n.get("min").asDouble(),
                    n.get("max").asDouble(),
                    n.get("ts").asLong()
            );
        }
    }

    private static String arg(String[] args, String key, String def) {
        for (String a : args) {
            if (a.startsWith("--" + key + "=")) return a.substring(key.length() + 3);
        }
        return def;
    }
}
