# 大数据组件编排 (docker/)

通用数据中台的底层基础设施一键部署：MinIO(湖存储) + Kafka(数据总线) + Flink(流计算引擎) + StarRocks(实时数仓, MySQL 协议)。
各组件供中台的「数据接入 / 数据开发 / 数据服务」等模块按需使用。

## 一、目录结构

```
docker/
├── docker-compose.yml      # 常驻组件（MinIO/Kafka/Flink JM+TM/StarRocks），name=pharma-bigdata
├── bring-up.sh             # 一键：compose up + 等 Kafka/StarRocks 就绪 + 建数仓分层库
└── init/
    ├── doris-ddl.sql        # 建数仓分层库（ODS/DWD/DWS/ADS/DIM）
    ├── kafka-topics.sh      # Kafka topic 初始化框架（按需创建，auto-create 已开）
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

**推荐：一键拉起**
```bash
cd docker
bash bring-up.sh          # compose up + 等 StarRocks 就绪 + 建数仓分层库
docker compose ps         # 确认组件 running
```
`bring-up.sh` 会等 Kafka/StarRocks 就绪后，执行 `doris-ddl.sql` 建立 ods/dwd/dws/ads/dim
五个分层库（供中台运行期由「数据仓库分层 / 离线接入 / 数据开发」等模块写入业务表）。

**或：手动分步**

```bash
cd docker
docker compose up -d                # 起组件
docker compose ps                   # 确认全部 running

# 建数仓分层库（首次启动后执行一次）
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root < init/doris-ddl.sql

# Kafka topic（可选，auto-create 已开；按需在 kafka-topics.sh 里加 create_topic）
docker exec -i pharma-kafka sh < init/kafka-topics.sh

# MinIO bucket（可选，湖存储预留）
docker run --rm --network=pharma-bigdata_default \
  -e MC_HOST_pharma=http://minioadmin:minioadmin@minio:9000 \
  minio/mc:latest sh -c "$(cat init/minio-init.sh)"
```

起完组件后，在宿主机启动中台服务层与前端（见根 README「快速开始」）。

## 四、验证各组件可达

```bash
# Kafka topic 列表
docker exec pharma-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list

# StarRocks 查询（应看到 ods/dwd/dws/ads/dim + meta 库）
docker exec -i pharma-starrocks mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW DATABASES;"

# Flink Web UI：浏览器打开 http://localhost:8081
```

## 五、停服与清理

```bash
docker compose down            # 停并删容器（保留数据卷）
docker compose down -v         # 同时删除数据卷（彻底重置）
```

## 六、内存与资源

总内存上限约 6GB（StarRocks 3G + Kafka 1G + Flink JM/TM ~1.3G + MinIO 0.5G）。
单机演示用 `replication_num=1`；生产应扩为 3 节点 3 副本。
