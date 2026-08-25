#!/usr/bin/env bash
# ============================================================
# AgentForge 一键启动脚本（P14 部署交付物）
#
# 五端拉起：
#   1. MySQL   127.0.0.1:3308（root 空密码，本地开发形态）
#   2. Redis   127.0.0.1:6379（.local-redis）
#   3. Python  Agent 引擎  127.0.0.1:8000（uvicorn）
#   4. Java    af-bootstrap  127.0.0.1:8090（fat-jar）
#   5. 前端    Vite dev     127.0.0.1:5173（proxy /api → 8090）
#
# 幂等：已运行的端口自动跳过；日志统一落 logs/ 目录。
# 用法：bash scripts/start-all.sh [--rebuild-java] [--rebuild-frontend] [--restart-java]
# ============================================================
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS_DIR="$ROOT/logs"
mkdir -p "$LOGS_DIR"

PYTHON_BIN="/Users/jack.yang/.workbuddy/binaries/python/envs/agentforge/bin/python"
NODE_BIN="/Users/jack.yang/.workbuddy/binaries/node/versions/22.22.2/bin"
BIND_HOST="${AGENTFORGE_BIND_HOST:-0.0.0.0}"
LOCAL_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
[ -n "$LOCAL_IP" ] || LOCAL_IP="127.0.0.1"
MYSQL_BIN="/usr/local/mysql-9.7.0-macos15-arm64/bin"
MYSQL_DATADIR="$ROOT/.local-mysql/data"
MYSQL_SOCK="$ROOT/.local-mysql/mysql.sock"
MYSQL_PID="$ROOT/.local-mysql/mysql.pid"
MYSQL_URL="jdbc:mysql://127.0.0.1:3308/agentforge?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-60}"
critical_failed=0
restart_java=0
for arg in "$@"; do
  case "$arg" in
    --restart-java) restart_java=1 ;;
    --rebuild-java) restart_java=1 ;;
  esac
done

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && { exec 3>&-; return 0; } || return 1; }

http_ready() {
  [ "$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' "$1" 2>/dev/null)" = "200" ]
}

wait_for_http() {
  local url="$1"
  local pid="${2:-}"
  local i
  for i in $(seq 1 "$READY_TIMEOUT_SECONDS"); do
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      return 2
    fi
    if http_ready "$url"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

wait_for_tcp() {
  local port="$1"
  local pid="${2:-}"
  local i
  for i in $(seq 1 "$READY_TIMEOUT_SECONDS"); do
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      return 2
    fi
    if port_open "$port"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

report_failure() {
  local service="$1"
  local log_file="$2"
  critical_failed=1
  echo "[x] $service 未就绪，详见 $log_file"
}

echo "=============================================="
echo " AgentForge 五端启动 · $(date '+%H:%M:%S')"
echo "=============================================="

# 1) MySQL
if port_open 3308; then
  echo "[✓] MySQL 3308 已在运行"
elif [ -d "$MYSQL_DATADIR/agentforge" ]; then
  echo "[↑] 启动 MySQL 3308（datadir: .local-mysql/data）..."
  if [ -f "$MYSQL_PID" ] && kill -0 "$(cat "$MYSQL_PID")" 2>/dev/null; then
    echo "[!] 发现残留 PID，清理"; rm -f "$MYSQL_PID" "$MYSQL_SOCK"
  fi
  nohup "$MYSQL_BIN/mysqld" \
    --datadir="$MYSQL_DATADIR" \
    --port=3308 \
    --socket="$MYSQL_SOCK" \
    --pid-file="$MYSQL_PID" \
    --user="$(id -un)" \
    --bind-address=127.0.0.1 \
    >"$LOGS_DIR/mysql.log" 2>&1 < /dev/null &
  mysql_pid=$!
  if wait_for_tcp 3308 "$mysql_pid"; then
    echo "[✓] MySQL 已启动"
  else
    report_failure "MySQL 3308" "$LOGS_DIR/mysql.log"
  fi
else
  report_failure "MySQL 3308（未初始化）" "$LOGS_DIR/mysql.log"
fi

# 2) Redis
if port_open 6379; then
  echo "[✓] Redis 6379 已在运行"
else
  if [ -x "$ROOT/.local-redis/redis-7.4.0/src/redis-server" ]; then
    echo "[↑] 启动 Redis 6379..."
    nohup "$ROOT/.local-redis/redis-7.4.0/src/redis-server" "$ROOT/.local-redis/redis-7.4.0/redis.conf" \
      >"$LOGS_DIR/redis.log" 2>&1 < /dev/null &
    redis_pid=$!
    if wait_for_tcp 6379 "$redis_pid"; then
      echo "[✓] Redis 已启动"
    else
      report_failure "Redis 6379" "$LOGS_DIR/redis.log"
    fi
  else
    report_failure "Redis 6379（未安装）" "$LOGS_DIR/redis.log"
  fi
fi

# 3) Python Agent 引擎
if port_open 8000; then
  if http_ready "http://127.0.0.1:8000/health"; then
    echo "[✓] Agent 引擎 8000 已在运行"
  else
    report_failure "Agent 引擎 8000（健康检查失败）" "$LOGS_DIR/agent-engine.log"
  fi
else
  echo "[↑] 启动 Agent 引擎（uvicorn :8000）..."
  cd "$ROOT/agent-engine" || exit 1
  # 本地 Ollama 等模型走 127.0.0.1，需显式绕过系统代理
  NO_PROXY="${NO_PROXY:-127.0.0.1,localhost}" \
  nohup "$PYTHON_BIN" -m uvicorn app.main:app --host "$BIND_HOST" --port 8000 \
    >"$LOGS_DIR/agent-engine.log" 2>&1 < /dev/null &
  agent_pid=$!
  if wait_for_http "http://127.0.0.1:8000/health" "$agent_pid"; then
    echo "[✓] Agent 引擎已就绪（http://127.0.0.1:8000/health）"
  else
    report_failure "Agent 引擎 8000" "$LOGS_DIR/agent-engine.log"
  fi
  cd "$ROOT"
fi

# 4) Java af-bootstrap
if [ "$restart_java" -eq 1 ] && port_open 8090; then
  echo "[↻] 按参数重启 Java 8090..."
  java_pids="$(lsof -ti tcp:8090 2>/dev/null || true)"
  if [ -n "$java_pids" ]; then
    kill $java_pids 2>/dev/null || true
    for i in $(seq 1 15); do
      port_open 8090 || break
      sleep 1
    done
    if port_open 8090; then kill -9 $java_pids 2>/dev/null || true; fi
  fi
fi
if port_open 8090; then
  if http_ready "http://127.0.0.1:8090/health"; then
    echo "[✓] Java 8090 已在运行"
  else
    report_failure "Java 8090（健康检查失败）" "$LOGS_DIR/java.log"
  fi
else
  if [ ! -f "$ROOT/backend/af-bootstrap/target/af-bootstrap-1.0.0-SNAPSHOT.jar" ]; then
    report_failure "Java 8090（fat-jar 缺失）" "$LOGS_DIR/java.log"
  else
    echo "[↑] 启动 Java af-bootstrap（:8090）..."
    cd "$ROOT/backend/af-bootstrap"
    unset SERVER__PORT
    AGENT_ENGINE_PROVIDER=http \
    MYSQL_URL="$MYSQL_URL" \
    MYSQL_PASSWORD="" \
    SKILL_REPO_DIR="$ROOT/backend/skill-repo" \
    nohup java -jar target/af-bootstrap-1.0.0-SNAPSHOT.jar --server.port=8090 >"$LOGS_DIR/java.log" 2>&1 < /dev/null &
    java_pid=$!
    if wait_for_http "http://127.0.0.1:8090/health" "$java_pid"; then
      echo "[✓] Java 已就绪（http://127.0.0.1:8090/health）"
    else
      report_failure "Java 8090" "$LOGS_DIR/java.log"
    fi
    cd "$ROOT"
  fi
fi

# 5) 前端 Vite dev
if port_open 5173; then
  if http_ready "http://127.0.0.1:5173/"; then
    echo "[✓] 前端 5173 已在运行"
  else
    report_failure "前端 5173（健康检查失败）" "$LOGS_DIR/frontend.log"
  fi
else
  echo "[↑] 启动前端 dev（:5173）..."
  cd "$ROOT/frontend"
  nohup "$NODE_BIN/npx" vite --host "$BIND_HOST" --port 5173 >"$LOGS_DIR/frontend.log" 2>&1 < /dev/null &
  frontend_pid=$!
  if wait_for_http "http://127.0.0.1:5173/" "$frontend_pid"; then
    echo "[✓] 前端已就绪（http://127.0.0.1:5173/）"
  else
    report_failure "前端 5173" "$LOGS_DIR/frontend.log"
  fi
  cd "$ROOT"
fi

if [ "$critical_failed" -ne 0 ]; then
  echo "[x] 核心服务未全部就绪，启动失败。请检查上面列出的日志。"
  exit 1
fi

echo "=============================================="
echo " 全部就绪：前端 http://$LOCAL_IP:5173（本机也可用 http://127.0.0.1:5173）"
echo "=============================================="

# 保活：以 sleep 占据启动任务进程，避免 nohup 子进程在脚本退出后被回收。
# 停止全部服务请结束本任务或手动 kill 对应端口进程。
exec sleep 31536000
