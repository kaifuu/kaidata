package com.pharma.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.tuple.Tuple5;
import org.apache.flink.api.java.tuple.Tuple7;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.jdbc.JdbcStatementBuilder;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;

import java.sql.PreparedStatement;
import java.sql.Timestamp;

/**
 * 批次/检验入库 Flink 作业（实时流）
 * <p>
 * 与 EnvironmentMonitorJob 配套，补齐批处理流的"入仓"环节：
 *   ① Kafka(pharma-batch) → Doris ods.ods_batch
 *   ② Kafka(pharma-qc)    → Doris ods.ods_qc
 * <p>
 * 没有本作业，批次/检验数据只停在 Kafka，数仓 ODS 为空，下游 Spark 分层加工与
 * 批次质量追溯页面将无数据。本作业让"批处理流"真正贯通：采集 → Kafka → Flink 入仓。
 * <p>
 * 提交：flink run -c com.pharma.streaming.BatchIngestJob datalake-streaming.jar
 *   --kafka kafka:9092 --doris starrocks:9030
 */
public class BatchIngestJob {

    public static void main(String[] args) throws Exception {
        String kafka = arg(args, "kafka", "kafka:9092");
        String dorisUrl = "jdbc:mysql://" + arg(args, "doris", "starrocks:9030") + "/?useSSL=false";
        String dorisUser = arg(args, "dorisUser", "root");
        String dorisPwd = arg(args, "dorisPwd", "");

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(30000);

        JdbcConnectionOptions dorisOpts = new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                .withUrl(dorisUrl)
                .withDriverName("com.mysql.cj.jdbc.Driver")
                .withUsername(dorisUser)
                .withPassword(dorisPwd)
                .build();

        // ---------- ① 批次完工：Kafka → ods.ods_batch ----------
        DataStream<String> batchRaw = env.fromSource(
                kafkaSource(kafka, "pharma-batch", "pharma-batch-consumer"),
                WatermarkStrategy.noWatermarks(), "pharma-batch-source");

        SingleOutputStreamOperator<Tuple5<String, String, Integer, String, Long>> batchParsed = batchRaw.map(new BatchParser());

        batchParsed.addSink(JdbcSink.sink(
                "INSERT INTO ods.ods_batch(batch_no, material_code, quantity, status, ts) VALUES (?,?,?,?,?)",
                (JdbcStatementBuilder<Tuple5<String, String, Integer, String, Long>>) (ps, t) -> {
                    ps.setString(1, t.f0);
                    ps.setString(2, t.f1);
                    ps.setInt(3, t.f2);
                    ps.setString(4, t.f3);
                    ps.setTimestamp(5, new Timestamp(t.f4));
                },
                JdbcExecutionOptions.builder().withBatchSize(100).withBatchIntervalMs(2000).build(),
                dorisOpts
        )).name("doris-batch-sink");

        // ---------- ② 检验结果：Kafka → ods.ods_qc ----------
        DataStream<String> qcRaw = env.fromSource(
                kafkaSource(kafka, "pharma-qc", "pharma-qc-consumer"),
                WatermarkStrategy.noWatermarks(), "pharma-qc-source");

        SingleOutputStreamOperator<Tuple7<String, String, String, Double, Double, Boolean, Long>> qcParsed = qcRaw.map(new QcParser());

        qcParsed.addSink(JdbcSink.sink(
                "INSERT INTO ods.ods_qc(batch_no, material_code, test_item, result, spec, pass, ts) VALUES (?,?,?,?,?,?,?)",
                (JdbcStatementBuilder<Tuple7<String, String, String, Double, Double, Boolean, Long>>) (ps, t) -> {
                    ps.setString(1, t.f0);
                    ps.setString(2, t.f1);
                    ps.setString(3, t.f2);
                    ps.setDouble(4, t.f3);
                    ps.setDouble(5, t.f4);
                    ps.setBoolean(6, t.f5);
                    ps.setTimestamp(7, new Timestamp(t.f6));
                },
                JdbcExecutionOptions.builder().withBatchSize(100).withBatchIntervalMs(2000).build(),
                dorisOpts
        )).name("doris-qc-sink");

        env.execute("PharmaBatchIngestJob");
    }

    /** 构建 Kafka Source（String 反序列化，从已提交 offset 续读，无则从最新开始） */
    private static KafkaSource<String> kafkaSource(String bootstrap, String topic, String group) {
        return KafkaSource.<String>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topic)
                .setGroupId(group)
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST))
                .setValueOnlyDeserializer(new org.apache.flink.api.common.serialization.SimpleStringSchema())
                .build();
    }

    /** 批次记录解析：{batchNo,materialCode,quantity,status,ts} → Tuple5 */
    public static class BatchParser
            implements MapFunction<String, Tuple5<String, String, Integer, String, Long>> {
        private transient ObjectMapper mapper;

        @Override
        public Tuple5<String, String, Integer, String, Long> map(String value) throws Exception {
            if (mapper == null) mapper = new ObjectMapper();
            JsonNode n = mapper.readTree(value);
            return Tuple5.of(
                    n.get("batchNo").asText(),
                    n.get("materialCode").asText(),
                    n.get("quantity").asInt(),
                    n.get("status").asText(),
                    n.get("ts").asLong()
            );
        }
    }

    /** 检验记录解析：{batchNo,materialCode,testItem,result,spec,pass,ts} → Tuple7 */
    public static class QcParser
            implements MapFunction<String, Tuple7<String, String, String, Double, Double, Boolean, Long>> {
        private transient ObjectMapper mapper;

        @Override
        public Tuple7<String, String, String, Double, Double, Boolean, Long> map(String value) throws Exception {
            if (mapper == null) mapper = new ObjectMapper();
            JsonNode n = mapper.readTree(value);
            return Tuple7.of(
                    n.get("batchNo").asText(),
                    n.get("materialCode").asText(),
                    n.get("testItem").asText(),
                    n.get("result").asDouble(),
                    n.get("spec").asDouble(),
                    n.get("pass").asBoolean(),
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
