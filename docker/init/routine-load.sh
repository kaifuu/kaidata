#!/usr/bin/env bash
# ============================================================
# StarRocks ROUTINE LOAD 桥接：Flink 缺席时的数据通道
# ------------------------------------------------------------
# 背景：streaming 模块的 Flink 依赖(org.apache.flink:*:1.18.1)在本机网络
#       拉不到，jar 建不出来。为保证平台"采→存→算→服"仍能端到端流转，
#       改由 StarRocks 直接消费 Kafka 入 ODS，等价替代 Flink 入仓环节。
#
# 本脚本创建三个 routine load（幂等）：
#   rl_env   pharma-env   → ods.ods_env_monitor
#   rl_batch pharma-batch → ods.ods_batch
#   rl_qc    pharma-qc    → ods.ods_qc
#
# 幂等策略：作业不存在则 CREATE；已存在则 RESUME（按已提交 offset 续读，
#   不会重复消费）。切勿无条件 DROP+重建——消费组名带 UUID 后缀，重建后
#   丢失已提交 offset，earliest 会把 topic 历史消息重灌一遍，ODS 翻倍。
#
# 告警(ods_alarm)由周期性 INSERT...SELECT 去重生成，见 ../alarm-bridge.sh。
#
# StarRocks 3.3.10 已踩的坑（务必遵守）：
#   ① PROPERTIES 里不要写 desired_concurrent（不认，建作业报错）
#   ② FROM KAFKA 里不要写 kafka_group_id（报 "invalid kafka custom property"；
#      3.3.10 消费组由作业名自动派生，group.id 形如 rl_env_<uuid>）
#   ③ 不要写 kafka_default_offsets（不认）；从头消费用
#      property.auto.offset.reset = earliest（实测 3.3.10 可用）
#   ④ 模拟器 ts 是毫秒数，目标列是 DATETIME，用
#      ts = from_unixtime(CAST(ts_ms / 1000 AS INT))
#      （源字段先别名 ts_ms 避免与目标列 ts 同名冲突）
#   ⑤ SHOW ROUTINE LOAD 需在库上下文（mysql 指定库名，否则 No database selected）
#   ⑥ topic 必须先于作业存在，否则作业进 PAUSED(unknown topic)；bring-up.sh 已先建 topic
#
# 用法（宿主机，StarRocks 与 Kafka 已起，topic 已建）：
#   bash docker/init/routine-load.sh
# 验证：
#   docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root \
#     -e "SHOW ROUTINE LOAD\G"
# ============================================================
set -u
export MSYS_NO_PATHCONV=1

# mysql 客户端跑在 starrocks 容器内，宿主机经 docker exec 调用
MYSQL=(docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root --default-character-set=utf8mb4)

# 作业不存在→CREATE 成功；已存在→CREATE 报错→RESUME 续读。两者都无重复消费。
# $1=作业名, 其余参数=CREATE 语句的剩余 SQL（COLUMNS 起到分号前）
ensure_rl() {
  local job="$1"; shift
  if "${MYSQL[@]}" ods -e "CREATE ROUTINE LOAD ods.${job} $*" 2>/dev/null; then
    echo "    ${job}: 已创建"
  else
    echo "    ${job}: 已存在 → RESUME（按已提交 offset 续读，不重复消费）"
    "${MYSQL[@]}" ods -e "RESUME ROUTINE LOAD FOR ods.${job};" 2>/dev/null || true
  fi
}

echo "[routine-load] 确保 rl_env / rl_batch / rl_qc（CREATE 或 RESUME）..."

# ---------- rl_env：环境监测 → ods_env_monitor ----------
ensure_rl rl_env "ON ods_env_monitor
COLUMNS(
  device_id, metric, value, min_val, max_val,
  ts_ms,
  ts = from_unixtime(CAST(ts_ms / 1000 AS INT))
)
PROPERTIES(
  'format' = 'json',
  'jsonpaths' = '[\"\$.deviceId\",\"\$.metric\",\"\$.value\",\"\$.min\",\"\$.max\",\"\$.ts\"]',
  'strip_outer_array' = 'false'
)
FROM KAFKA(
  'kafka_broker_list' = 'kafka:9092',
  'kafka_topic' = 'pharma-env',
  'property.auto.offset.reset' = 'earliest'
);"

# ---------- rl_batch：批次完工 → ods_batch ----------
ensure_rl rl_batch "ON ods_batch
COLUMNS(
  batch_no, material_code, quantity, status,
  ts_ms,
  ts = from_unixtime(CAST(ts_ms / 1000 AS INT))
)
PROPERTIES(
  'format' = 'json',
  'jsonpaths' = '[\"\$.batchNo\",\"\$.materialCode\",\"\$.quantity\",\"\$.status\",\"\$.ts\"]'
)
FROM KAFKA(
  'kafka_broker_list' = 'kafka:9092',
  'kafka_topic' = 'pharma-batch',
  'property.auto.offset.reset' = 'earliest'
);"

# ---------- rl_qc：检验结果 → ods_qc ----------
ensure_rl rl_qc "ON ods_qc
COLUMNS(
  batch_no, material_code, test_item, result, spec, pass,
  ts_ms,
  ts = from_unixtime(CAST(ts_ms / 1000 AS INT))
)
PROPERTIES(
  'format' = 'json',
  'jsonpaths' = '[\"\$.batchNo\",\"\$.materialCode\",\"\$.testItem\",\"\$.result\",\"\$.spec\",\"\$.pass\",\"\$.ts\"]'
)
FROM KAFKA(
  'kafka_broker_list' = 'kafka:9092',
  'kafka_topic' = 'pharma-qc',
  'property.auto.offset.reset' = 'earliest'
);"

echo "[routine-load] 等待 FE 调度（6s）..."
sleep 6

echo ""
echo "[routine-load] 当前 routine load 状态："
"${MYSQL[@]}" ods -e "SHOW ROUTINE LOAD\G" | grep -E "Name:|TableName:|State:|TopicName:|Statistic:|ReasonOfStateChanged:" || true

echo ""
echo "[routine-load] 完成。State 正常为 RUNNING / NEED_SCHEDULE（瞬态，稍后转 RUNNING）。"
echo "  若为 PAUSED 且 ReasonOfStateChanged 含 'unknown topic'：Kafka topic 尚未建，"
echo "  先执行 'docker exec -i pharma-kafka sh < docker/init/kafka-topics.sh' 再重跑本脚本。"
echo "  采集器投递后 Statistic.totalRows 会持续增长。"
