#!/system/bin/sh
# 管理器“执行”按钮：切换全局 LC3
MODDIR=${0%/*}
sh "$MODDIR/x4lc3.sh" toggle
echo
echo "等待活动设备切换…"
sleep 3
sh "$MODDIR/x4lc3.sh" status
