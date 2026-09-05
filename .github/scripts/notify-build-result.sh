#!/usr/bin/env bash
set -euo pipefail
REPO="${GITHUB_REPOSITORY:-anglesgirl/javchu-ech-verifier}"
RUN_ID="${GITHUB_RUN_ID:-}"
SHA="${GITHUB_SHA:-}"
STATUS="${BUILD_STATUS:-${{ job.status }}}"
[ -z "$RUN_ID" ] && RUN_ID="${GITHUB_RUN_ID:-unknown}"
SHORT_SHA="${SHA:0:7}"
RUN_URL="https://github.com/${REPO}/actions/runs/${RUN_ID}"
BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
CHAT_ID="${TELEGRAM_CHAT_ID:-}"
if [ -z "$BOT_TOKEN" ] || [ -z "$CHAT_ID" ]; then
  echo "missing TELEGRAM secrets, skip notify"
  exit 0
fi
send_msg() {
  local text="$1"
  curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendMessage" \
    -d chat_id="${CHAT_ID}" \
    -d parse_mode="Markdown" \
    --data-urlencode "text=${text}" | head -c 500; echo
}
if [ "$STATUS" = "success" ]; then
  MSG="✅ 构建成功 \`$SHORT_SHA\` | run \`$RUN_ID\`
[查看运行](${RUN_URL})
APK 已上传到 Artifacts，稍后自动下载推送"
  send_msg "$MSG"
  # 尝试直接发送APK（若 artifact 已生成）
  APK=$(find app/build/outputs -name "*.apk" 2>/dev/null | head -n1 || true)
  if [ -n "$APK" ] && [ -f "$APK" ]; then
    echo "sending APK $APK"
    curl -sS -X POST "https://api.telegram.org/bot${BOT_TOKEN}/sendDocument" \
      -F chat_id="${CHAT_ID}" \
      -F caption="APK $SHORT_SHA run $RUN_ID" \
      -F document=@"$APK" | head -c 500; echo
  fi
else
  LOG=$(./gradlew :app:assembleDebug --stacktrace 2>&1 | tail -80 || true)
  # 提取真实错误
  ERR=$(echo "$LOG" | grep -E "e: |FAILURE|error:" | head -20 || echo "未知错误")
  MSG="❌ 构建失败 \`$SHORT_SHA\` | run \`$RUN_ID\` | \`$STATUS\`
[查看运行](${RUN_URL})
\`\`\`
${ERR:0:3000}
\`\`\`"
  send_msg "$MSG"
fi
