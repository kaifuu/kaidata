package com.pharma.service.access.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Properties;

/**
 * 单例 Kafka 生产者：实时接入 JDBC→Kafka 投递数据行。
 * <p>对齐 datalake-ingestion 的 KafkaProducerService 配置（acks=all、JSON 序列化）。
 */
@Component
public class KafkaProducerHolder {

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper json = new ObjectMapper();

    public KafkaProducerHolder(@Value("${pharma.kafka.bootstrap:localhost:9094}") String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.RETRIES_CONFIG, 3);
        p.put(ProducerConfig.LINGER_MS_CONFIG, 50);
        this.producer = new KafkaProducer<>(p);
        System.out.println("[KafkaProducer] bootstrap=" + bootstrap);
    }

    public void send(String topic, String key, Map<String, Object> row) {
        try {
            producer.send(new ProducerRecord<>(topic, key, json.writeValueAsString(row)),
                    (m, e) -> { if (e != null) System.err.println("[KafkaProducer] 发送失败 topic=" + topic + " err=" + e.getMessage()); });
        } catch (Exception ignored) {}
    }

    @PreDestroy
    public void close() { try { producer.close(); } catch (Exception ignored) {} }
}
