#!/usr/bin/env bash
# ============================================================
# 下载 Flink 连接器 jar（flink-lib/）
# ------------------------------------------------------------
# 离线 FlinkDag 作业的 table / kafka 源汇依赖这些 jar，缺任一个都会在
# CREATE TABLE 时报 "Cannot discover a connector using option: 'connector'='jdbc'"。
# jar 被 .gitignore 的 *.jar 排除，故不进版本库，由本脚本按需拉取。
#
# 用法： bash docker/fetch-flink-libs.sh     （幂等，已存在则跳过）
# ============================================================
set -e
cd "$(dirname "$0")"
mkdir -p flink-lib

BASE=https://repo1.maven.org/maven2/org/apache/flink
fetch() {  # fetch <jar 名> <maven 相对路径>
  local f="flink-lib/$1" path="$2"
  if [ -s "$f" ]; then echo "    · $1 已存在，跳过"; return; fi
  echo "    · 下载 $1 ..."
  curl -sSL --max-time 300 --retry 2 -o "$f" "$BASE/$path/$1"
  [ -s "$f" ] || { echo "    × $1 下载失败（网络受限？可手工放入 docker/flink-lib/）"; rm -f "$f"; exit 1; }
}

echo "==> Flink 连接器 jar"
fetch flink-connector-jdbc-3.1.2-1.18.jar  "flink-connector-jdbc/3.1.2-1.18"
fetch flink-connector-kafka-3.0.2-1.18.jar "flink-connector-kafka/3.0.2-1.18"

# JDBC 驱动复用 Hop 那份，避免重复入库
if [ ! -s flink-lib/mysql-connector-j-8.3.0.jar ]; then
  echo "    · 复用 hop-jdbc 的 MySQL 驱动"
  cp hop-jdbc/mysql-connector-j-8.3.0.jar flink-lib/
fi

echo "==> 就绪："; ls -1 flink-lib/
