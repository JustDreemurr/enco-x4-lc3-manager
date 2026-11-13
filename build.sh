#!/usr/bin/env sh
# 打包模块：输出 dist/enco_x4_lc3-<version>.zip，并按 module.prop 更新 update.json（管理器在线更新用）
set -eu
cd "$(dirname "$0")"
REPO=JustDreemurr/enco-x4-lc3-manager
VERSION=$(sed -n 's/^version=//p' module/module.prop)
VERSION_CODE=$(sed -n 's/^versionCode=//p' module/module.prop)
NAME="enco_x4_lc3-$VERSION.zip"
OUT="dist/$NAME"
mkdir -p dist
rm -f "$OUT"
(cd module && zip -r -9 -X "../$OUT" . -x '*.DS_Store')
cat > update.json <<JSON
{
  "version": "$VERSION",
  "versionCode": $VERSION_CODE,
  "zipUrl": "https://github.com/$REPO/releases/download/$VERSION/$NAME",
  "changelog": "https://raw.githubusercontent.com/$REPO/main/CHANGELOG.md"
}
JSON
echo "$OUT"
