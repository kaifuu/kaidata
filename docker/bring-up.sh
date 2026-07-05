#!/usr/bin/env bash
# ============================================================
# 一键拉起大数据组件并打通数据流（Flink 缺席桥接版）
# ------------------------------------------------------------
# 执行步骤：
#   1. docker compose up -d          起 MinIO/Kafka/Flink JM+TM/StarRocks
#   2. 等 Kafka 就绪 + 建 topic       （routine load 找不到 topic 会 PAUSED）
#   3. 等 StarRocks 可查询
#   4. 执行 doris-ddl.sql            建数仓分层表 + 物料维度种子
#   5. 创建 ROUTINE LOAD 桥接        rl_env/rl_batch/rl_qc（Kafka→ODS）
#   6. 后台启动 alarm-bridge.sh      越限告警 ods_alarm 周期去重写入
#
# 起完后数据流即贯通（需另起采集模拟器投 Kafka，再起服务层与前端）：
#   模拟器 → Kafka → [ROUTINE LOAD] → StarRocks ODS → (spark-submit) → ADS → API → 前端
#
# 用法： bash docker/bring-up.sh
# 停服：  cd docker && docker compose down       （保留数据卷）
#        cd docker && docker compose down -v     （彻底重置）
# ============================================================
set -e
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

echo "==> [1/6] docker compose up -d"
docker compose up -d

echo "==> [2/6] 等待 Kafka 就绪并建 topic"
i=0
until docker exec pharma-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list >/dev/null 2>&1; do
  i=$((i + 1))
  if [ $i -gt 30 ]; then
    echo "    Kafka 90 秒仍未就绪，请检查：docker logs pharma-kafka"
    exit 1
  fi
  sleep 3
done
echo "    Kafka 就绪（${i} 次探测）。建 topic："
docker exec -i pharma-kafka sh < init/kafka-topics.sh 2>&1 | grep -E "创建 topic|pharma-" || true

echo "==> [3/6] 等待 StarRocks 可查询（最多 ~3 分钟）..."
i=0
until docker exec pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" >/dev/null 2>&1; do
  i=$((i + 1))
  if [ $i -gt 60 ]; then
    echo "    StarRocks 3 分钟仍未就绪，请检查容器日志：docker logs pharma-starrocks"
    exit 1
  fi
  sleep 3
done
echo "    StarRocks 就绪（${i} 次探测）。"

echo "==> [4/6] 执行 doris-ddl.sql（建表 + 物料维度种子）"
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root < init/doris-ddl.sql

echo "==> [5/6] 创建 ROUTINE LOAD 桥接（rl_env/rl_batch/rl_qc）"
bash init/routine-load.sh

echo "==> [6/6] 后台启动告警桥接 alarm-bridge.sh"
mkdir -p ../logs
if [ -f ../logs/alarm-bridge.pid ] && kill -0 "$(cat ../logs/alarm-bridge.pid 2>/dev/null)" 2>/dev/null; then
  echo "    已有 alarm-bridge 在运行（PID $(cat ../logs/alarm-bridge.pid)），跳过。"
else
  nohup bash alarm-bridge.sh > ../logs/alarm-bridge.log 2>&1 &
  echo $! > ../logs/alarm-bridge.pid
  echo "    alarm-bridge 已后台启动（PID $!，日志 logs/alarm-bridge.log）。"
fi

cat <<'EOF'

================ bring-up 完成 ================
大数据组件已起，数仓分层表已建，ROUTINE LOAD 桥接 + 告警桥接已就位。
接下来在【宿主机】起数据源与服务层（新开终端）：

  # A. 采集模拟器（持续投 env/batch/qc 到 Kafka）
  cd datalake-ingestion && mvn -q -DskipTests package
  java -jar target/datalake-ingestion.jar localhost:9094

  # B. 服务层（API 查 StarRocks）
  cd datalake-service && mvn -q -DskipTests package
  java -jar target/datalake-service.jar

  # C. 前端
  cd datalake-web && npm install && npm run dev    # http://localhost:5173

  # D.（可选）刷新 ADS 宽表：spark-submit WarehouseBuildJob
  docker run --rm --network=pharma-bigdata_default \
    -v "$PWD/datalake-batch/target":/app docker.1ms.run/apache/spark:3.5.1 \
    /opt/spark/bin/spark-submit --class com.pharma.batch.WarehouseBuildJob \
    --jars /app/mysql-connector-j-8.0.33.jar /app/datalake-batch.jar --doris starrocks

登录前端：admin / admin123
================================================
EOF
