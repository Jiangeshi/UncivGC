#!/usr/bin/env bash
# ============================================================
# UncivGC 正式包打包脚本 (2026-08-31)
# 用途: 开源仓库代码里是占位符 (YOUR_LOBBY_HOST 等), 正式包构建前
#       注入真实服务器地址/token, 构建完成后还原代码。
# 用法:
#   bash release_build.sh                          # 从环境变量读 (见下)
#   LOBBY_HOST=110.40.151.9 SP_HOST=110.40.151.9 FS_HOST=118.25.42.214 LOBBY_TOKEN=<token> bash release_build.sh
# 产物: desktop/build/libs/UncivGC.jar + android/build/outputs/apk/release/Unciv-release.apk
# 注意: 注入只在本次构建生效, 脚本结束自动 git checkout 还原占位符 (代码永远干净)
# ============================================================
set -e
cd "$(dirname "$0")"

# 真实值来源: 环境变量 (缺省从本地 ../.ugc_release_env 读 — 避免命令行传密钥被脱敏/记录)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${LOBBY_TOKEN:-}" ] && [ -f "${SCRIPT_DIR}/../.ugc_release_env" ]; then
  set -a; source "${SCRIPT_DIR}/../.ugc_release_env"; set +a
fi
LOBBY_HOST="${LOBBY_HOST:-YOUR_LOBBY_HOST}"
SP_HOST="${SP_HOST:-YOUR_SP_HOST}"
FS_HOST="${FS_HOST:-YOUR_FS_HOST}"
LOBBY_TOKEN="${LOBBY_TOKEN:-YOUR_LOBBY_TOKEN}"

echo "[release] 注入服务器配置: lobby=$LOBBY_HOST sp=$SP_HOST fs=$FS_HOST token=${LOBBY_TOKEN:0:6}..."

FILES=(
  "core/src/com/unciv/logic/lobby/LobbyApi.kt"
  "core/src/com/unciv/ui/screens/worldscreen/FrameSync.kt"
  "core/src/com/unciv/ui/screens/lobbyscreens/LobbyRoomScreen.kt"
)

restore() {
  echo "[release] 还原占位符 (仅替换回占位符, 保留其他未提交改动)..."
  python3 - "$LOBBY_HOST" "$LOBBY_TOKEN" "$FS_HOST" "$SP_HOST" "${FILES[0]}" "${FILES[1]}" "${FILES[2]}" <<'PYEOF'
import sys
host_l, token, host_f, host_s, f0, f1, f2 = sys.argv[1:8]
reps = {
    f0: [(f"http://{host_l}:8125", "http://YOUR_LOBBY_HOST:8125"), (f'"{token}"', '"YOUR_LOBBY_TOKEN"')],
    f1: [(f'"{host_f}"', '"YOUR_FS_HOST"')],
    f2: [(f"http://{host_s}:30126", "http://YOUR_SP_HOST:30126")],
}
for path, pairs in reps.items():
    s = open(path, encoding='utf-8').read()
    for old, new in pairs:
        s = s.replace(old, new)
    open(path, 'w', encoding='utf-8').write(s)
print("[release] 占位符还原完成")
PYEOF
  # 验证占位符已还原 (任一未还原 = 警告)
  if ! grep -q "YOUR_LOBBY_HOST" "${FILES[0]}" || ! grep -q "YOUR_LOBBY_TOKEN" "${FILES[0]}" \
    || ! grep -q "YOUR_FS_HOST" "${FILES[1]}" || ! grep -q "YOUR_SP_HOST" "${FILES[2]}"; then
    echo "[release] 警告: 占位符还原未完全成功, 请检查文件!"
  fi
}
trap restore EXIT

# 注入 (sed 替换占位符)
sed -i '' "s|http://YOUR_LOBBY_HOST:8125|http://${LOBBY_HOST}:8125|g" "${FILES[0]}"
sed -i '' "s|YOUR_LOBBY_TOKEN|${LOBBY_TOKEN}|g" "${FILES[0]}"
sed -i '' "s|YOUR_FS_HOST|${FS_HOST}|g" "${FILES[1]}"
sed -i '' "s|http://YOUR_SP_HOST:30126|http://${SP_HOST}:30126|g" "${FILES[2]}"

# 检查注入是否全部生效 (任一占位符残留 = 中止)
if grep -rn "YOUR_LOBBY_HOST\|YOUR_LOBBY_TOKEN\|YOUR_FS_HOST\|YOUR_SP_HOST" "${FILES[@]}"; then
  echo "[release] 错误: 仍有占位符残留, 中止构建"; exit 1
fi
echo "[release] 注入完成, 开始构建..."

./gradlew :desktop:dist :android:assembleRelease --no-build-cache -q

echo "[release] 构建完成:"
ls -la desktop/build/libs/UncivGC.jar android/build/outputs/apk/release/Unciv-release.apk
echo "[release] jar md5: $(md5 -q desktop/build/libs/UncivGC.jar)"
echo "[release] apk md5: $(md5 -q android/build/outputs/apk/release/Unciv-release.apk)"
