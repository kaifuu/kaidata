-- ============================================================
-- 数据中台 - 数仓分层库 DDL
-- 分层：ODS(原始) / DWD(明细) / DWS(汇总) / ADS(应用) / DIM(维度)
-- 复制数=1（单机演示）；生产应=3
--
-- 各层具体业务表由「数据仓库分层 / 离线接入 / 数据开发」等中台模块按需创建，
-- 这里仅初始化 5 个分层库（供中台运行期写入）。
-- ============================================================

CREATE DATABASE IF NOT EXISTS ods;
CREATE DATABASE IF NOT EXISTS dwd;
CREATE DATABASE IF NOT EXISTS dws;
CREATE DATABASE IF NOT EXISTS ads;
CREATE DATABASE IF NOT EXISTS dim;
