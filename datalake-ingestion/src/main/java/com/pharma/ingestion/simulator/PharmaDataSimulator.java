package com.pharma.ingestion.simulator;

import com.pharma.ingestion.producer.KafkaProducerService;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 药厂数据模拟器
 * <p>
 * 模拟药厂三类核心数据，定时投递 Kafka，让大数据平台有"真实流动的数据"：
 *   1. 环境监测（温湿度压差）—— 高频(2s)，偶尔越限（供 Flink 实时告警）
 *   2. 批次完工事件           —— 30s/条（供批处理数仓分层）
 *   3. 检验结果               —— 30s/条（供批次质量追溯）
 * <p>
 * 数据贴合调研报告：AHU/纯化水/注射用水设备、阿莫西林胶囊批次、温湿度压差合规区间。
 */
public class PharmaDataSimulator {

    /** Kafka topic 定义 */
    public static final String TOPIC_ENV = "pharma-env";
    public static final String TOPIC_BATCH = "pharma-batch";
    public static final String TOPIC_QC = "pharma-qc";

    private final KafkaProducerService producer;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final Random rnd = new Random();

    /** 最近完工批次队列（[batchNo, materialCode]），供 emitQc 关联，保证 batch ⋈ qc 能 join 上 */
    private final java.util.concurrent.ConcurrentLinkedDeque<String[]> recentBatches =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** 模拟的监测设备 */
    private final List<String> devices = List.of("AHU-01", "AHU-02", "WFI-001", "PW-001");
    /** 模拟的物料/批次 */
    private final List<String> materials = List.of("FG-04001", "FG-04002", "FG-04003");
    private int batchSeq = 1;

    public PharmaDataSimulator(KafkaProducerService producer) {
        this.producer = producer;
    }

    /** 启动并阻塞持续生成数据 */
    public void run() {
        // 1. 环境数据：每 2 秒，每设备一个指标
        scheduler.scheduleAtFixedRate(this::emitEnvironment, 0, 2, TimeUnit.SECONDS);
        // 2. 批次完工：每 30 秒
        scheduler.scheduleAtFixedRate(this::emitBatch, 5, 30, TimeUnit.SECONDS);
        // 3. 检验结果：每 30 秒
        scheduler.scheduleAtFixedRate(this::emitQc, 10, 30, TimeUnit.SECONDS);

        // 阻塞主线程
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 生成环境监测数据（温湿度压差，偶尔越限） */
    private void emitEnvironment() {
        try {
            String device = devices.get(rnd.nextInt(devices.size()));
            long ts = System.currentTimeMillis();
            // 温度：正常 18~26，约 8% 概率越限到 27~29
            double temp = rnd.nextDouble() < 0.08 ? 27 + rnd.nextDouble() * 2 : 18 + rnd.nextDouble() * 8;
            // 湿度：正常 45~65
            double humidity = 45 + rnd.nextDouble() * 20;
            // 压差：正常 10~15，约 5% 概率越限到 7~9
            double diffPressure = rnd.nextDouble() < 0.05 ? 7 + rnd.nextDouble() * 2 : 10 + rnd.nextDouble() * 5;

            // 每条环境记录一个指标，便于 Flink 按指标流式处理
            producer.send(TOPIC_ENV, device, envRecord(device, "temp", round(temp), 18, 26, ts));
            producer.send(TOPIC_ENV, device, envRecord(device, "humidity", round(humidity), 45, 65, ts));
            producer.send(TOPIC_ENV, device, envRecord(device, "diffPressure", round(diffPressure), 10, 999, ts));
        } catch (Exception e) {
            System.err.println("[Simulator] 环境数据生成异常: " + e.getMessage());
        }
    }

    /** 生成批次完工事件 */
    private void emitBatch() {
        try {
            String batchNo = "B" + System.currentTimeMillis() / 1000 + "-" + batchSeq++;
            String material = materials.get(rnd.nextInt(materials.size()));
            int qty = 9000 + rnd.nextInt(2000);
            String status = rnd.nextDouble() < 0.95 ? "COMPLETED" : "ABNORMAL";
            producer.send(TOPIC_BATCH, batchNo, batchRecord(batchNo, material, qty, status, System.currentTimeMillis()));
            // 入队供 emitQc 关联（保证下游 batch ⋈ qc 能 join 上，批次追溯有数据）
            recentBatches.offerFirst(new String[]{batchNo, material});
            while (recentBatches.size() > 50) recentBatches.removeLast();
        } catch (Exception e) {
            System.err.println("[Simulator] 批次数据生成异常: " + e.getMessage());
        }
    }

    /** 生成检验结果（关联一个最近完工的批次，与其共享 batchNo） */
    private void emitQc() {
        try {
            String[] ref = recentBatches.pollFirst();   // 取最近一个未检验的批次
            String batchNo;
            String material;
            if (ref != null) {
                batchNo = ref[0];
                material = ref[1];
            } else {
                // 冷启动尚无批次时生成独立检验（下游 LEFT JOIN 仍可展示）
                batchNo = "B" + System.currentTimeMillis() / 1000 + "-0";
                material = materials.get(rnd.nextInt(materials.size()));
            }
            String testItem = List.of("含量", "水分", "溶出度").get(rnd.nextInt(3));
            double result = 95 + rnd.nextDouble() * 5;
            double spec = 92.0;
            boolean pass = result >= spec;
            producer.send(TOPIC_QC, batchNo, qcRecord(batchNo, material, testItem, round(result), spec, pass, System.currentTimeMillis()));
        } catch (Exception e) {
            System.err.println("[Simulator] 检验数据生成异常: " + e.getMessage());
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    // -------- 记录构造（POJO 用 LinkedHashMap 简化，避免多写类）--------

    private java.util.Map<String, Object> envRecord(String device, String metric, double value,
                                                    double min, double max, long ts) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("deviceId", device);
        m.put("metric", metric);
        m.put("value", value);
        m.put("min", min);
        m.put("max", max);
        m.put("ts", ts);
        return m;
    }

    private java.util.Map<String, Object> batchRecord(String batchNo, String material, int qty,
                                                      String status, long ts) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("batchNo", batchNo);
        m.put("materialCode", material);
        m.put("quantity", qty);
        m.put("status", status);
        m.put("ts", ts);
        return m;
    }

    private java.util.Map<String, Object> qcRecord(String batchNo, String material, String testItem,
                                                   double result, double spec, boolean pass, long ts) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("batchNo", batchNo);
        m.put("materialCode", material);
        m.put("testItem", testItem);
        m.put("result", result);
        m.put("spec", spec);
        m.put("pass", pass);
        m.put("ts", ts);
        return m;
    }

    private double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
