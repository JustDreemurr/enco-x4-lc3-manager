#!/system/bin/sh
# 卸载：还原 conf 与 settings，删除数据目录。
# 多数管理器在下次开机的早期阶段执行本脚本，此时 settings 命令不可用，
# 因此先处理文件，settings 的还原放到开机完成后的后台任务里执行。

DATA=/data/adb/enco_x4_lc3
CONF=/data/misc/bluedroid/bt_oplus_iot_list.conf

[ -d "$DATA" ] || exit 0

# 停止守护进程
[ -f "$DATA/watchdog.pid" ] && kill "$(cat "$DATA/watchdog.pid")" 2>/dev/null

# 1. conf：只有当前文件是本模块写入的才还原，被云控改写过的保持原样
CONF_CHANGED=0
if [ -f "$DATA/applied.conf" ] && [ -f "$CONF" ] && cmp -s "$CONF" "$DATA/applied.conf"; then
  if [ -f "$DATA/base.conf" ]; then
    cat "$DATA/base.conf" > "$CONF"
  else
    rm -f "$CONF"
  fi
  CONF_CHANGED=1
fi

# 2. 读出需要还原的 settings，之后即可删除数据目录
STATE=$(cat "$DATA/state" 2>/dev/null)
HAS_BACKUP=0
if [ -f "$DATA/backup" ]; then
  HAS_BACKUP=1
  SAVEGAME=$(sed -n 's/^savegame=//p' "$DATA/backup")
  LLSTATE=$(sed -n 's/^llstate=//p' "$DATA/backup")
fi
rm -rf "$DATA"

restore_one() {
  if [ -z "$2" ] || [ "$2" = null ]; then
    settings delete global "$1" >/dev/null 2>&1
  else
    settings put global "$1" "$2" >/dev/null 2>&1
  fi
}

restore_settings() {
  [ "$STATE" = on ] && settings put global debug_gamemode_value 0 >/dev/null 2>&1
  if [ $HAS_BACKUP = 1 ]; then
    restore_one debug_gamemode_savegame "$SAVEGAME"
    restore_one oplus_games_low_latency_state "$LLSTATE"
  fi
}

if [ "$(getprop sys.boot_completed)" = 1 ]; then
  # 系统已运行（部分管理器会立即执行卸载脚本）：直接还原，conf 有变化则重启蓝牙
  restore_settings
  if [ $CONF_CHANGED = 1 ] && [ "$(settings get global bluetooth_on)" != 0 ]; then
    svc bluetooth disable; sleep 4; svc bluetooth enable
  fi
else
  # 开机早期：蓝牙尚未启动，conf 已生效；settings 等开机完成后再还原
  (
    until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
    sleep 10
    restore_settings
  ) </dev/null >/dev/null 2>&1 &
fi
