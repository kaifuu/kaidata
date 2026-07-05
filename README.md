<div align="center">

<img src="logo.svg" width="128" alt="kaidata LOGO">

# 数据中台 · kaidata

### 现代湖仓架构的企业级数据中台 · 10 大域 40+ 子模块

模拟器 → Kafka → StarRocks(ROUTINE LOAD) → Spark 分层 → 数仓 → API → 前端，全链路真实流转

![Java](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-green) ![Vue](https://img.shields.io/badge/Vue-3.4-42b883) ![Element Plus](https://img.shields.io/badge/Element%20Plus-2.6-409eff) ![StarRocks](https://img.shields.io/badge/StarRocks-3.3-blue) ![Docker](https://img.shields.io/badge/Docker-Compose-2496ed)

</div>

---

## 一、总体架构

```
数据模拟器 / 外部数据源 / 接口
        │
        ▼  Kafka 总线
 ├─▶ StarRocks ROUTINE LOAD（实时入 ODS）
 ├─▶ Spark 批计算（ODS → DWD → DWS/ADS 分层加工）
        │
        ▼  StarRocks 实时数仓（MySQL 协议 9030）
        │
        ▼  datalake-service（Spring Boot REST API）
        │
        ▼  datalake-web（Vue3 门户）
```

单机 Docker Compose 一键部署：**MinIO + Kafka + Flink + Spark + StarRocks**。

## 二、功能矩阵（10 大域 40+ 子模块）

| 域 | 子模块 | 说明 |
|---|---|---|
| **湖仓主线** | 模拟器 / Kafka / StarRocks / Spark / API / 前端 | 采→存→算→服 真实流转 |
| **数据接入** | 数据源管理(13类) / 文件管理 / 离线接入 / 实时接入 / 数据探查 / 接口管理 | 多源接入，含国产库适配器框架 |
| **数据治理** | 数据标准 / 数据模型 / 数据仓库(分层) / 数据质量 / 元数据 / 数据标签 / 主数据 | 全治理体系 |
| **数据开发** | 函数管理 / SQL 工作台 / 数据接出 / 工作流(任务链) | 在线开发+调度 |
| **数据资产** | 资产编目 / 资产挂载 / 资产审核 | 状态机审批 |
| **运维中心** | 交互式分析 / 数据概览 / 任务中心 / 任务概览 / 资源监控 / 集群管理 / 执行器 / 连接器 | 运维监控看板 |
| **数据安全** | 安全标准 / 数据脱敏 / 密钥管理 / 告警管理 / 黑白名单 / 敏感数据 / 数据权限 | 全安全管控 |
| **数据服务** | 服务管理 / 调用统计 | SQL 封装为 REST，支持 /open 公开免鉴权 |
| **数据集市** | 数据集 / 资源概览 | 消费方门户，接口/库表对接 + 购物车 |
| **系统管理** | 用户/组织/租户/角色/菜单/日志 | 三员分立 RBAC + 审计 |

## 三、技术栈

- **后端**：Spring Boot 3.2.4 / Java 17 / JdbcTemplate（无 MyBatis）/ HikariCP / HMAC-SHA256 JWT 自实现鉴权
- **前端**：Vue 3.4 + Element Plus 2.6 + ECharts 5.5 + vue-router 4 + axios + Vite 5
- **大数据**：StarRocks 3.3（MySQL 协议数仓）/ Kafka 3.7（KRaft）/ Flink 1.18 / Spark 3.5 / MinIO
- **部署**：Docker Compose（单机一键）
- **主题**：浅色(DIFY 风格) + 暗色(霓虹科技) 双主题切换

## 四、快速开始

```bash
# 1. 起大数据组件 + 打通数据流
cd docker && docker compose up -d && bash bring-up.sh

# 2. 采集模拟器（持续投递 Kafka）
cd ../datalake-ingestion && mvn -q -DskipTests package
java -jar target/datalake-ingestion.jar localhost:9094

# 3. 服务层 API（8090）
cd ../datalake-service && mvn -q -DskipTests package
java -jar target/datalake-service.jar

# 4. 前端（5173）
cd ../datalake-web && npm install && npm run dev
```

打开 http://localhost:5173 → 登录 **admin / admin123**

## 五、端口速查

| 服务 | 端口 |
|---|---|
| 前端 (Vite) | 5173 |
| 服务层 API | 8090 |
| StarRocks (MySQL 协议) | 9030 |
| Kafka (宿主机) | 9094 |
| Flink Web | 8081 |
| MinIO 控制台 | 9001 |

## 六、目录结构

```
kaidata/
├── datalake-ingestion/   # 采集层：数据模拟器 → Kafka
├── datalake-streaming/   # 流计算：Flink（本机依赖受限，ROUTINE LOAD 桥接替代）
├── datalake-batch/       # 批计算：Spark 分层加工
├── datalake-service/     # 服务层：Spring Boot REST（10 大域后端）
├── datalake-web/         # 前端：Vue3 + Element Plus
└── docker/               # 大数据组件编排（compose + init 脚本）
```

## 七、亮点

- **真实流转**：模拟器→Kafka→StarRocks→Spark→API→前端，端到端可验证
- **13 类数据源适配器**：开源驱动实测 + 国产库（达梦/人大金仓/南大通用）SPI 占位
- **数据探查**：结构变化感知 + 版本比对 + 自动建模
- **数据服务**：SQL 封装 REST，`/open/{code}` 公开免鉴权对外发布
- **数据集市**：消费方门户，接口/库表双对接 + 购物车
- **双主题**：浅色/暗色统一 + 科技感

## License

MIT
