#!/usr/bin/env bash
# ============================================================
# MinIO 湖存储初始化（建 bucket）
# minio/minio 镜像不含 mc 客户端，故通过 minio/mc 临时容器执行。
#
# 用法：
#   docker run --rm --network=pharma-bigdata_default \
#     -e MC_HOST_pharma=http://minioadmin:minioadmin@minio:9000 \
#     minio/mc:latest sh -c "$(cat docker/init/minio-init.sh)"
#
# 说明：-e MC_HOST_pharma=... 配置名为 pharma 的别名（指向 minio:9000），
#       脚本内 pharma/<bucket> 即用该别名。
# ============================================================
set -e

# 湖存储主 bucket（Iceberg 表格式存放目录）
mc mb -p pharma/pharma-lake    || echo "[minio-init] pharma-lake 已存在"
# 归档 bucket（预留）
mc mb -p pharma/pharma-archive || echo "[minio-init] pharma-archive 已存在"

# 列出结果
echo "[minio-init] 当前 bucket："
mc ls pharma
