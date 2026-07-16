<div align="center">

<img src="logo.svg" width="128" alt="kaidata LOGO">

# Data Middle Platform · kaidata

### An enterprise-grade data middle platform on a modern lakehouse architecture · 9 domains · 50+ modules

English | **[中文](README.md)**

External sources → Kafka bus → StarRocks real-time warehouse → governance / development / serving / security → web portal — a fully working, end-to-end data pipeline

![Java](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.4-green) ![Vue](https://img.shields.io/badge/Vue-3.4-42b883) ![Element Plus](https://img.shields.io/badge/Element%20Plus-2.6-409eff) ![StarRocks](https://img.shields.io/badge/StarRocks-3.3.10-blue) ![Kafka](https://img.shields.io/badge/Kafka-3.7.231000) ![Docker](https://img.shields.io/badge/Docker-Compose-2496ed)

</div>

---

## 1. Overview

kaidata is an **enterprise lakehouse data middle platform** covering the full data lifecycle — from *ingestion* to *consumption*. It is not a stitched-together demo: every pipeline runs for real. External sources flow through adapters into a Kafka bus, land in the StarRocks real-time warehouse (layered storage), then get refined, governed, secured and published by the governance, development, asset, security, serving and marketplace domains — finally surfacing in the web portal.

- **One-click single-node deploy**: Docker Compose brings up MinIO + Kafka + Flink + StarRocks + Hop
- **Real end-to-end pipelines**: offline and streaming ingestion verified to actually land data
- **Three-role RBAC**: System / Security / Audit administrators + full operation audit
- **Bilingual + dual theme**: vue-i18n (zh/en), light (DIFY) / dark (neon) theme switch

## 2. Architecture

```
External sources / files / APIs / Kafka
        │
        ▼  Data Access (24 adapters + offline full/incremental + streaming JDBC→Kafka→StarRocks)
        ├─▶ StarRocks real-time warehouse (MySQL protocol :9030, layers ODS / DWD / DWS / ADS / DIM)
        │
        ▼  Governance · Development · Asset · Security · Serving · Marketplace
        │
        ▼  datalake-service (Spring Boot REST API, 9 domains)
        │
        ▼  datalake-web (Vue3 portal + data map + overview)
```

## 3. Feature Matrix (9 domains, 50+ modules)

| Domain | Modules | Highlights |
|---|---|---|
| **Data Access** | Datasource / File / Offline / Streaming / Profiling / API | 24 datasource adapters (incl. domestic-DB SPI placeholders); FTP/SFTP/CSV; offline full+incremental with **multi-target write**; streaming **JDBC→Kafka→StarRocks ROUTINE LOAD**; profiling with schema-change detection + auto-modeling |
| **Data Governance** | Standard / Model / Warehouse / Quality / Metadata / Tag / Master Data | **6-dimension quality** (completeness/uniqueness/validity/timeliness/accuracy/consistency) + severity-weighted scoring (0-100 / A-D) + **Word report export**; full governance stack |
| **Data Development** | Offline / Streaming / Script / Function / Task Log | SQL(JDBC)/Python/Java/Shell/Scala script execution; unified task-log aggregation |
| **Data Asset** | Catalog / Mount / Approval / Lifecycle | Approval state machine (draft→pending→approved/rejected); safe online/offline/unbind with zero-cascade |
| **Ops Center** | Interactive Analysis / Overview / Task Center / Task Stats / Resource Monitor / Cluster / Executor / Connector | Ops dashboards, cluster liveness + adapter availability checks |
| **Data Security** | Security Standard / Masking / Key / Alert / Allow-Deny List / Sensitive Data / Permission | Keys encrypted via CryptoUtil; masking registration; table-level permissions |
| **Data Serving** | Service / Data Open | Wrap SQL as REST; **asset-driven "Data Open"**: appkey auth + /openapi endpoints + in-memory rate/limit/quota |
| **Data Marketplace** | Dataset / Resource Overview | Consumer portal: browse approved assets → full-text/category/tag search → subscribe → approve → **auto-grant open appkey on approval** |
| **System** | User / Org / Tenant / Role / Menu / Log | Three-role RBAC (SYS/SEC/AUDIT_ADMIN) + audit; MyBatis-Plus |

> Plus a **Data Map** (search + category tree + 3 asset types + lineage) and a **Data Overview** home portal.

## 4. Tech Stack

**Backend** (`datalake-service`)
- Spring Boot 3.2.4 · Java 17 · JdbcTemplate direct queries (governance/dev/asset/serving/security/ops/access/marketplace) + **MyBatis-Plus 3.5.5** (system domain)
- HikariCP dynamic datasources · HMAC-SHA256 stateless token auth · captcha · Apache POI 5.2.5 (Word reports) · Spring Scheduling

**Frontend** (`datalake-web`)
- Vue 3.4 + Element Plus 2.6 + ECharts 5.5 (vue-echarts) + vue-i18n 9 + vue-router 4 + axios + TypeScript + Vite 5
- Light (DIFY) / dark (neon) dual theme

**Big Data** (`docker/`)
- StarRocks 3.3.10 (real-time warehouse, MySQL protocol) / Kafka 3.7.0 (KRaft) / Flink 1.18 / MinIO / Apache Hop 2.10

**Deploy**: Docker Compose, single-node one-click

## 5. Quick Start

```bash
# Option A: one-click (recommended) — brings up the big-data stack + backend(:8090) + frontend(:5173), idempotent
bash start.sh

# Option B: step by step
cd docker && bash bring-up.sh                 # ① big-data components + warehouse layers
cd ../datalake-service && mvn -DskipTests package && java -jar target/datalake-service.jar  # ② backend
cd ../datalake-web && npm install && npm run dev                                            # ③ frontend
```

Open **http://localhost:5173** → log in with **admin / admin123**

> Stop: `bash stop.sh` (`bash stop.sh --all` also stops the big-data stack)

## 6. Ports

| Service | Port |
|---|---|
| Frontend (Vite) | 5173 |
| Backend API | 8090 |
| StarRocks FE (MySQL protocol / Web) | 9030 / 8030 |
| Kafka (host / internal) | 9094 / 9092 |
| Flink Web | 8081 |
| MinIO Console | 9001 |
| Hop Server | 8082 |

## 7. Project Structure

```
kaidata/
├── datalake-service/   # Backend: Spring Boot REST (9 domains)
├── datalake-web/       # Frontend: Vue3 + Element Plus portal
├── docker/             # Big-data orchestration (compose + bring-up.sh + DDL)
├── docs/               # Documentation
├── start.sh / stop.sh  # One-click start / stop
└── logo.svg
```

## 8. Highlights

- 🔌 **24 datasource adapters**: real open-source drivers (PG/ClickHouse/SQLServer/Oracle/TDengine/MySQL…) + domestic-DB (Dameng/Kingbase/GBase) SPI placeholder framework
- 🔄 **Real streaming ingestion**: JDBC polling → Kafka → StarRocks ROUTINE LOAD; OFFSET_BEGINNING per-partition consumption fixes "zero rows landed"; primary-key dedup
- 📊 **Quality scoring system**: 6 dimensions + severity weighting → overall score + grade (BLOCKER failure caps at D); one-click **Word(.docx) report export** + in-page radar/dashboard
- 🔍 **Data profiling**: schema snapshots + version diff + first-run auto-modeling into target layer
- 🌐 **Data Open**: asset-driven appkey auth + /openapi endpoints + in-memory rate/limit/quota
- 🛒 **Marketplace subscription**: browse → search → subscribe → approve → auto-grant open appkey
- 🔐 **Three-role separation**: SYS / SEC / AUDIT administrator RBAC + full operation audit
- 🌏 **Bilingual + dual theme**: vue-i18n (zh/en), unified light / dark

## 9. Default Account

| User | Password | Role |
|---|---|---|
| admin | admin123 | Super administrator (SYS + SEC + AUDIT) |

> ⚠️ Change the default password and enable HTTPS for production deployments.

## License

MIT
