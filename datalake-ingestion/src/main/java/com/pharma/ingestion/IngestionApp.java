package com.pharma.ingestion;

import com.pharma.ingestion.producer.KafkaProducerService;
import com.pharma.ingestion.simulator.PharmaDataSimulator;

/**
 * 采集层入口
 * <p>
 * 启动药厂数据模拟器，持续向 Kafka 投递三类数据：
 *   - 环境监测数据（温湿度压差，2s/条）→ topic: pharma-env
 *   - 批次完工事件（30s/条）          → topic: pharma-batch
 *   - 检验结果（30s/条）              → topic: pharma-qc
 * <p>
 * 这是整个大数据平台的"数据源头"——数据从这里真正开始流转。
 * 生产环境替换为真实连接器（OPCUA/AMQP/SAP），模拟器仅用于无真实系统时保证平台有流动数据。
 * <p>
 * 运行：java -jar datalake-ingestion.jar [kafka-bootstrap-servers]
 *   默认 kafka-bootstrap-servers = localhost:9094（宿主机访问容器内 Kafka 的 EXTERNAL 端口）
 */
public class IngestionApp {

    public static void main(String[] args) throws Exception {
        // Kafka 地址：宿主机跑用 localhost:9094；容器内跑用 kafka:9092
        String bootstrap = args.length > 0 ? args[0] : "localhost:9094";

        KafkaProducerService producer = new KafkaProducerService(bootstrap);
        producer.start();   // 连接 Kafka（惰性，首次发送才真正建连）

        PharmaDataSimulator simulator = new PharmaDataSimulator(producer);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Ingestion] 正在停止模拟器...");
            simulator.stop();
            producer.close();
        }));

        System.out.println("[Ingestion] 药厂数据模拟器已启动，Kafka=" + bootstrap);
        simulator.run();   // 阻塞，持续生成数据
    }
}
