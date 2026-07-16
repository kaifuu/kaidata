package com.pharma.service.access.kafka;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Kafka 管理 / 只读消费工具（实时接入用）。
 * <ul>
 *   <li>{@link #ensureTopic}：启动作业前显式建 topic（1 分区 / 副本 1），消除 auto-create 边际
 *       —— StarRocks ROUTINE LOAD 消费 auto-create 出来的 topic 常出现 receivedBytes=0、数据不入仓，
 *       故统一在启动前显式建好 topic。</li>
 *   <li>{@link #latestMessages}：拉取 topic 最近 N 条消息，供前端数据预览。</li>
 *   <li>{@link #topicExists}：判断 topic 是否已存在。</li>
 * </ul>
 * 全部 best-effort：失败只记日志不抛（topic 可能已被 auto-create 建好 / 消费瞬时失败不应阻断主流程）。
 */
@Component
public class KafkaAdminHolder {

    private final AdminClient admin;
    private final String bootstrap;

    public KafkaAdminHolder(@Value("${pharma.kafka.bootstrap:localhost:9094}") String bootstrap) {
        this.bootstrap = bootstrap;
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(AdminClientConfig.CLIENT_ID_CONFIG, "datalake-admin");
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        this.admin = AdminClient.create(p);
        System.out.println("[KafkaAdmin] bootstrap=" + bootstrap);
    }

    /** 显式建 topic（不存在则建，已存在视为成功）。返回是否“可用”（已存在或新建成功）。 */
    public boolean ensureTopic(String topic) {
        try {
            admin.createTopics(Collections.singleton(new NewTopic(topic, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
            System.out.println("[KafkaAdmin] 已显式创建 topic=" + topic);
            return true;
        } catch (Exception e) {
            // 多为 TopicExistsException（auto-create 已建）→ 视为可用；其余失败也放行，由后续 routine load / 生产者暴露错误
            return topicExists(topic);
        }
    }

    public boolean topicExists(String topic) {
        try {
            return admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(topic);
        } catch (Exception e) {
            return false;
        }
    }

    /** topic 分区数（routine load 按 kafka_partitions/kafka_offsets 逐分区指定起点用）。失败回退 1。 */
    public int partitionCount(String topic) {
        try {
            return admin.describeTopics(Collections.singleton(topic))
                    .allTopicNames().get(10, TimeUnit.SECONDS).get(topic).partitions().size();
        } catch (Exception e) {
            return 1;
        }
    }

    /** 拉取 topic 最近 max 条消息的 value（JSON 字符串），按时间顺序。 */
    public List<String> latestMessages(String topic, int max) {
        if (max <= 0) max = 20;
        List<String> out = new ArrayList<>();
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "datalake-preview-" + UUID.randomUUID());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Math.max(max, 500));
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            List<PartitionInfo> parts = c.partitionsFor(topic, Duration.ofSeconds(10));
            if (parts == null || parts.isEmpty()) return out;
            List<TopicPartition> tps = parts.stream()
                    .map(pi -> new TopicPartition(topic, pi.partition()))
                    .collect(Collectors.toList());
            c.assign(tps);
            Map<TopicPartition, Long> ends = c.endOffsets(tps, Duration.ofSeconds(10));
            for (TopicPartition tp : tps) {
                long end = ends.getOrDefault(tp, 0L);
                long start = Math.max(0, end - max);
                if (start < end) c.seek(tp, start);
            }
            int empty = 0;
            while (out.size() < max && empty < 3) {
                var records = c.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) { empty++; continue; }
                for (ConsumerRecord<String, String> r : records) {
                    out.add(r.value());
                    if (out.size() >= max) break;
                }
            }
            if (out.size() > max) out = new ArrayList<>(out.subList(out.size() - max, out.size()));
        } catch (Exception e) {
            System.err.println("[KafkaAdmin] latestMessages topic=" + topic + " err=" + e.getMessage());
        }
        return out;
    }

    @PreDestroy
    public void close() { try { admin.close(Duration.ofSeconds(5)); } catch (Exception ignored) {} }
}
