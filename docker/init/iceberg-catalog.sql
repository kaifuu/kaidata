-- ============================================================
-- Iceberg External Catalog（StarRocks 查湖表入口）
-- ------------------------------------------------------------
-- 湖表由「离线接入 Iceberg 目标」经 REST Catalog 写入 MinIO；
-- StarRocks 侧只挂目录（只读加速），查询用三段名：
--   SELECT * FROM iceberg_catalog.<namespace>.<table>
-- 重复执行会报 "already exists"，bring-up 已做容错忽略。
--
-- 属性分两组（缺一不可，实测 3.3.10）：
--  · aws.s3.*  —— FE→BE 的数据文件读取（AwsCloudConfigurationProvider，
--    validate 要求 AK/SK 在场；缺 endpoint/enable_ssl=false 时 BE 会
--    以 https 打 MinIO 的 http 端口 → curlCode 28 超时，
--    缺 AK/SK 时退回默认 AWS 客户端打真 AWS → 超时）
--  · s3.*      —— FE 侧 iceberg-java RESTCatalog 的 S3FileIO（读
--    metadata.json；server 的 /v1/config overrides 为空不会代下发）
-- ============================================================

CREATE EXTERNAL CATALOG iceberg_catalog
PROPERTIES (
  "type" = "iceberg",
  "iceberg.catalog.type" = "rest",
  "uri" = "http://iceberg-rest:8181",
  "aws.s3.region" = "us-east-1",
  "aws.s3.access_key" = "minioadmin",
  "aws.s3.secret_key" = "minioadmin",
  "aws.s3.enable_path_style_access" = "true",
  "aws.s3.enable_ssl" = "false",
  "aws.s3.endpoint" = "http://minio:9000",
  "s3.endpoint" = "http://minio:9000",
  "s3.access-key-id" = "minioadmin",
  "s3.secret-access-key" = "minioadmin",
  "s3.path-style-access" = "true"
);
