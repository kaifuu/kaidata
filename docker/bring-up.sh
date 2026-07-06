#!/usr/bin/env bash
# ============================================================
# 一键拉起大数据组件（通用数据中台）
# ------------------------------------------------------------
# 执行步骤：
#   1. docker compose up -d          起 MinIO/Kafka/Flink JM+TM/StarRocks
#   2. 等 Kafka 就绪 + 建 topic（按需）
#   3. 等 StarRocks 可查询
#   4. 执行 doris-ddl.sql            建数仓分层库（ods/dwd/dws/ads/dim）
#
# 用法： bash docker/bring-up.sh
# 停服：  cd docker && docker compose down       （保留数据卷）
#        cd docker && docker compose down -v     （彻底重置）
# ============================================================
set -e
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

echo "==> [1/4] docker compose up -d"
docker compose up -d

echo "==> [2/4] 等待 Kafka 就绪并建 topic"
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
docker exec -i pharma-kafka sh < init/kafka-topics.sh 2>&1 | grep -E "创建 topic" || true

echo "==> [3/4] 等待 StarRocks 可查询（最多 ~3 分钟）..."
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

echo "==> [4/4] 执行 doris-ddl.sql（建数仓分层库）"
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root < init/doris-ddl.sql

cat <<'EOF'

================ bring-up 完成 ================
大数据组件已起，数仓分层库（ods/dwd/dws/ads/dim）已就绪。
接下来在【宿主机】起服务层与前端（新开终端）：

  # 服务层（API 查 StarRocks）
  cd datalake-service && mvn -q -DskipTests package
  java -jar target/datalake-service.jar

  # 前端
  cd datalake-web && npm install && npm run dev    # http://localhost:5173

登录前端：admin / admin123
================================================
EOF
