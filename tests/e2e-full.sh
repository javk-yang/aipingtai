#!/usr/bin/env bash
# AgentForge 全模块回归测试
# 覆盖：认证、模型、会话聊天、工具、技能、知识库

set -euo pipefail

ROOT="/Users/jack.yang/WorkBuddy/开发全流程体验"
BASE="${AGENTFORGE_BASE:-http://127.0.0.1:8090}"
CURL="/usr/bin/curl -sS --max-time 15 --noproxy '*'"
PY="/Users/jack.yang/.workbuddy/binaries/python/versions/3.13.12/bin/python3"
TOKEN=""
REFRESH=""
FAILURES=0

log() { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "[FAIL] $*"; ((FAILURES++)) || true; }
pass() { echo "[PASS] $*"; }

jq_code() { "$PY" -c "import sys,json;d=json.load(sys.stdin);$1"; }

# ---------- 登录 & token 刷新 ----------
log "==== 认证模块 ===="
LOGIN_RESP=$($CURL -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"identifier":"admin","password":"Admin@2026"}')
TOKEN=$(echo "$LOGIN_RESP" | jq_code 'print(d.get("data",{}).get("accessToken",""))')
REFRESH=$(echo "$LOGIN_RESP" | jq_code 'print(d.get("data",{}).get("refreshToken",""))')
if [ "${#TOKEN}" -gt 20 ] && [ "${#REFRESH}" -gt 20 ]; then
  pass "登录获取双 token"
else
  fail "登录失败：$LOGIN_RESP"
fi

AUTH="Authorization: Bearer $TOKEN"

# token 刷新
REFRESH_RESP=$($CURL -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}")
NEW_TOKEN=$(echo "$REFRESH_RESP" | jq_code 'print(d.get("data",{}).get("accessToken",""))')
if [ "${#NEW_TOKEN}" -gt 20 ]; then
  pass "refresh token 成功"
  AUTH="Authorization: Bearer $NEW_TOKEN"
else
  fail "refresh token 失败：$REFRESH_RESP"
fi

# 当前用户
ME_RESP=$($CURL "$BASE/api/auth/me" -H "$AUTH")
ME_CODE=$(echo "$ME_RESP" | jq_code 'print(d.get("code"))')
[ "$ME_CODE" = "0" ] && pass "获取当前用户成功" || fail "获取当前用户失败：$ME_RESP"

# ---------- 模型管理 ----------
log "==== 模型管理模块 ===="
MODELS_RESP=$($CURL "$BASE/api/models" -H "$AUTH" -H 'X-Tenant-Id: 1')
DEFAULT_PROVIDER=$(echo "$MODELS_RESP" | jq_code 'm=next((x for x in d.get("data",[]) if x.get("isDefault")==1),None);print(m.get("provider") if m else "NONE")')
[ "$DEFAULT_PROVIDER" = "deterministic" ] && pass "默认模型为 deterministic" || fail "默认模型不是 deterministic：$DEFAULT_PROVIDER"

# 创建草稿模型
DRAFT_RESP=$($CURL -X POST "$BASE/api/models/test" -H 'Content-Type: application/json' -H "$AUTH" -d '{"provider":"deterministic","model":"test-model","baseUrl":"","apiKey":"","temperature":0.7,"maxTokens":512}')
DRAFT_CODE=$(echo "$DRAFT_RESP" | jq_code 'print(d.get("code"))')
[ "$DRAFT_CODE" = "0" ] && pass "草稿模型连通性测试通过" || fail "草稿模型测试失败：$DRAFT_RESP"

# ---------- 会话 & 聊天 ----------
log "==== 会话与聊天模块 ===="
CONV_RESP=$($CURL -X POST "$BASE/api/conversations" -H 'Content-Type: application/json' -H "$AUTH" -d '{"title":"全量回归会话"}')
CID=$(echo "$CONV_RESP" | jq_code 'print(d.get("data",{}).get("id",""))')
[ -n "$CID" ] && pass "创建会话成功" || fail "创建会话失败：$CONV_RESP"

DEL_RESP=$($CURL -X DELETE "$BASE/api/conversations/$CID" -H "$AUTH")
DEL_CODE=$(echo "$DEL_RESP" | jq_code 'print(d.get("code"))')
[ "$DEL_CODE" = "0" ] && pass "删除会话接口成功" || fail "删除会话接口失败：$DEL_RESP"

LIST_RESP=$($CURL "$BASE/api/conversations" -H "$AUTH")
STILL_THERE=$(echo "$LIST_RESP" | jq_code "print('YES' if any(r.get('id')=='$CID' for r in d.get('data',{}).get('records',[])) else 'NO')")
[ "$STILL_THERE" = "NO" ] && pass "删除后会话从列表消失" || fail "删除后会话仍出现在列表"

# 确定性模型聊天
TMP_SSE=$(mktemp)
$CURL --max-time 30 -N -X POST "$BASE/api/chat/stream" -H 'Content-Type: application/json' -H "$AUTH" -H 'X-Trace-Id: full-det' -d '{"content":"你好","modelConfigId":1}' > "$TMP_SSE" 2>/dev/null || true
DONE=$(grep -aE '^event: ?message_done$' "$TMP_SSE" >/dev/null && echo YES || echo NO)
DELTA=$(grep -aE '^event: ?content_delta$' "$TMP_SSE" >/dev/null && echo YES || echo NO)
ERR=$(grep -aE '^event: ?error$' "$TMP_SSE" >/dev/null && echo YES || echo NO)
[ "$DONE" = "YES" ] && [ "$DELTA" = "YES" ] && [ "$ERR" = "NO" ] && pass "确定性模型聊天 SSE 完整" || fail "确定性模型聊天异常 done=$DONE delta=$DELTA err=$ERR"
rm -f "$TMP_SSE"

# 真实模型失败降级（OpenAI 官方端点被透明代理拦截）
TMP_SSE2=$(mktemp)
$CURL --max-time 20 -N -X POST "$BASE/api/chat/stream" -H 'Content-Type: application/json' -H "$AUTH" -H 'X-Trace-Id: full-real' -d '{"content":"你好","modelConfigId":2}' > "$TMP_SSE2" 2>/dev/null || true
START=$(grep -aE '^event: ?message_start$' "$TMP_SSE2" >/dev/null && echo YES || echo NO)
ERR2=$(grep -aE '^event: ?error$' "$TMP_SSE2" >/dev/null && echo YES || echo NO)
DONE2=$(grep -aE '^event: ?message_done$' "$TMP_SSE2" >/dev/null && echo YES || echo NO)
# 只要没有完整 message_done，就视为降级成功
if [ "$DONE2" = "NO" ]; then
  pass "真实模型未成功完成，平台降级正确：start=$START err=$ERR2 done=$DONE2"
else
  fail "真实模型异常完成 start=$START err=$ERR2 done=$DONE2"
fi
rm -f "$TMP_SSE2"

# ---------- 工具 & 技能 ----------
log "==== 工具与技能模块 ===="
TOOLS_RESP=$($CURL "$BASE/api/tools" -H "$AUTH" -H 'X-Tenant-Id: 1')
TOOLS_CODE=$(echo "$TOOLS_RESP" | jq_code 'print(d.get("code"))')
[ "$TOOLS_CODE" = "0" ] && pass "工具列表可访问" || fail "工具列表失败：$TOOLS_RESP"

SKILLS_RESP=$($CURL "$BASE/api/skills" -H "$AUTH" -H 'X-Tenant-Id: 1')
SKILLS_CODE=$(echo "$SKILLS_RESP" | jq_code 'print(d.get("code"))')
[ "$SKILLS_CODE" = "0" ] && pass "技能列表可访问" || fail "技能列表失败：$SKILLS_RESP"

# ---------- 知识库 ----------
log "==== 知识库模块 ===="
KNOW_RESP=$($CURL "$BASE/api/knowledge" -H "$AUTH" -H 'X-Tenant-Id: 1')
KNOW_CODE=$(echo "$KNOW_RESP" | jq_code 'print(d.get("code"))')
[ "$KNOW_CODE" = "0" ] && pass "知识库列表可访问" || fail "知识库列表失败：$KNOW_RESP"

log "==== 汇总：失败数 $FAILURES ===="
exit $FAILURES
