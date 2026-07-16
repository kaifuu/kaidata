<div align="center">

<img src="logo.svg" width="128" alt="kaidata LOGO">

# 数据中台 · kaidata

### 现代湖仓架构的企业级数据中台 · 9 大域 · 50+ 子模块

[English](README.en.md) | 中文

多源接入 → Kafka 总线 → StarRocks 实时数仓 → 治理 / 开发 / 服务 / 安全 → 前端门户，全链路真实流转

![Java](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-green) ![Vue](https://img.shields.io/badge/Vue-3.4-42b883) ![Element Plus](https://img.shields.io/badge/Element%20Plus-2.6-409eff) ![StarRocks](https://img.shields.io/badge/StarRocks-3.3.10-blue) ![Kafka](https://img.shields.io/badge/Kafka-3.7.231000) ![Docker](https://img.shields.io/badge/Docker-Compose-2496ed)

</div>

---

## 一、项目简介

kaidata 是一套**面向企业的湖仓一体数据中台**，覆盖数据从"接入"到"消费"的完整生命周期。它不是 Demo 拼装——每一条链路都是真实可跑的：外部数据源经适配器进入 Kafka 总线，落到 StarRocks 实时数仓分层存储，再由治理、开发、资产、安全、服务、集市等域加工、管控并对外发布，最终在前端门户呈现。

- **单机一键部署**：Docker Compose 拉起 MinIO + Kafka + Flink + StarRocks + Hop
- **全链路真实流转**：离线/实时管道端到端入仓可验证
- **三员分立 RBAC**：系统 / 安全 / 审计管理员 + 全量操作审计
- **中英双语 + 双主题**：vue-i18n 国际化，浅色 / 暗色双主题切换

## 二、总体架构

```
外部数据源 / 文件 / 接口 / Kafka
        │
        ▼  数据接入（24 类适配器 + 离线全量/增量 + 实时 JDBC→Kafka→StarRocks）
        ├─▶ StarRocks 实时数仓（MySQL 协议 9030，分层 ODS / DWD / DWS / ADS / DIM）
        │
        ▼  数据治理 · 数据开发 · 数据资产 · 数据安全 · 数据服务 · 数据集市
        │
        ▼  datalake-service（Spring Boot REST API，9 大域）
        │
        ▼  datalake-web（Vue3 门户 + 数据地图 + 数据总览）
```

## 三、功能矩阵（9 大域 50+ 子模块）

| 域 | 子模块 | 能力要点 |
|---|---|---|
| **数据接入** | 数据源管理 / 文件管理 / 离线接入 / 实时接入 / 数据探查 / 接口管理 | 24 类数据源适配器（含国产库 SPI 占位）；FTP/SFTP/CSV 文件；离线全量+增量、**多目标库写入**；实时 **JDBC→Kafka→StarRocks ROUTINE LOAD** 入仓；探查结构变化感知+自动建模 |
| **数据治理** | 数据标准 / 数据模型 / 数据仓库 / 数据质量 / 元数据 / 数据标签 / 主数据 | **数据质量 6 维度**（完整/唯一/有效/及时/准确/一致）+ 严重度加权评分（0-100 / A-D）+ **Word 质量报告导出**；标准/模型/分层仓库/标签/主数据全治理 |
| **数据开发** | 离线开发 / 实时开发 / 脚本开发 / 函数管理 / 任务日志 | SQL(JDBC)/Python/Java/Shell/Scala 脚本执行；任务日志四源聚合 |
| **数据资产** | 资产编目 / 资产挂载 / 资产审核 / 生命周期 | 资产审批状态机（草稿→待审→通过/驳回）；上/下线、解绑零级联安全 |
| **运维中心** | 交互式分析 / 数据概览 / 任务中心 / 任务概览 / 资源监控 / 集群管理 / 执行器 / 连接器 | 运维监控看板，集群探活 + 适配器可用性验证 |
| **数据安全** | 安全标准 / 数据脱敏 / 密钥管理 / 告警 / 黑白名单 / 敏感数据 / 数据权限 | 密钥 CryptoUtil 加密存储；脱敏登记；表级数据权限 |
| **数据服务** | 服务管理 / 数据开放 | SQL 封装 REST；**资产驱动「数据开放」**：appkey 鉴权 + /openapi 端点 + 内存限次/限流/限时 |
| **数据集市** | 数据集 / 资源概览 | 消费方门户：浏览已审核资产 → 全文/分类/标签检索 → 订阅 → 审核 → **通过自动建开放授权 appkey** |
| **系统管理** | 用户 / 组织 / 租户 / 角色 / 菜单 / 日志 | 三员分立 RBAC（SYS/SEC/AUDIT_ADMIN）+ 审计；MyBatis-Plus |

> 另有 **数据地图**（检索 + 类目树 + 三类资产 + 血缘）与 **数据总览** 首页门户。

## 四、技术栈

**后端** (`datalake-service`)
- Spring Boot 3.2.4 · Java 17 · JdbcTemplate 直查（治理/开发/资产/服务/安全/运维/接入/集市域）+ **MyBatis-Plus 3.5.5**（系统管理域）
- HikariCP 动态数据源 · HMAC-SHA256 无状态令牌鉴权 · 图形验证码 · Apache POI 5.2.5（Word 报告）· Spring Scheduling

**前端** (`datalake-web`)
- Vue 3.4 + Element Plus 2.6 + ECharts 5.5（vue-echarts）+ vue-i18n 9 + vue-router 4 + axios + TypeScript + Vite 5
- 浅色(DIFY 风格) / 暗色(霓虹科技) 双主题

**大数据组件** (`docker/`)
- StarRocks 3.3.10（实时数仓，MySQL 协议）/ Kafka 3.7.0（KRaft）/ Flink 1.18 / MinIO / Apache Hop 2.10

**部署**：Docker Compose 单机一键

## 五、快速开始

```bash
# 方式一：一键启动（推荐）—— 拉起大数据栈 + 后端(8090) + 前端(5173)，幂等
bash start.sh

# 方式二：分步手动
cd docker && bash bring-up.sh                 # ① 大数据组件 + 建数仓分层库
cd ../datalake-service && mvn -DskipTests package && java -jar target/datalake-service.jar  # ② 后端
cd ../datalake-web && npm install && npm run dev                                            # ③ 前端
```

打开 **http://localhost:5173** → 登录 **admin / admin123**

> 停服：`bash stop.sh`（`bash stop.sh --all` 连大数据栈一起停）

## 六、端口速查

| 服务 | 端口 |
|---|---|
| 前端 (Vite) | 5173 |
| 服务层 API | 8090 |
| StarRocks FE（MySQL 协议 / Web） | 9030 / 8030 |
| Kafka（宿主机 / 容器内） | 9094 / 9092 |
| Flink Web | 8081 |
| MinIO 控制台 | 9001 |
| Hop Server | 8082 |

## 七、目录结构

```
kaidata/
├── datalake-service/   # 后端：Spring Boot REST（9 大域）
├── datalake-web/       # 前端：Vue3 + Element Plus 门户
├── docker/             # 大数据组件编排（compose + bring-up.sh + DDL）
├── docs/               # 文档
├── start.sh / stop.sh  # 一键启停
└── logo.svg
```

## 八、亮点功能

- 🔌 **24 类数据源适配器**：开源驱动实测（PG/ClickHouse/SQLServer/Oracle/TDengine/MySQL…）+ 国产库（达梦/人大金仓/南大通用）SPI 占位框架
- 🔄 **真实实时入仓**：JDBC 轮询 → Kafka → StarRocks ROUTINE LOAD，OFFSET_BEGINNING 逐分区消费根治"入仓 0 行"，主键去重
- 📊 **数据质量评分体系**：6 维度 + 严重度加权 → 综合质量分 + 等级（BLOCKER 失败封顶 D），一键导出 **Word(.docx) 质量报告** + 页内雷达图仪表盘
- 🔍 **数据探查**：结构快照 + 版本比对 + 首次自动建模到目标分层
- 🌐 **数据开放**：资产驱动 appkey 鉴权 + /openapi 端点 + 内存级限流限次限时
- 🛒 **数据集市订阅**：浏览 → 检索 → 订阅 → 审核 → 通过自动建开放授权
- 🔐 **三员分立**：SYS / SEC / AUDIT 管理员 RBAC + 全量操作审计
- 🌏 **中英双语 + 双主题**：vue-i18n 国际化，浅色 / 暗色统一

## 九、默认账户

| 用户 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | 超级管理员（SYS + SEC + AUDIT） |

> ⚠️ 生产部署请务必修改默认密码并启用 HTTPS。

## License

MIT
