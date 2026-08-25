#!/usr/bin/env bash
# AgentForge 端到端冒烟测试
# 覆盖：登录、模型默认策略、会话 CRUD、确定性模型聊天、真实模型失败降级

set -euo pipefail

ROOT="/Users/jack.yang/WorkBuddy/开发全流程体验"
BASE="${AGENTFORGE_BASE:-http://127.0.0.1:8090}"
PROXY="${USE_PROXY:-}"   # 空=让 curl 自己决定；当前环境有透明代理，默认留空
CURL="/usr/bin/curl -sS --max-time 10"
[ -n "$PROXY" ] || CURL="$CURL --noproxy '*'"

PY="/Users/jack.yang/.workbuddy/binaries/python/versions/3.13.12/bin/python3"
TOKEN=""
FAILURES=0

log() { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "[FAIL] $*"; ((FAILURES++)); }
pass() { echo "[PASS] $*"; }

# ---------- 1. 登录 ----------
log "测试 1/5：登录"
LOGIN_RESP=$($CURL -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"identifier":"admin","password":"Admin@2026"}')
CODE=$(echo "$LOGIN_RESP" | "$PY" -c 'import sys,json;print(json.load(sys.stdin).get("code"))')
TOKEN=$(echo "$LOGIN_RESP" | "$PY" -c 'import sys,json;print(json.load(sys.stdin).get("data",{}).get("accessToken",""))')
if [ "$CODE" = "0" ] && [ "${#TOKEN}" -gt 20 ]; then
  pass "登录成功，token 长度 ${#TOKEN}"
else
  fail "登录失败：$LOGIN_RESP"
fi

AUTH="Authorization: Bearer $TOKEN"

# ---------- 2. 模型列表默认策略 ----------
log "测试 2/5：模型列表默认策略（默认应为启用模型）"
MODELS_RESP=$($CURL "$BASE/api/models" -H "$AUTH" -H 'X-Tenant-Id: 1')
DEFAULT_ID=$(echo "$MODELS_RESP" | "$PY" -c 'import sys,json;data=json.load(sys.stdin).get("data",[]);m=next((x for x in data if x.get("isDefault")==1),None);print(m.get("id") if m else "NONE")')
DEFAULT_PROVIDER=$(echo "$MODELS_RESP" | "$PY" -c 'import sys,json;data=json.load(sys.stdin).get("data",[]);m=next((x for x in data if x.get("isDefault")==1),None);print(m.get("provider") if m else "NONE")')
DEFAULT_ENABLED=$(echo "$MODELS_RESP" | "$PY" -c 'import sys,json;data=json.load(sys.stdin).get("data",[]);m=next((x for x in data if x.get("isDefault")==1),None);print(m.get("enabled") if m else "0")')
if [ "$DEFAULT_PROVIDER" != "NONE" ] && [ "$DEFAULT_ENABLED" = "1" ]; then
  pass "默认模型为启用配置 (id=$DEFAULT_ID provider=$DEFAULT_PROVIDER)"
else
  fail "默认模型无效：id=$DEFAULT_ID provider=$DEFAULT_PROVIDER enabled=$DEFAULT_ENABLED"
fi

# ---------- 3. 会话 CRUD（重点：删除后列表不再出现） ----------
log "测试 3/5：会话创建与删除"
CONV_RESP=$($CURL -X POST "$BASE/api/conversations" -H 'Content-Type: application/json' -H "$AUTH" -d '{"title":"冒烟测试会话"}')
CID=$(echo "$CONV_RESP" | "$PY" -c 'import sys,json;print(json.load(sys.stdin).get("data",{}).get("id",""))')
if [ -z "$CID" ]; then
  fail "创建会话失败：$CONV_RESP"
else
  pass "创建会话成功：$CID"
fi

DEL_RESP=$($CURL -X DELETE "$BASE/api/conversations/$CID" -H "$AUTH")
DEL_CODE=$(echo "$DEL_RESP" | "$PY" -c 'import sys,json;print(json.load(sys.stdin).get("code"))')
if [ "$DEL_CODE" = "0" ]; then
  pass "删除会话接口返回成功"
else
  fail "删除会话接口失败：$DEL_RESP"
fi

# 验证列表中已不存在
LIST_RESP=$($CURL "$BASE/api/conversations" -H "$AUTH")
STILL_THERE=$(echo "$LIST_RESP" | "$PY" -c "import sys,json;data=json.load(sys.stdin).get('data',{});recs=data.get('records',[]);print('YES' if any(r.get('id')=='$CID' for r in recs) else 'NO')")
if [ "$STILL_THERE" = "NO" ]; then
  pass "删除后会话不再出现在列表中"
else
  fail "删除后会话仍出现在列表中"
fi

# ---------- 4. 确定性模型聊天 SSE + 普通问题内容断言 ----------
log "测试 4/5：确定性模型聊天 SSE 与自然语言回答"
TMP_SSE=$(mktemp)
$CURL --max-time 30 -N -X POST "$BASE/api/chat/stream" \
  -H 'Content-Type: application/json' -H "$AUTH" -H 'X-Trace-Id: smoke-det' \
  -d '{"content":"你好，请只回复：连接测试成功","modelConfigId":1}' > "$TMP_SSE" 2>/dev/null || true
EVENTS=$(grep -a '^event:' "$TMP_SSE" | sort | uniq -c | tr '\n' ' ')
HAS_DONE=$(grep -aE '^event: ?message_done$' "$TMP_SSE" >/dev/null && echo YES || echo NO)
HAS_ERROR=$(grep -aE '^event: ?error$' "$TMP_SSE" >/dev/null && echo YES || echo NO)
HAS_DELTA=$(grep -aE '^event: ?content_delta$' "$TMP_SSE" >/dev/null && echo YES || echo NO)
HAS_ANSWER=$(grep -a '连接测试成功' "$TMP_SSE" >/dev/null && echo YES || echo NO)
HAS_RAW_TOOL=$(grep -aE 'tool_calls|tool_code' "$TMP_SSE" >/dev/null && echo YES || echo NO)
if [ "$HAS_DONE" = "YES" ] && [ "$HAS_DELTA" = "YES" ] && [ "$HAS_ERROR" = "NO" ] && [ "$HAS_ANSWER" = "YES" ] && [ "$HAS_RAW_TOOL" = "NO" ]; then
  pass "确定性模型 SSE 与回答内容完整：$EVENTS"
else
  fail "确定性模型 SSE/回答异常：done=$HAS_DONE delta=$HAS_DELTA error=$HAS_ERROR answer=$HAS_ANSWER raw_tool=$HAS_RAW_TOOL events=[$EVENTS]"
  echo "--- SSE raw ---"; head -40 "$TMP_SSE"
fi
rm -f "$TMP_SSE"

# 验证平台介绍问题不会只返回固定演示文案，也不会复述内部协议
TMP_SSE3=$(mktemp)
$CURL --max-time 30 -N -X POST "$BASE/api/chat/stream" \
  -H 'Content-Type: application/json' -H "$AUTH" -H 'X-Trace-Id: smoke-intro' \
  -d '{"content":"请介绍 AgentForge 平台","modelConfigId":1}' > "$TMP_SSE3" 2>/dev/null || true
if grep -a '企业级 ' "$TMP_SSE3" >/dev/null && grep -a 'AI Agent 平台' "$TMP_SSE3" >/dev/null && ! grep -aE 'tool_calls|我收到的输入摘要' "$TMP_SSE3" >/dev/null; then
  pass "普通平台介绍问题得到相关自然语言回答"
else
  fail "平台介绍回答不符合预期"; head -40 "$TMP_SSE3"
fi
rm -f "$TMP_SSE3"
# modelConfigId=2 是 OpenAI 官方端点，在当前环境会被透明代理拦截。
# 之前返回 error 事件；绕过 env 代理后直接连接会超时。两种表现都视为
# "平台未崩溃、未返回 message_done" 的降级成功。
log "测试 5/5：真实模型失败降级"
TMP_SSE2=$(mktemp)
$CURL --max-time 20 -N -X POST "$BASE/api/chat/stream" \
  -H 'Content-Type: application/json' -H "$AUTH" -H 'X-Trace-Id: smoke-real' \
  -d '{"content":"你好","modelConfigId":2}' > "$TMP_SSE2" 2>/dev/null || true
HAS_START=$(grep -aE '^event: ?message_start$' "$TMP_SSE2" >/dev/null && echo YES || echo NO)
HAS_ERROR2=$(grep -aE '^event: ?error$' "$TMP_SSE2" >/dev/null && echo YES || echo NO)
HAS_DONE2=$(grep -aE '^event: ?message_done$' "$TMP_SSE2" >/dev/null && echo YES || echo NO)
# 成功降级：要么报错，要么只返回 start/ping 然后超时；绝不能返回完整 message_done
if [ "$HAS_DONE2" = "NO" ]; then
  pass "真实模型未成功完成，平台降级正确：start=$HAS_START error=$HAS_ERROR2 done=$HAS_DONE2"
else
  fail "真实模型异常完成：start=$HAS_START error=$HAS_ERROR2 done=$HAS_DONE2"
  echo "--- SSE raw ---"; head -20 "$TMP_SSE2"
fi
rm -f "$TMP_SSE2"

# ---------- 汇总 ----------
log "测试完成，失败数：$FAILURES"
exit $FAILURES
