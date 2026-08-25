#!/usr/bin/env bash
# AgentForge 30-minute stability soak test.
# Read-only checks plus temporary conversation creation/deletion; no production data mutation.
set -u -o pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${AGENTFORGE_BASE:-http://127.0.0.1:8090}"
PY="/Users/jack.yang/.workbuddy/binaries/python/versions/3.13.12/bin/python3"
DURATION="${SOAK_DURATION_SECONDS:-1800}"
INTERVAL="${SOAK_INTERVAL_SECONDS:-20}"
OUT="${SOAK_LOG:-$ROOT/logs/soak-30m-$(date '+%Y%m%d-%H%M%S').log}"
mkdir -p "$(dirname "$OUT")"

CURL=(/usr/bin/curl -sS --noproxy '*' --max-time 15)
PASS=0
FAIL=0
ITER=0
START=$(date +%s)

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$OUT"; }
request() { "${CURL[@]}" "$@"; }
json_value() {
  "$PY" -c 'import json,sys
try:
    data=json.load(sys.stdin)
except Exception:
    print("")
    raise SystemExit
path=sys.argv[1].split(".")
cur=data
for key in path:
    if isinstance(cur,dict): cur=cur.get(key)
    else: cur=None
print("" if cur is None else cur)' "$1"
}
pass_check() { PASS=$((PASS+1)); log "PASS $*"; }
fail_check() { FAIL=$((FAIL+1)); log "FAIL $*"; }

login() {
  request -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d '{"identifier":"admin","password":"Admin@2026"}'
}

stream_check() {
  local token="$1" content="$2" model="$3" label="$4" conversation_id="${5:-}" tmp body
  tmp=$(mktemp)
  if [ -n "$conversation_id" ]; then
    body=$(printf '{"content":%s,"modelConfigId":%s,"conversationId":%s}' \
      "$(printf '%s' "$content" | "$PY" -c 'import json,sys;print(json.dumps(sys.stdin.read()))')" "$model" \
      "$(printf '%s' "$conversation_id" | "$PY" -c 'import json,sys;print(json.dumps(sys.stdin.read()))')")
  else
    body=$(printf '{"content":%s,"modelConfigId":%s}' \
      "$(printf '%s' "$content" | "$PY" -c 'import json,sys;print(json.dumps(sys.stdin.read()))')" "$model")
  fi
  request --max-time 30 -N -X POST "$BASE/api/chat/stream" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $token" \
    -H "X-Trace-Id: soak-$ITER-$label" -d "$body" >"$tmp" 2>/dev/null || true
  if grep -aE '^event: ?message_start$' "$tmp" >/dev/null \
    && grep -aE '^event: ?content_delta$' "$tmp" >/dev/null \
    && grep -aE '^event: ?message_done$' "$tmp" >/dev/null; then
    if grep -aE 'tool_calls|"tool_code"' "$tmp" >/dev/null; then
      fail_check "$label raw tool protocol leaked"
    else
      pass_check "$label SSE complete"
    fi
  else
    fail_check "$label SSE incomplete"
  fi
  cat "$tmp" >> "$OUT"
  rm -f "$tmp"
}

log "START duration=${DURATION}s interval=${INTERVAL}s base=$BASE"
while [ $(( $(date +%s) - START )) -lt "$DURATION" ]; do
  ITER=$((ITER+1))
  log "ITERATION $ITER"

  health=$(request "$BASE/health" || true)
  if printf '%s' "$health" | "$PY" -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code") == 0 and d.get("data",{}).get("status") == "UP"' >/dev/null 2>&1; then
    pass_check "Java health"
  else
    fail_check "Java health: $health"
  fi

  engine=$(request http://127.0.0.1:8000/health || true)
  if printf '%s' "$engine" | "$PY" -c 'import json,sys; d=json.load(sys.stdin); assert d.get("status") in ("ok","up")' >/dev/null 2>&1; then
    pass_check "Engine health"
  else
    fail_check "Engine health: $engine"
  fi

  login_resp=$(login || true)
  token=$(printf '%s' "$login_resp" | json_value 'data.accessToken')
  if [ "${#token}" -gt 20 ]; then
    pass_check "login"
  else
    fail_check "login"
    sleep "$INTERVAL"
    continue
  fi

  models=$(request "$BASE/api/models" -H "Authorization: Bearer $token" -H 'X-Tenant-Id: 1' || true)
  default_provider=$(printf '%s' "$models" | "$PY" -c 'import json,sys
try:
 d=json.load(sys.stdin).get("data",[])
 m=next((x for x in d if x.get("isDefault")==1),{})
 print(m.get("provider", ""))
except Exception: print("")')
  if [ -n "$default_provider" ]; then pass_check "model list default=$default_provider"; else fail_check "model list"; fi

  conv_resp=$(request -X POST "$BASE/api/conversations" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $token" -d '{"title":"30分钟稳定性巡检"}' || true)
  cid=$(printf '%s' "$conv_resp" | json_value 'data.id')
  if [ -n "$cid" ]; then
    pass_check "conversation create"
    stream_check "$token" "你好" 1 "hello" "$cid"
    stream_check "$token" "请介绍 AgentForge 平台" 1 "intro" "$cid"
    stream_check "$token" "请直接回答：1+1等于几？" 1 "math" "$cid"
    stream_check "$token" "帮我计算 12 * (3 + 4)" 1 "calculator" "$cid"
    del_resp=$(request -X DELETE "$BASE/api/conversations/$cid" -H "Authorization: Bearer $token" || true)
    del_code=$(printf '%s' "$del_resp" | json_value 'code')
    if [ "$del_code" = "0" ]; then pass_check "conversation delete"; else fail_check "conversation delete"; fi
  else
    fail_check "conversation create"
  fi

  # Every tenth iteration, verify a published Agent runtime contract without sending secrets.
  if [ $((ITER % 10)) -eq 0 ]; then
    agents=$(request "$BASE/api/agents" -H "Authorization: Bearer $token" -H 'X-Tenant-Id: 1' || true)
    if printf '%s' "$agents" | "$PY" -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==0 and isinstance(d.get("data"),list)' >/dev/null 2>&1; then
      pass_check "agent list"
    else
      fail_check "agent list"
    fi
  fi

  log "SUMMARY iteration=$ITER pass=$PASS fail=$FAIL elapsed=$(( $(date +%s) - START ))s"
  sleep "$INTERVAL"
done

log "END iterations=$ITER pass=$PASS fail=$FAIL duration=$(( $(date +%s) - START ))s"
[ "$FAIL" -eq 0 ]
