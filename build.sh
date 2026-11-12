#!/usr/bin/env sh
# 打包模块：输出 dist/enco_x4_lc3-<version>.zip
set -eu
cd "$(dirname "$0")"
VERSION=$(sed -n 's/^version=//p' module/module.prop)
OUT="dist/enco_x4_lc3-$VERSION.zip"
mkdir -p dist
rm -f "$OUT"
(cd module && zip -r -9 -X "../$OUT" . -x '*.DS_Store')
echo "$OUT"
