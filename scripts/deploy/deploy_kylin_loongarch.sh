#!/usr/bin/env bash
# ThreshCore — 银河麒麟高级服务器版 V11 + LoongArch64 生产构建
#
# 须在目标架构机器上执行（龙芯服务器或同 arch CI），交叉编译不在此脚本范围。
#
# 用法（仓库根目录）：
#   bash scripts/deploy/deploy_kylin_loongarch.sh
#
# 环境变量：
#   DEPLOY_OUT     默认 deploy/release-kylin
#   SKIP_FRONTEND  1 跳过前端
#   JAVA_HOME      须为 JDK 17（龙架构本地构建）
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

OUT="${DEPLOY_OUT:-$ROOT/deploy/release-kylin}"
export SKIP_TESTS=0
export PROD_BUILD=1

echo "======== 麒麟 V11 + LoongArch 生产构建 ========"
echo "uname: $(uname -a)"
echo ""

ARCH="$(uname -m)"
case "$ARCH" in
  loongarch64|mips64)
    echo "[arch] LoongArch/MIPS64 检测通过: $ARCH"
    ;;
  *)
    echo "[arch] 警告: 当前架构为 $ARCH，赛题交付目标为 loongarch64" >&2
    ;;
esac

if [[ -f /etc/os-release ]]; then
  echo "[os] $(grep -E '^(NAME|VERSION|ID)=' /etc/os-release | tr '\n' ' ')"
elif [[ -f /etc/.kyinfo ]]; then
  echo "[os] Kylin $(head -1 /etc/.kyinfo)"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "错误: 未找到 java，请安装 JDK 17（龙架构 build）" >&2
  exit 1
fi
java -version

need_cmds=(mvn node npm)
for c in "${need_cmds[@]}"; do
  if ! command -v "$c" >/dev/null 2>&1; then
    echo "错误: 缺少命令 $c" >&2
    exit 1
  fi
done

DEPLOY_OUT="$OUT" bash "$ROOT/scripts/deploy/deploy.sh"

cat > "$OUT/START-KYLIN.txt" << 'EOF'
麒麟 V11 + LoongArch 启动示例
=============================

1. 环境变量（见 scripts/deploy/env.kylin.prod.example）

2. 启动后端
   export SPRING_PROFILES_ACTIVE=prod-kylin
   java -Xms512m -Xmx2g -jar award-log.jar

3. 验收
   curl -s http://127.0.0.1:8088/award-log/api/platform/info | jq .
   # acceptance.deliveryTargetMatch 应为 true

4. Nginx + systemd
   见 award-log.service、nginx.kylin.sample.conf
EOF

echo ""
echo "产物目录: $OUT"
echo "======== 完成 ========"
