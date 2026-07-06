#!/usr/bin/env bash
# ============================================================
# Kafka topic 初始化（通用框架）
# 在 pharma-kafka 容器内执行，按需创建数据 topic 并配置分区/副本。
# （docker-compose 已开 auto-create-topics，本脚本用于规范化分区数与副本数。）
#
# 用法：
#   docker exec -i pharma-kafka sh < docker/init/kafka-topics.sh
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

# 按需在此创建业务 topic（示例）：
# create_topic "your-topic" 3

echo "[kafka-init] 当前 topic 列表："
"$BIN" --bootstrap-server "$BOOTSTRAP" --list
