#!/system/bin/sh
# 蓝牙启动前把自定义白名单合并进 conf，无需重启蓝牙
MODDIR=${0%/*}
sh "$MODDIR/x4lc3.sh" early >/dev/null 2>&1
