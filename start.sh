#!/usr/bin/env bash
# ============================================================
# 一键启动 · 数据中台 kaidata
# ------------------------------------------------------------
# 依次拉起：大数据栈(docker) → 后端(8090) → 前端(5173)
# 全程幂等：已运行的服务自动跳过，重复执行无副作用。
# 后端 jar 仅在源码变更时才重新构建。
#
# 用法：  bash start.sh
# 日志：  logs/backend.log   logs/frontend.log
# 停服：  bash stop.sh       （或 bash stop.sh --all 连大数据栈一起停）
# ============================================================
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1

PORT_API=8090
PORT_WEB=5173
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

# ---- 工具函数 ----
# 端口是否被监听（Windows netstat，Git Bash 可用）
port_up() { netstat -ano 2>/dev/null | grep -E "LISTENING" | grep -E ":$1\b" >/dev/null 2>&1; }

# 轮询等待端口就绪：wait_port <port> <name> <max_sec>
wait_port() {
  local port=$1 name=$2 max=$3 i=0
  while ! port_up "$port"; do
    i=$((i + 1))
    if [ $i -gt "$max" ]; then
      echo "    × $name 在 ${max}s 内未就绪，详见日志"
      return 1
    fi
    sleep 1
  done
  echo "    ✓ $name 就绪（${i}s）"
}

echo "================ 一键启动 ================"

# ------------------------------------------------------------
# [1/3] 大数据栈：MinIO / Kafka / Flink / StarRocks
# ------------------------------------------------------------
echo "[1/3] 大数据栈..."
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q pharma-starrocks; then
  echo "    · StarRocks 容器已在运行，跳过"
else
  echo "    · 启动大数据栈 + 建数仓分层库（首次约 1~3 分钟）..."
  bash "$ROOT/docker/bring-up.sh"
fi

# ------------------------------------------------------------
# [2/3] 后端 datalake-service（8090）
# ------------------------------------------------------------
echo "[2/3] 后端服务..."
if port_up "$PORT_API"; then
  echo "    · 端口 $PORT_API 已被占用，跳过"
else
  JAR="$ROOT/datalake-service/target/datalake-service.jar"
  # jar 不存在 或 src/pom 比 jar 新 → 重新构建
  if [ ! -f "$JAR" ] || find "$ROOT/datalake-service/src" "$ROOT/datalake-service/pom.xml" -newer "$JAR" 2>/dev/null | grep -q .; then
    echo "    · 源码有变更或 jar 缺失，重新构建（mvn -DskipTests package）..."
    (cd "$ROOT/datalake-service" && mvn -q -DskipTests package)
  else
    echo "    · jar 最新，跳过构建"
  fi
  echo "    · 启动 java -jar ..."
  # POSIX 路径 /f/... Windows 原生 java.exe 无法识别，转成正斜杠 Windows 路径
  JAR_WIN=$(cygpath -m "$JAR" 2>/dev/null || echo "$JAR")
  nohup java -jar "$JAR_WIN" > "$LOG_DIR/backend.log" 2>&1 &
  echo $! > "$LOG_DIR/backend.pid"
  wait_port "$PORT_API" "后端(8090)" 60 || { tail -25 "$LOG_DIR/backend.log" || true; exit 1; }
fi

# ------------------------------------------------------------
# [3/3] 前端 datalake-web（5173）
# ------------------------------------------------------------
echo "[3/3] 前端服务..."
if port_up "$PORT_WEB"; then
  echo "    · 端口 $PORT_WEB 已被占用，跳过"
else
  if [ ! -d "$ROOT/datalake-web/node_modules" ]; then
    echo "    · 缺 node_modules，执行 npm install ..."
    (cd "$ROOT/datalake-web" && npm install)
  fi
  echo "    · 启动 npm run dev ..."
  (cd "$ROOT/datalake-web" && nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 & echo $! > "$LOG_DIR/frontend.pid")
  wait_port "$PORT_WEB" "前端(5173)" 30 || { tail -25 "$LOG_DIR/frontend.log" || true; exit 1; }
fi

# ------------------------------------------------------------
cat <<EOF

================ 启动完成 ================
  前端 : http://localhost:5173    登录 admin / admin123
  后端 : http://localhost:8090
  日志 : logs/backend.log   logs/frontend.log
  停服 : bash stop.sh   （bash stop.sh --all 连大数据栈一起停）
==========================================
EOF
