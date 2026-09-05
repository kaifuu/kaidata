#!/usr/bin/env bash
# ============================================================
# 一键拉起大数据组件（通用数据中台）
# ------------------------------------------------------------
# 执行步骤：
#   1. docker compose up -d          起 MinIO/IcebergREST/Kafka/Flink JM+TM/StarRocks
#   2. 等 Kafka 就绪 + 建 topic（按需）
#   3. 等 Iceberg REST Catalog 就绪 + MinIO 建湖桶 lake
#   4. 等 StarRocks 可查询
#   5. 执行 doris-ddl.sql            建数仓分层库（ods/dwd/dws/ads/dim）
#   6. 执行 iceberg-catalog.sql      挂 Iceberg External Catalog（重复执行容错）
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
docker exec -i pharma-kafka sh < init/kafka-topics.sh 2>&1 | grep -E "创建 topic" || true

echo "==> [3/6] 等待 Iceberg REST Catalog（8181）并建湖桶"
i=0
until curl -s http://localhost:8181/v1/config >/dev/null 2>&1; do
  i=$((i + 1))
  if [ $i -gt 20 ]; then echo "    Iceberg REST 未就绪（湖表功能暂不可用，其余不受影响）"; break; fi
  sleep 3
done
if [ $i -le 20 ]; then
  echo "    Iceberg REST 就绪（${i} 次探测）。建 MinIO 湖桶 lake："
  # minio/mc 一次性容器建桶（幂等：已存在则跳过）；桶是 S3FileIO 写数据文件的前提
  # 注意 mc 镜像 ENTRYPOINT=mc，须 --entrypoint sh 才能跑复合命令
  docker run --rm --network pharma-bigdata_default --entrypoint sh minio/mc:latest -c \
    "mc alias set m http://minio:9000 minioadmin minioadmin >/dev/null 2>&1 && mc mb --ignore-existing m/lake && echo '    湖桶 s3://lake 就绪'"
fi

echo "==> [4/6] 等待 StarRocks 可查询（最多 ~3 分钟）..."
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

echo "==> [5/6] 执行 doris-ddl.sql（建数仓分层库）"
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root < init/doris-ddl.sql

echo "==> [6/6] 挂载 Iceberg External Catalog（SR 查湖表入口）"
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root < init/iceberg-catalog.sql 2>/dev/null \
  || echo "    （已存在或暂不可建，可稍后手工执行 init/iceberg-catalog.sql）"

echo "==> 附加：Flink SQL Gateway 探测（8083，离线 FlinkSQL/FlinkDag 用；可选，失败不阻塞）"
i=0
until docker exec pharma-flink-sql-gateway curl -s http://localhost:8083/v3/info >/dev/null 2>&1; do
  i=$((i + 1))
  if [ $i -gt 20 ]; then echo "    SQL Gateway 未就绪（离线 FlinkSQL 作业暂不可用，其余作业不受影响）"; break; fi
  sleep 3
done
if [ $i -le 20 ]; then echo "    SQL Gateway 就绪（${i} 次探测）"; fi

echo "==> 附加：Apache Hop Server 探测（8082，离线 Kettle/Hop 用；可选，失败不阻塞）"
i=0
until docker exec pharma-hop curl -s http://localhost:8080/hop/status >/dev/null 2>&1; do
  i=$((i + 1))
  if [ $i -gt 20 ]; then echo "    Hop Server 未就绪（离线 Kettle/Hop 作业暂不可用，其余作业不受影响）"; break; fi
  sleep 3
done
if [ $i -le 20 ]; then echo "    Hop Server 就绪（${i} 次探测）"; fi

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
