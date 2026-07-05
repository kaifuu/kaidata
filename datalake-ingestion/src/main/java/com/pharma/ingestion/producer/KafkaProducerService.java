package com.pharma.ingestion.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Kafka 生产者封装
 * <p>
 * 统一负责向 Kafka 投递消息。acks=all 保证不丢消息（合规要求：数据完整不丢失）。
 * 模拟器生成的各类数据通过本类发往对应 topic。
 */
public class KafkaProducerService {

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper json = new ObjectMapper();

    public KafkaProducerService(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");          // 所有副本确认，保证不丢
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 50);        // 批量发送，提升吞吐
        this.producer = new KafkaProducer<>(props);
    }

    /** 占位启动方法（KafkaProducer 惰性建连，此处仅语义占位） */
    public void start() {
        System.out.println("[Producer] 已初始化，目标 Kafka 已配置");
    }

    /**
     * 发送消息到指定 topic
     *
     * @param topic   Kafka topic
     * @param key     分区键（如 deviceId / batchNo）
     * @param payload 消息体对象（JSON 序列化）
     */
    public void send(String topic, String key, Object payload) {
        try {
            String value = json.writeValueAsString(payload);
            producer.send(new ProducerRecord<>(topic, key, value), (meta, e) -> {
                if (e != null) {
                    System.err.println("[Producer] 发送失败 topic=" + topic + " err=" + e.getMessage());
                }
            });
        } catch (JsonProcessingException e) {
            System.err.println("[Producer] JSON 序列化失败: " + e.getMessage());
        }
    }

    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }
}
