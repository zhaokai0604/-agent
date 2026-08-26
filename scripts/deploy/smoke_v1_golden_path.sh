#!/usr/bin/env bash
# V1 Golden Path 冒烟（服务已启动，需 curl）
# 用法:
#   BASE=http://127.0.0.1:8088/award-log bash scripts/deploy/smoke_v1_golden_path.sh
# 可选: SMOKE_USER / SMOKE_PASS（默认随机注册新用户）
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8088/award-log}"
SMOKE_USER="${SMOKE_USER:-smoke_$(date +%s)}"
SMOKE_PASS="${SMOKE_PASS:-SmokePass123!}"
COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

pass() { echo "[PASS] $*"; }
fail() { echo "[FAIL] $*"; exit 1; }
warn() { echo "[WARN] $*"; }

json_code() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '.code // empty' <<<"$1" 2>/dev/null || true
  else
    echo "$1" | grep -o '"code"[[:space:]]*:[[:space:]]*[0-9]*' | head -1 | grep -o '[0-9]*' || true
  fi
}

echo "== V1 Golden Path Smoke =="
echo "BASE=$BASE USER=$SMOKE_USER"

echo "== 1. Actuator health =="
HEALTH=$(curl -sf "$BASE/actuator/health" || true)
echo "$HEALTH" | head -c 300
echo ""
[[ -n "$HEALTH" ]] && pass "actuator/health" || fail "actuator/health 不可达"

echo "== 2. Platform acceptance（公开） =="
ACC=$(curl -sf "$BASE/api/platform/acceptance" || true)
echo "$ACC" | head -c 1200
echo ""
[[ -n "$ACC" ]] && pass "platform/acceptance" || fail "platform/acceptance"

echo "== 3. Security self-check（公开只读） =="
SC=$(curl -sf "$BASE/api/security/self-check" || true)
echo "$SC" | head -c 800
echo ""
[[ -n "$SC" ]] && pass "security/self-check" || warn "self-check 需登录或不可达"

echo "== 4. 注册 + 登录 =="
curl -sf -X POST "$BASE/admin/user/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASS\",\"email\":\"${SMOKE_USER}@smoke.local\"}" \
  >/dev/null 2>&1 || true

LOGIN=$(curl -sf -c "$COOKIE_JAR" -b "$COOKIE_JAR" -X POST "$BASE/admin/user/login" \
  -H "Content-Type: application/json" \
  -H "X-Requested-With: XMLHttpRequest" \
  -d "{\"username\":\"$SMOKE_USER\",\"password\":\"$SMOKE_PASS\"}")
CODE=$(json_code "$LOGIN")
[[ "$CODE" == "200" ]] && pass "login code=200" || fail "login failed: $LOGIN"

echo "== 5. MCP 工具目录 =="
TOOLS=$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE/api/mcp/tools")
echo "$TOOLS" | grep -q DiskTool && pass "DiskTool 已注册" || fail "缺少 DiskTool"

echo "== 6. DiskTool 只读执行 =="
DISK=$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST "$BASE/api/mcp/execute" \
  -H "Content-Type: application/json" \
  -H "X-Requested-With: XMLHttpRequest" \
  -d '{"toolName":"DiskTool","parameters":{},"userMessage":"golden path disk check"}')
CODE=$(json_code "$DISK")
[[ "$CODE" == "200" ]] && pass "DiskTool execute code=200" || warn "DiskTool: $DISK"

echo "== 7. CleanTemp dry-run 预览 =="
CLEAN=$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST "$BASE/api/mcp/execute" \
  -H "Content-Type: application/json" \
  -H "X-Requested-With: XMLHttpRequest" \
  -d '{"toolName":"CleanTempTool","parameters":{"dryRun":true},"userMessage":"golden path temp preview","previewOnly":true}')
echo "$CLEAN" | head -c 600
echo ""
echo "$CLEAN" | grep -qE 'confirmToken|NEED_CONFIRM|preview|dryRun' && pass "CleanTemp 预览/确认链路有响应" || warn "CleanTemp 响应需人工核对"

echo "== 8. 巡检历史 =="
PATROL=$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE/api/ops/patrol/history" || true)
CODE=$(json_code "$PATROL")
[[ "$CODE" == "200" ]] && pass "patrol/history code=200" || warn "patrol/history: $PATROL"

echo "== 9. 审计 feed =="
AUDIT=$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE/api/audit/feed?pageNum=1&pageSize=5" || true)
CODE=$(json_code "$AUDIT")
[[ "$CODE" == "200" ]] && pass "audit/feed code=200" || warn "audit/feed: $AUDIT"

echo "== 10. YAML Skills =="
SKILLS=$(curl -sf -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE/api/ops/skills/yaml" || true)
[[ -n "$SKILLS" ]] && pass "ops/skills/yaml 有响应" || warn "skills/yaml 不可达"

echo "== 完成：Golden Path 冒烟结束（写操作未自动 confirmExecute，演示需 UI 二次确认）=="
