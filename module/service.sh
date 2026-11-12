#!/system/bin/sh
# 开机完成后：检查 conf 是否被覆盖、按配置恢复全局 LC3、启动守护进程
MODDIR=${0%/*}
sh "$MODDIR/x4lc3.sh" boot >/dev/null 2>&1 &
