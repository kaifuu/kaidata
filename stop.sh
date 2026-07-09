#!/usr/bin/env bash
# ============================================================
# 一键停止 · 数据中台 kaidata
# ------------------------------------------------------------
# 默认只停应用层（前端 5173 + 后端 8090），保留大数据栈
# （StarRocks 等启动慢，通常希望保留复用）。
#
# 用法：
#   bash stop.sh          仅停 前端 + 后端
#   bash stop.sh --all    连 大数据栈(MinIO/Kafka/Flink/StarRocks) 一起停
# ============================================================
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
# 注意：此处不能设 MSYS_NO_PATHCONV=1。kill_port/kill_pidfile 用 taskkill //PID，
# 该写法依赖 MSYS 默认路径转换把 //PID 折成 /PID；一旦关闭转换，//PID 原样传入
# taskkill.exe 会静默失败（表现为“已停止”但进程未杀、端口仍占用，导致紧随其后的
# start.sh 误判端口占用而跳过 → 后端不会重新构建，改的 Java 不生效）。

LOG_DIR="$ROOT/logs"
STOP_ALL=0
[ "${1:-}" = "--all" ] && STOP_ALL=1

# 按端口杀掉监听该端口的进程（Windows taskkill；Git Bash 下 // 防路径转换）
kill_port() {
  local port=$1
  # netstat -ano 第 5 列为 PID；去重
  local pids
  pids=$(netstat -ano 2>/dev/null | grep -E "LISTENING" | grep -E ":$port\b" | awk '{print $5}' | sort -u)
  for pid in $pids; do
    [ -n "$pid" ] && [ "$pid" != "0" ] && taskkill //PID "$pid" //F >/dev/null 2>&1 && echo "    · 杀掉 PID $pid (端口 $port)"
  done
}

# 按 pid 文件兜底杀（端口已释放但父进程残留时）
kill_pidfile() {
  local f=$1
  [ -f "$f" ] || return 0
  local pid
  pid=$(cat "$f" 2>/dev/null)
  if [ -n "$pid" ] && [ "$pid" != "0" ]; then
    taskkill //PID "$pid" //F //T >/dev/null 2>&1 && echo "    · 杀掉 PID $pid (来自 $(basename "$f"))" || true
  fi
  rm -f "$f"
}

echo "================ 一键停止 ================"

echo "[1/2] 前端(5173)..."
kill_port 5173
kill_pidfile "$LOG_DIR/frontend.pid"
echo "      已停止"

echo "[2/2] 后端(8090)..."
kill_port 8090
kill_pidfile "$LOG_DIR/backend.pid"
echo "      已停止"

if [ "$STOP_ALL" = "1" ]; then
  echo "[+] 大数据栈(--all)..."
  (cd "$ROOT/docker" && docker compose down)
  echo "    已停止（数据卷保留；彻底重置用 docker compose down -v）"
fi

echo "=========================================="
echo "停止完成。"
