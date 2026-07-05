#!/usr/bin/env bash
# ============================================================
# Kafka topic 初始化
# 在 pharma-kafka 容器内执行，显式创建三个数据 topic 并配置分区。
# （docker-compose 已开 auto-create-topics，本脚本用于规范化分区数与副本数。）
#
# 用法：
#   docker exec -i pharma-kafka sh < docker/init/kafka-topics.sh
# 或（容器外，使用 apache/kafka 镜像临时容器）：
#   docker run --rm --network=pharma-bigdata_default apache/kafka:3.7.0 \
#     sh -c "$(cat docker/init/kafka-topics.sh)"
# ============================================================
set -e
BOOTSTRAP="${BOOTSTRAP:-kafka:9092}"
BIN="${KAFKA_BIN:-/opt/kafka/bin/kafka-topics.sh}"

create_topic() {
  local topic="$1" partitions="${2:-3}"
  echo "[kafka-init] 创建 topic=$topic partitions=$partitions"
  "$BIN" --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1
}

# 采集模拟器投递的三类数据 topic
create_topic "pharma-env"   3   # 环境监测（温湿度压差）→ Flink 实时入仓
create_topic "pharma-batch" 1   # 批次完工事件          → Flink 入 ods_batch
create_topic "pharma-qc"    1   # 检验结果              → Flink 入 ods_qc

echo "[kafka-init] 当前 topic 列表："
"$BIN" --bootstrap-server "$BOOTSTRAP" --list
