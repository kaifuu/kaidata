-- ============================================================
-- 大数据平台 - Doris 数仓 DDL
-- 分层：ODS(原始) / DWD(明细) / DWS(汇总) / ADS(应用) / DIM(维度)
-- 复制数=1（单机演示）；生产应=3
-- 由 Flink(实时入ODS) + Spark(批加工到DWD/DWS/ADS) 共同填充
-- ============================================================

CREATE DATABASE IF NOT EXISTS ods;
CREATE DATABASE IF NOT EXISTS dwd;
CREATE DATABASE IF NOT EXISTS dws;
CREATE DATABASE IF NOT EXISTS ads;

-- ---------- ODS：原始层（Flink 实时写入） ----------
USE ods;

-- 环境监测明细（Flink 消费 Kafka pharma-env 实时写入）
CREATE TABLE IF NOT EXISTS ods_env_monitor (
    device_id  VARCHAR(32)  COMMENT '设备ID',
    metric     VARCHAR(32)  COMMENT '指标 temp/humidity/diffPressure',
    value      DOUBLE       COMMENT '实测值',
    min_val    DOUBLE       COMMENT '合规下限',
    max_val    DOUBLE       COMMENT '合规上限',
    ts         DATETIME     COMMENT '时间戳'
) DUPLICATE KEY(device_id, metric)
  DISTRIBUTED BY HASH(device_id) BUCKETS 3
  PROPERTIES("replication_num"="1");

-- 环境越限告警（Flink 实时判定写入）
CREATE TABLE IF NOT EXISTS ods_alarm (
    device_id  VARCHAR(32),
    metric     VARCHAR(32),
    value      DOUBLE,
    min_val    DOUBLE,
    max_val    DOUBLE,
    severity   VARCHAR(16)  COMMENT 'WARN/CRITICAL',
    ts         DATETIME
) DUPLICATE KEY(device_id, metric)
  DISTRIBUTED BY HASH(device_id) BUCKETS 3
  PROPERTIES("replication_num"="1");

-- 批次完工事件（Flink 入湖或直写）
CREATE TABLE IF NOT EXISTS ods_batch (
    batch_no       VARCHAR(64) COMMENT '批次号',
    material_code  VARCHAR(64) COMMENT '物料编码',
    quantity       INT         COMMENT '产量',
    status         VARCHAR(16) COMMENT 'COMPLETED/ABNORMAL',
    ts             DATETIME
) DUPLICATE KEY(batch_no)
  DISTRIBUTED BY HASH(batch_no) BUCKETS 3
  PROPERTIES("replication_num"="1");

-- 检验结果
CREATE TABLE IF NOT EXISTS ods_qc (
    batch_no       VARCHAR(64),
    material_code  VARCHAR(64),
    test_item      VARCHAR(64) COMMENT '检验项',
    result         DOUBLE      COMMENT '实测',
    spec           DOUBLE      COMMENT '标准',
    pass           BOOLEAN     COMMENT '是否合格',
    ts             DATETIME
) DUPLICATE KEY(batch_no)
  DISTRIBUTED BY HASH(batch_no) BUCKETS 3
  PROPERTIES("replication_num"="1");

-- ---------- ADS：应用层（Spark 批加工写入，供批次追溯/看板查询） ----------
USE ads;

-- 批次质量全景宽表（Spark 关联 ods_batch + ods_qc 加工）
CREATE TABLE IF NOT EXISTS ads_batch_quality (
    batch_no       VARCHAR(64) COMMENT '批次号',
    material_code  VARCHAR(64) COMMENT '物料',
    quantity       INT         COMMENT '产量',
    batch_status   VARCHAR(16) COMMENT '批次状态',
    qc_pass        BOOLEAN     COMMENT '检验是否合格',
    qc_result      DOUBLE      COMMENT '检验结果',
    qc_spec        DOUBLE      COMMENT '检验标准',
    ts             DATETIME
) DUPLICATE KEY(batch_no)
  DISTRIBUTED BY HASH(batch_no) BUCKETS 3
  PROPERTIES("replication_num"="1");

-- 环境合规汇总（Spark 按设备/指标汇总达标率，供看板）
CREATE TABLE IF NOT EXISTS ads_env_compliance (
    device_id   VARCHAR(32),
    metric      VARCHAR(32),
    total_cnt   BIGINT     COMMENT '样本数',
    ok_cnt      BIGINT     COMMENT '合规数',
    compliance_rate DOUBLE COMMENT '达标率%'
) DUPLICATE KEY(device_id, metric)
  DISTRIBUTED BY HASH(device_id) BUCKETS 3
  PROPERTIES("replication_num"="1");

-- 生产效能汇总（Spark 按物料汇总，供生产效能看板）
CREATE TABLE IF NOT EXISTS ads_production_efficiency (
    material_code    VARCHAR(64) COMMENT '物料编码',
    material_name    VARCHAR(128) COMMENT '物料名称',
    total_batches    BIGINT     COMMENT '总批次数',
    abnormal_batches BIGINT     COMMENT '异常批次数',
    total_quantity   BIGINT     COMMENT '总产量',
    avg_qc_result    DOUBLE     COMMENT '平均检验值',
    pass_rate        DOUBLE     COMMENT '检验合格率%'
) DUPLICATE KEY(material_code)
  DISTRIBUTED BY HASH(material_code) BUCKETS 1
  PROPERTIES("replication_num"="1");

-- ---------- DIM：维度层（种子数据，Spark 关联取物料名称） ----------
CREATE DATABASE IF NOT EXISTS dim;
USE dim;

CREATE TABLE IF NOT EXISTS dim_material (
    material_code  VARCHAR(64)  COMMENT '物料编码',
    material_name  VARCHAR(128) COMMENT '物料名称',
    spec           VARCHAR(64)  COMMENT '规格',
    category       VARCHAR(32)  COMMENT '剂型分类'
) UNIQUE KEY(material_code)
  DISTRIBUTED BY HASH(material_code) BUCKETS 1
  PROPERTIES("replication_num"="1");

-- 模拟器使用的三个成品物料维度（覆盖 FG-04001/2/3）
INSERT INTO dim_material (material_code, material_name, spec, category) VALUES
  ('FG-04001', '阿莫西林胶囊', '0.25g*30粒', '胶囊剂'),
  ('FG-04002', '头孢氨苄胶囊', '0.25g*30粒', '胶囊剂'),
  ('FG-04003', '布洛芬胶囊',   '0.2g*24粒',  '胶囊剂');

-- ---------- DWD：明细层（Spark 关联清洗后的批次×检验明细宽表） ----------
USE dwd;

CREATE TABLE IF NOT EXISTS dwd_batch_qc (
    batch_no        VARCHAR(64)  COMMENT '批次号',
    material_code   VARCHAR(64)  COMMENT '物料编码',
    material_name   VARCHAR(128) COMMENT '物料名称(关联dim)',
    quantity        INT          COMMENT '产量',
    batch_status    VARCHAR(16)  COMMENT '批次状态',
    test_item       VARCHAR(64)  COMMENT '检验项',
    result          DOUBLE       COMMENT '检验实测',
    spec            DOUBLE       COMMENT '检验标准',
    qc_pass         BOOLEAN      COMMENT '是否合格',
    ts              DATETIME     COMMENT '完工时间'
) DUPLICATE KEY(batch_no)
  DISTRIBUTED BY HASH(batch_no) BUCKETS 3
  PROPERTIES("replication_num"="1");

-- ---------- DWS：汇总层（Spark 按物料汇总质量/产能） ----------
USE dws;

CREATE TABLE IF NOT EXISTS dws_material_quality (
    material_code    VARCHAR(64)  COMMENT '物料编码',
    material_name    VARCHAR(128) COMMENT '物料名称',
    total_batches    BIGINT       COMMENT '总批次数',
    abnormal_batches BIGINT       COMMENT '异常批次数',
    total_quantity   BIGINT       COMMENT '总产量',
    avg_qc_result    DOUBLE       COMMENT '平均检验值',
    pass_rate        DOUBLE       COMMENT '检验合格率%'
) DUPLICATE KEY(material_code)
  DISTRIBUTED BY HASH(material_code) BUCKETS 1
  PROPERTIES("replication_num"="1");
