# 大数据组件编排 (docker/)

现代湖仓栈一键部署：MinIO(湖) + Kafka(总线) + Flink(流) + StarRocks(数仓)。
Spark 按需 `spark-submit` 提交，不常驻（省内存）。

## 一、目录结构

```
docker/
├── docker-compose.yml      # 常驻组件（MinIO/Kafka/Flink JM+TM/StarRocks），name=pharma-bigdata
├── bring-up.sh             # 一键：compose up + 建表 + ROUTINE LOAD 桥接 + 后台告警桥接
├── alarm-bridge.sh         # 周期 INSERT...SELECT 去重，把越限读数写 ods_alarm（后台常驻）
└── init/
    ├── doris-ddl.sql        # 数仓分层建表（ODS/DWD/DWS/ADS/DIM）+ 物料维度种子
    ├── routine-load.sh      # 创建 rl_env/rl_batch/rl_qc（StarRocks 消费 Kafka 入 ODS，Flink 桥接）
    ├── kafka-topics.sh      # 建 Kafka topic（auto-create 已开，可选规范化分区）
    └── minio-init.sh        # 建 MinIO bucket
```

> 仓库用 **StarRocks** 替代 Doris：两者同源、同 MySQL 协议(9030)、同 SQL 方言。
> apache/doris 镜像在镜像源不可用且 BE 需调 `max_map_count`，StarRocks allin1 更稳。
> 代码里 "doris" 命名沿用，实际指向 StarRocks。

## 二、端口与地址

| 组件 | 容器间地址（compose 网络） | 宿主机地址 |
|------|---------------------------|-----------|
| Kafka | `kafka:9092` (INTERNAL) | `localhost:9094` (EXTERNAL) |
| StarRocks | `starrocks:9030` (MySQL 协议) | `localhost:9030` |
| MinIO | `minio:9000` (S3) | `localhost:9000` / 控制台 `localhost:9001` |
| Flink Web | `flink-jm:8081` | `localhost:8081` |
| StarRocks FE Web | — | `localhost:8030` |

## 三、启动步骤

**推荐：一键拉起（含数据流桥接）**
```bash
cd docker
bash bring-up.sh          # compose up + 建表 + ROUTINE LOAD 桥接 + 后台告警桥接
docker compose ps         # 确认组件 running
```
`bring-up.sh` 会等 StarRocks 就绪后建表，并创建 `rl_env/rl_batch/rl_qc` 三个
ROUTINE LOAD（本机 Flink 依赖拉不到时，由 StarRocks 直接消费 Kafka 入 ODS），
再后台启动 `alarm-bridge.sh` 生成越限告警。

**或：手动分步**

```bash
cd docker
docker compose up -d                # 起组件
docker compose ps                   # 确认全部 healthy/running
```

初始化（首次启动后执行一次）：

```bash
# 1. 数仓建表（用任意 mysql 客户端连 localhost:9030，root/无密码）
mysql -h 127.0.0.1 -P 9030 -u root < init/doris-ddl.sql
#   或：docker exec -i pharma-starrocks mysql -P 9030 -h 127.0.0.1 -u root < init/doris-ddl.sql

# 2. ROUTINE LOAD 桥接（Kafka → ODS；Flink 缺席时必需）
bash init/routine-load.sh

# 3. 告警桥接（后台；越限读数 → ods_alarm）
nohup bash alarm-bridge.sh > ../logs/alarm-bridge.log 2>&1 &

# 4. Kafka topic（可选，auto-create 已开）
docker exec -i pharma-kafka sh < init/kafka-topics.sh

# 5. MinIO bucket（可选，湖存储预留）
docker run --rm --network=pharma-bigdata_default \
  -e MC_HOST_pharma=http://minioadmin:minioadmin@minio:9000 \
  minio/mc:latest sh -c "$(cat init/minio-init.sh)"
```

## 四、验证各组件可达

```bash
# Kafka topic 列表
docker exec pharma-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list

# StarRocks 查询（应看到 ods/dwd/dws/ads/dim 库）
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW DATABASES;"

# ROUTINE LOAD 状态（State 应为 RUNNING；采集器投递后 NumMessagesReceived 增长）
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW ROUTINE LOAD\G"

# 起采集模拟器后，ODS 应持续入库（验证 Kafka→StarRocks 已打通）
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root \
  -e "SELECT COUNT(*) FROM ods.ods_env_monitor; SELECT COUNT(*) FROM ods.ods_alarm;"

# Flink Web UI
# 浏览器打开 http://localhost:8081
```

## 五、停服与清理

```bash
docker compose down            # 停并删容器（保留数据卷）
docker compose down -v         # 同时删除数据卷（彻底重置）
```

## 六、内存与资源

总内存上限约 6GB（StarRocks 3G + Kafka 1G + Flink JM/TM ~1.3G + MinIO 0.5G）。
单机演示用 `replication_num=1`；生产应扩为 3 节点 3 副本。
