#!/system/bin/sh
# x4lc3.sh — OPPO Enco X4 全局 LC3 管理（KernelSU / APatch / Magisk 模块核心脚本）
#
# 原理：
#   白名单：在 /data/misc/bluedroid/bt_oplus_iot_list.conf 的 [dualmode_a2dp_lowlatency] add_list
#           追加包名，扩充双模 LC3 白名单（蓝牙启动时读取，改动后需重启蓝牙）
#   全局开关：写 Settings.Global debug_gamemode_* 伪造常驻游戏态，使 LE Audio(LC3) 成为活动设备
# 本脚本是模块唯一的逻辑实现；action.sh / service.sh / post-fs-data.sh / WebUI 都调用它。

set -f

MODDIR=$(cd "${0%/*}" 2>/dev/null && pwd)
[ -f "$MODDIR/x4lc3.sh" ] || MODDIR=/data/adb/modules/enco_x4_lc3
SELF=$MODDIR/x4lc3.sh
VERSION=$(sed -n 's/^version=//p' "$MODDIR/module.prop" 2>/dev/null)

DATA=${X4LC3_DATA:-/data/adb/enco_x4_lc3}
CONF=${X4LC3_CONF:-/data/misc/bluedroid/bt_oplus_iot_list.conf}
SECTION=dualmode_a2dp_lowlatency

WL=$DATA/whitelist          # 自定义白名单，一行一个包名（唯一数据源）
CFG=$DATA/config            # KEY=VALUE
STATE=$DATA/state           # on | off（用户期望的全局 LC3 状态）
ACTIVE=$DATA/active_pkg     # 当前用于游戏态的包名
BACKUP=$DATA/backup         # 开启前备份的 settings 原值
BASE=$DATA/base.conf        # 外部（云控/手动）写入的原始 conf，合并时作为基础
APPLIED=$DATA/applied.conf  # 本模块最近一次写入的 conf
PENDING=$DATA/pending_restart
PIDF=$DATA/watchdog.pid
LOG=$DATA/x4lc3.log

# 系统硬编码的国内双模白名单（OplusDualModeHelper.DUAL_MODE_ACCEPT_LIST_DOMESTIC）
BUILTIN="org.chromium.latency.walt com.android.sound.localization com.superpowered.superpoweredlatency
com.tencent.tmgp.pubgmhd com.tencent.tmgp.dfm com.garena.game.df com.proxima.dfm.mobile com.tencent.tmgp.codev
com.tencent.tmgp.sgame com.tencent.lolm com.tencent.tmgp.cod com.tencent.tmgp.cf com.netease.hyxd
com.netease.hyxd.nearme.gamecenter com.netease.dwrg com.netease.dwrg.nearme.gamecenter com.tencent.mf.uam
com.gamestar.perfectpiano com.hermes.mk com.tencent.tmgp.speedmobile com.netease.yhtj.nearme.gamecenter
com.netease.mrzh"

# ---------- 基础工具 ----------

log() { [ -d "$DATA" ] && echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; return 0; }
say() { echo "$*"; log "$*"; }
die() { echo "错误: $1" >&2; log "错误: $1"; exit "${2:-1}"; }

trim_log() {
  [ -f "$LOG" ] || return 0
  [ "$(wc -l < "$LOG")" -gt 500 ] && { tail -n 300 "$LOG" > "$LOG.tmp"; mv -f "$LOG.tmp" "$LOG"; }
  return 0
}

valid_pkg() { echo "$1" | grep -qE '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$'; }

init_data() {
  mkdir -p "$DATA"
  [ -f "$WL" ] || : > "$WL"
  [ -f "$STATE" ] || echo off > "$STATE"
  [ -f "$CFG" ] || printf 'RESTORE_ON_BOOT=1\nWATCHDOG=0\nWATCH_INTERVAL=10\nANCHOR=\n' > "$CFG"
}

cfg_get() {
  _v=$(sed -n "s/^$1=//p" "$CFG" 2>/dev/null | tail -n 1)
  if [ -n "$_v" ]; then echo "$_v"; else echo "$2"; fi
}
cfg_set() {
  grep -v "^$1=" "$CFG" > "$CFG.tmp" 2>/dev/null
  echo "$1=$2" >> "$CFG.tmp"
  mv -f "$CFG.tmp" "$CFG"
}

state_get() { cat "$STATE" 2>/dev/null || echo off; }

sget() { settings get global "$1" 2>/dev/null | tr -d '\r'; }
sput() { settings put global "$1" "$2" >/dev/null 2>&1; }
sdel() { settings delete global "$1" >/dev/null 2>&1; }

load_pkgs() {
  [ -n "$PKGS_LOADED" ] && return 0
  PKGS=$(pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://')
  PKGS_LOADED=1
}
installed() { load_pkgs; printf '%s\n' "$PKGS" | grep -qxF "$1"; }

wl_read() { grep -E '^[A-Za-z][A-Za-z0-9_.]*$' "$WL" 2>/dev/null; }
wl_has() { wl_read | grep -qxF "$1"; }
is_builtin() { for _b in $BUILTIN; do [ "$_b" = "$1" ] && return 0; done; return 1; }

detect_manager() {
  if [ -d /data/adb/ksu ]; then echo KernelSU
  elif [ -d /data/adb/ap ]; then echo APatch
  elif [ -d /data/adb/magisk ]; then echo Magisk
  else echo unknown; fi
}

# ---------- 白名单 conf ----------

# 以 BASE（可能不存在）为基础，把自定义白名单合并进 [dualmode_a2dp_lowlatency]，输出到 stdout。
# - add_list 取并集并去重；delete_list 中移除我们的包名（为空则删掉该行）
# - 解析器要求每个键值行恰好一个 "="，因此不输出空值行
gen_conf() {
  _pk=$(wl_read | tr '\n' ',' | sed 's/,*$//')
  { [ -f "$BASE" ] && cat "$BASE"; } | awk -v sec="[$SECTION]" -v pk="$_pk" '
function trim(s) { sub(/^[ \t\r]+/, "", s); sub(/[ \t\r]+$/, "", s); return s }
function join_unique(list,   n, a, i, p, out, seen) {
  out = ""; n = split(list, a, ",")
  for (i = 1; i <= n; i++) {
    p = trim(a[i])
    if (p != "" && !(p in seen)) { seen[p] = 1; out = out (out == "" ? "" : ",") p }
  }
  return out
}
function drop_mine(list,   n, a, i, p, out) {
  out = ""; n = split(list, a, ",")
  for (i = 1; i <= n; i++) {
    p = trim(a[i])
    if (p != "" && !(p in mine)) out = out (out == "" ? "" : ",") p
  }
  return out
}
BEGIN { n = split(pk, t, ","); for (i = 1; i <= n; i++) mine[t[i]] = 1; insec = 0; found = 0; added = 0 }
/^[ \t]*\[/ {
  if (insec && !added) { print "add_list=" pk; added = 1 }
  insec = (trim($0) == sec)
  if (insec) found = 1
  print; next
}
insec && index($0, "=") > 0 {
  eq = index($0, "="); k = trim(substr($0, 1, eq - 1)); v = trim(substr($0, eq + 1))
  if (k == "add_list") { print "add_list=" join_unique(v "," pk); added = 1; next }
  if (k == "delete_list") { v = drop_mine(v); if (v != "") print "delete_list=" v; next }
}
{ print }
END {
  if (insec && !added) print "add_list=" pk
  else if (!found) { if (NR > 0) print ""; print sec; print "add_list=" pk }
}'
}

write_conf() {
  cat "$1" > "$CONF" || return 1
  chown bluetooth:bluetooth "$CONF" 2>/dev/null || chown 1002:1002 "$CONF"
  chmod 660 "$CONF"
  restorecon "$CONF" >/dev/null 2>&1
  return 0
}

# 同步 conf；返回 0 表示文件发生了变化（需重启蓝牙）
conf_sync() {
  if [ -f "$CONF" ]; then
    if [ ! -f "$APPLIED" ] || ! cmp -s "$CONF" "$APPLIED"; then
      cp -f "$CONF" "$BASE"
      log "检测到外部写入的 conf，已保存为合并基础"
    fi
  elif [ -f "$APPLIED" ]; then
    log "conf 被外部删除，基础配置视为空"
    rm -f "$BASE" "$APPLIED"
  fi

  _t=$DATA/target.conf
  if [ -n "$(wl_read)" ]; then gen_conf > "$_t"
  elif [ -f "$BASE" ]; then cp -f "$BASE" "$_t"
  else rm -f "$_t"; fi

  if [ -f "$_t" ]; then
    if [ -f "$CONF" ] && cmp -s "$_t" "$CONF"; then
      mv -f "$_t" "$APPLIED"; return 1
    fi
    write_conf "$_t" || { rm -f "$_t"; die "写入 $CONF 失败"; }
    mv -f "$_t" "$APPLIED"
    log "已写入 $CONF"
    return 0
  fi
  if [ -f "$CONF" ]; then
    rm -f "$CONF" "$APPLIED"
    log "白名单为空且无基础配置，已删除 $CONF"
    return 0
  fi
  return 1
}

# conf 是否需要重新同步（被外部改写/删除，或白名单尚未写入）
conf_drifted() {
  if [ -f "$APPLIED" ]; then
    [ -f "$CONF" ] || return 0
    cmp -s "$CONF" "$APPLIED" && return 1
    return 0
  fi
  [ -n "$(wl_read)" ]
}

bt_on() { [ "$(sget bluetooth_on)" != 0 ]; }

wait_bt() { # $1: 0=等待关闭 1=等待开启
  _i=0
  while [ $_i -lt 20 ]; do
    _s=$(sget bluetooth_on)
    if [ "$1" = 0 ]; then [ "$_s" = 0 ] && return 0; else [ "$_s" != 0 ] && return 0; fi
    sleep 1; _i=$((_i + 1))
  done
  return 1
}

restart_bt() {
  if ! bt_on; then
    rm -f "$PENDING"
    say "蓝牙当前已关闭，下次开启蓝牙时生效"
    return 0
  fi
  say "重启蓝牙…（耳机会短暂断开）"
  svc bluetooth disable
  wait_bt 0 || say "警告: 等待蓝牙关闭超时"
  sleep 2
  svc bluetooth enable
  wait_bt 1 || say "警告: 等待蓝牙开启超时"
  rm -f "$PENDING"
  if [ "$(state_get)" = on ] && [ -s "$ACTIVE" ]; then
    sleep 3
    apply_gamemode "$(cat "$ACTIVE")"
  fi
  say "蓝牙已重启，耳机重连后生效"
}

after_conf_change() { # $1 = NO_RESTART
  touch "$PENDING"
  if [ "$1" = 1 ]; then say "白名单已更新，重启蓝牙后生效"; else restart_bt; fi
}

# ---------- 常驻游戏态 ----------

# 设置观察者只在值变化时触发，因此当前已为 1 时先置 0 再置 1
apply_gamemode() {
  if [ "$(sget debug_gamemode_value)" = 1 ]; then sput debug_gamemode_value 0; sleep 1; fi
  sput debug_gamemode_savegame "$1"
  # 开启了“用户偏好低延迟”的机型要求 <包名>:1，否则 isUserPreferLLMode() 为假
  if [ "$(getprop ro.oplus.audio.support.low_latency_game)" = true ]; then
    sput oplus_games_low_latency_state "$1:1"
  fi
  sput debug_gamemode_value 1
}

backup_settings() {
  [ -f "$BACKUP" ] && return 0
  {
    echo "savegame=$(sget debug_gamemode_savegame)"
    echo "llstate=$(sget oplus_games_low_latency_state)"
  } > "$BACKUP"
}

restore_one() {
  if [ -z "$2" ] || [ "$2" = null ]; then sdel "$1"; else sput "$1" "$2"; fi
}

restore_settings() {
  [ -f "$BACKUP" ] || return 0
  restore_one debug_gamemode_savegame "$(sed -n 's/^savegame=//p' "$BACKUP")"
  # 不能写空串（会被系统忽略），原值为空时删除该键
  restore_one oplus_games_low_latency_state "$(sed -n 's/^llstate=//p' "$BACKUP")"
  rm -f "$BACKUP"
}

# 选择用于游戏态的包名：配置指定 → 自定义白名单中已安装 → 内置白名单中已安装
pick_anchor() {
  _a=$(cfg_get ANCHOR "")
  if [ -n "$_a" ]; then
    if installed "$_a"; then echo "$_a"; return 0; fi
    log "指定的游戏态包名 $_a 未安装，改为自动选择"
  fi
  for _p in $(wl_read); do installed "$_p" && { echo "$_p"; return 0; }; done
  for _p in $BUILTIN; do installed "$_p" && { echo "$_p"; return 0; }; done
  return 1
}

effective_on() { [ "$(state_get)" = on ] && [ "$(sget debug_gamemode_value)" = 1 ]; }

cmd_on() {
  _pkg=$1
  if [ -n "$_pkg" ]; then
    valid_pkg "$_pkg" || die "无效包名: $_pkg"
    installed "$_pkg" || say "警告: $_pkg 未安装，可能不会生效"
    if ! wl_has "$_pkg" && ! is_builtin "$_pkg"; then
      say "警告: $_pkg 不在内置或自定义白名单中，可能不会生效"
    fi
  else
    _pkg=$(pick_anchor) || die "没有可用的白名单应用：请先在白名单中添加一个已安装的应用，或安装系统内置白名单中的游戏" 2
  fi
  if [ -f "$PENDING" ] && ! is_builtin "$_pkg"; then
    say "警告: 白名单改动尚未生效，请先重启蓝牙"
  fi
  _co=$(getprop persist.sys.bt.leacoexist)
  if [ "$_co" != true ]; then
    say "警告: persist.sys.bt.leacoexist=${_co:-空}，本机蓝牙可能未启用双模共存，全局 LC3 很可能无效（可运行 diag 排查）"
  fi
  backup_settings
  apply_gamemode "$_pkg"
  echo on > "$STATE"
  echo "$_pkg" > "$ACTIVE"
  say "已开启全局 LC3（游戏态包名: $_pkg）"
  [ "$(cfg_get WATCHDOG 0)" = 1 ] && watchdog_start
  return 0
}

cmd_off() {
  sput debug_gamemode_value 0
  restore_settings
  echo off > "$STATE"
  rm -f "$ACTIVE"
  say "已关闭全局 LC3，切回经典蓝牙（LHDC/AAC）"
}

cmd_toggle() { if effective_on; then cmd_off; else cmd_on; fi; }

# ---------- 守护进程 ----------

wd_running() {
  [ -f "$PIDF" ] || return 1
  _pid=$(cat "$PIDF")
  [ -n "$_pid" ] && kill -0 "$_pid" 2>/dev/null && grep -q _watch "/proc/$_pid/cmdline" 2>/dev/null
}

watchdog_start() {
  wd_running && return 0
  if command -v setsid >/dev/null 2>&1; then
    setsid sh "$SELF" _watch </dev/null >/dev/null 2>&1 &
  else
    nohup sh "$SELF" _watch </dev/null >/dev/null 2>&1 &
  fi
  say "守护进程已启动"
}

watchdog_stop() {
  if wd_running; then kill "$(cat "$PIDF")" 2>/dev/null; say "守护进程已停止"; fi
  rm -f "$PIDF"
}

wd_loop() {
  echo $$ > "$PIDF"
  log "守护进程启动 (pid $$)"
  while [ "$(cfg_get WATCHDOG 0)" = 1 ]; do
    if [ "$(state_get)" = on ] && [ -s "$ACTIVE" ]; then
      _v=$(sget debug_gamemode_value)
      if [ "$_v" != 1 ]; then
        apply_gamemode "$(cat "$ACTIVE")"
        log "守护: debug_gamemode_value 被改为 $_v，已恢复游戏态"
      fi
    fi
    if conf_drifted; then
      log "守护: conf 被外部改写，重新合并"
      conf_sync && after_conf_change 0 >/dev/null
    fi
    sleep "$(cfg_get WATCH_INTERVAL 10)"
  done
  rm -f "$PIDF"
  log "守护进程退出"
}

# ---------- 状态 ----------

lea_events() {
  dumpsys bluetooth_manager 2>/dev/null | tr -d '\000\r' | grep "Active Device Changed" | tail -n 6 | sed 's/^[[:space:]]*//'
}

# 根据最近一次 LE_AUDIO 活动设备事件推断：true / false / null
lea_state() {
  _l=$(printf '%s\n' "$1" | grep "LE_AUDIO" | tail -n 1)
  if [ -z "$_l" ]; then echo null
  elif echo "$_l" | grep -qi "Changed: null"; then echo false
  else echo true; fi
}

json_esc() { printf '%s' "$1" | tr -d '\r' | tr '\t' ' ' | sed 's/\\/\\\\/g; s/"/\\"/g'; }
jstr() { printf '"%s"' "$(json_esc "$1")"; }
jbool() { if "$@"; then printf true; else printf false; fi; }

cmd_status_json() {
  load_pkgs
  _ev=$(lea_events)
  bt_on && _bt=true || _bt=false
  _lea=$(lea_state "$_ev")
  [ "$_bt" = false ] && _lea=false
  _anchor=$(pick_anchor)
  printf '{'
  printf '"version":%s,"manager":%s,' "$(jstr "$VERSION")" "$(jstr "$(detect_manager)")"
  printf '"state":%s,"effective":%s,' "$(jstr "$(state_get)")" "$(jbool effective_on)"
  printf '"gamemode":{"value":%s,"savegame":%s,"userPrefer":%s,"llProp":%s},' \
    "$(jstr "$(sget debug_gamemode_value)")" "$(jstr "$(sget debug_gamemode_savegame)")" \
    "$(jstr "$(sget oplus_games_low_latency_state)")" "$(jstr "$(getprop ro.oplus.audio.support.low_latency_game)")"
  printf '"leacoexist":%s,"btOn":%s,"leaActive":%s,' "$(jstr "$(getprop persist.sys.bt.leacoexist)")" "$_bt" "$_lea"
  printf '"anchor":%s,"activePkg":%s,' "$(jstr "$_anchor")" "$(jstr "$(cat "$ACTIVE" 2>/dev/null)")"
  printf '"pendingRestart":%s,"watchdog":%s,' "$(jbool test -f "$PENDING")" "$(jbool wd_running)"
  printf '"config":{"restoreOnBoot":%s,"watchdog":%s,"watchInterval":%s,"anchor":%s},' \
    "$(jstr "$(cfg_get RESTORE_ON_BOOT 1)")" "$(jstr "$(cfg_get WATCHDOG 0)")" \
    "$(jstr "$(cfg_get WATCH_INTERVAL 10)")" "$(jstr "$(cfg_get ANCHOR "")")"
  printf '"whitelist":['
  _f=1
  for _p in $(wl_read); do
    [ $_f = 1 ] || printf ','; _f=0
    printf '{"pkg":%s,"installed":%s}' "$(jstr "$_p")" "$(jbool installed "$_p")"
  done
  printf '],"builtinInstalled":['
  _f=1
  for _p in $BUILTIN; do
    installed "$_p" || continue
    [ $_f = 1 ] || printf ','; _f=0
    printf '%s' "$(jstr "$_p")"
  done
  printf '],"events":['
  _f=1
  _IFS=$IFS; IFS='
'
  for _l in $_ev; do
    [ $_f = 1 ] || printf ','; _f=0
    printf '%s' "$(jstr "$_l")"
  done
  IFS=$_IFS
  printf ']}\n'
}

cmd_status() {
  _ev=$(lea_events)
  _lea=$(lea_state "$_ev")
  bt_on || _lea=bt_off
  case $_lea in
    true) _dev="LE Audio（LC3）" ;;
    false) _dev="经典蓝牙（A2DP）" ;;
    bt_off) _dev="蓝牙已关闭" ;;
    *) _dev="未知" ;;
  esac
  if effective_on; then echo "全局 LC3: 已开启"; else echo "全局 LC3: 未开启"; fi
  echo "活动设备（据最近事件推断）: $_dev"
  echo "游戏态: value=$(sget debug_gamemode_value), package=$(sget debug_gamemode_savegame), user_prefer=$(sget oplus_games_low_latency_state)"
  echo "共存开关 persist.sys.bt.leacoexist=$(getprop persist.sys.bt.leacoexist)"
  [ -f "$PENDING" ] && echo "白名单改动待重启蓝牙后生效"
  wd_running && echo "守护进程: 运行中" || echo "守护进程: 未运行"
  if [ -n "$_ev" ]; then
    echo "最近的活动设备切换:"
    printf '%s\n' "$_ev" | tail -n 4 | sed 's/^/  /'
  fi
}

# ---------- 诊断 ----------

# 蓝牙 MAC 只保留后两段
mask_mac() { sed -E 's/([0-9A-Fa-f]{2}:){4}([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})/XX:XX:XX:XX:\2/g'; }

cmd_diag() {
  _out=${X4LC3_DIAG_DIR:-/sdcard/Download}/x4lc3-diag-$(date +%Y%m%d-%H%M%S).txt
  {
    echo "== 模块 $VERSION / $(detect_manager)"
    echo "== 设备"
    for _k in ro.product.brand ro.product.model ro.product.name ro.build.display.id \
              ro.build.version.oplusrom ro.build.version.release ro.build.version.sdk; do
      echo "$_k=$(getprop "$_k")"
    done
    echo; echo "== 蓝牙相关属性"
    getprop | grep -iE 'leacoexist|leaudio|le_audio|bap|lc3|low_latency|dual|a2dp|lhdc|bluetooth\.profile'
    echo; echo "== settings"
    for _k in bluetooth_on debug_gamemode_value debug_gamemode_savegame oplus_games_low_latency_state; do
      echo "$_k=$(sget "$_k")"
    done
    echo; echo "== 模块状态"
    cmd_status
    echo; echo "== 自定义白名单"; wl_read
    echo; echo "== $CONF"; cat "$CONF" 2>/dev/null || echo "（不存在）"
    echo; echo "== dual_mode_support.xml"
    cat /data/user_de/0/*bluetooth*/shared_prefs/dual_mode_support.xml 2>/dev/null || echo "（不存在）"
    echo; echo "== PeerSmartAudioFeature（X4 应为 56318 / 0xDBFE）"
    grep -E '^\[|PeerSmartAudioFeature' /data/misc/bluedroid/bt_remote_dev_info.conf 2>/dev/null | grep -B1 PeerSmartAudioFeature
    echo; echo "== bt_config.conf（名称 / LE Audio 相关）"
    grep -iE '^\[|^Name|LeAudio|Lc3|DevType' /data/misc/bluedroid/bt_config.conf 2>/dev/null | grep -iE -B2 'enco|LeAudio|Lc3' | head -n 60
    echo; echo "== dumpsys bluetooth_manager（编码 / LE Audio）"
    dumpsys bluetooth_manager 2>/dev/null | tr -d '\000\r' | grep -iE 'codec|LeAudio|LE_AUDIO|DualMode|DualConnection|Active Device' | head -n 80
    echo; echo "== logcat"
    logcat -d -b all 2>/dev/null | grep -iE 'OplusDualMode|isDualConnection|oplusHandleLea|BluetoothLowLatency|leacoexist|SmartAudioFeature|GameSpaceMode' | tail -n 80
    echo; echo "== 模块日志"; tail -n 50 "$LOG" 2>/dev/null
  } 2>&1 | mask_mac > "$_out"
  cat "$_out"
  echo
  echo "诊断信息已保存到 $_out"
}

# ---------- 白名单命令 ----------

cmd_add() {
  [ $# -ge 1 ] || die "用法: add <包名>..."
  for _p in "$@"; do valid_pkg "$_p" || die "无效包名: $_p"; done
  for _p in "$@"; do
    installed "$_p" || say "警告: $_p 未安装（仍会写入白名单）"
    wl_has "$_p" || echo "$_p" >> "$WL"
  done
  say "白名单: $(wl_read | tr '\n' ' ')"
  if conf_sync; then after_conf_change "$NO_RESTART"; else say "conf 无变化"; fi
}

cmd_del() {
  [ $# -ge 1 ] || die "用法: del <包名>..."
  for _p in "$@"; do
    grep -vxF "$_p" "$WL" > "$WL.tmp"
    mv -f "$WL.tmp" "$WL"
  done
  say "白名单: $(wl_read | tr '\n' ' ')"
  if conf_sync; then after_conf_change "$NO_RESTART"; else say "conf 无变化"; fi
}

cmd_clear() {
  : > "$WL"
  say "自定义白名单已清空"
  if conf_sync; then after_conf_change "$NO_RESTART"; fi
}

cmd_config() {
  case $1 in
    get|'') cat "$CFG" ;;
    set)
      _k=$2; _val=$3
      case $_k in
        RESTORE_ON_BOOT|WATCHDOG)
          case $_val in 0|1) ;; *) die "$_k 取值须为 0 或 1" ;; esac ;;
        WATCH_INTERVAL)
          case $_val in ''|*[!0-9]*) die "WATCH_INTERVAL 须为整数" ;; esac
          [ "$_val" -ge 3 ] && [ "$_val" -le 600 ] || die "WATCH_INTERVAL 范围为 3-600 秒" ;;
        ANCHOR)
          [ -z "$_val" ] || valid_pkg "$_val" || die "无效包名: $_val" ;;
        *) die "未知配置项: $_k" ;;
      esac
      cfg_set "$_k" "$_val"
      say "已设置 $_k=$_val"
      if [ "$_k" = WATCHDOG ]; then
        if [ "$_val" = 1 ]; then watchdog_start; else watchdog_stop; fi
      fi
      ;;
    *) die "用法: config get | config set <KEY> <VALUE>" ;;
  esac
}

# 撤销本模块对系统的全部改动（保留模块配置）
cmd_reset() {
  watchdog_stop
  cmd_off
  : > "$WL"
  _changed=0
  conf_sync && _changed=1
  rm -f "$APPLIED" "$BASE"
  say "已清空自定义白名单并还原 conf"
  if [ $_changed = 1 ]; then after_conf_change "$NO_RESTART"; else rm -f "$PENDING"; fi
}

# post-fs-data：蓝牙尚未启动，同步 conf 不需要重启蓝牙
cmd_early() {
  conf_sync
  rm -f "$PENDING"
}

# service.sh：开机完成后恢复状态
cmd_boot() {
  until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
  sleep 15
  log "开机恢复开始"
  conf_sync && after_conf_change 0 >/dev/null
  if [ "$(state_get)" = on ]; then
    if [ "$(cfg_get RESTORE_ON_BOOT 1)" = 1 ]; then
      _p=$(cat "$ACTIVE" 2>/dev/null)
      # 子 shell：cmd_on 失败时 die 不会中断后续的守护进程启动
      if [ -n "$_p" ] && installed "$_p"; then (cmd_on "$_p") >/dev/null; else (cmd_on) >/dev/null; fi
    else
      cmd_off >/dev/null
    fi
  fi
  [ "$(cfg_get WATCHDOG 0)" = 1 ] && watchdog_start >/dev/null
  log "开机恢复完成"
}

usage() {
  cat <<EOF
x4lc3 $VERSION — OPPO Enco X4 全局 LC3 管理（需 root）

全局 LC3（常驻游戏态，切换无需重启蓝牙）:
  on [包名]            开启；省略包名时自动选择已安装的白名单应用
  off                  关闭，切回经典蓝牙
  toggle               切换
  status [--json]      查看状态

自定义白名单（写入 $CONF，需重启蓝牙生效）:
  list                 列出
  add <包名>...        添加
  del <包名>...        删除
  clear                清空
  sync                 按白名单重新合并 conf
  restart-bt           重启蓝牙
  （add/del/clear/sync/reset 加 --no-restart 可跳过自动重启蓝牙）

其他:
  config get | config set <KEY> <VALUE>
                       RESTORE_ON_BOOT=0|1  WATCHDOG=0|1  WATCH_INTERVAL=3-600  ANCHOR=<包名|空>
  watchdog start|stop|status
  pkgs                 列出已安装的第三方应用
  log                  查看日志
  diag                 收集诊断信息（保存到 /sdcard/Download）
  reset                撤销全部改动（关闭 LC3、清空白名单、还原 conf）
EOF
}

# ---------- 入口 ----------

CMD=$1
[ $# -gt 0 ] && shift
case $CMD in ''|-h|--help|help) usage; exit 0 ;; esac
[ "$(id -u)" = 0 ] || [ -n "$X4LC3_DATA" ] || die "需要 root 权限"

NO_RESTART=0; JSON=0; ARGS=""
for _a in "$@"; do
  case $_a in
    --no-restart) NO_RESTART=1 ;;
    --json) JSON=1 ;;
    *) ARGS="$ARGS $_a" ;;
  esac
done
# shellcheck disable=SC2086
set -- $ARGS

init_data
trim_log

case $CMD in
  on) cmd_on "$1" ;;
  off) cmd_off ;;
  toggle) cmd_toggle ;;
  status) if [ $JSON = 1 ]; then cmd_status_json; else cmd_status; fi ;;
  list) wl_read ;;
  add) cmd_add "$@" ;;
  del|rm|remove) cmd_del "$@" ;;
  clear) cmd_clear ;;
  sync) if conf_sync; then after_conf_change "$NO_RESTART"; else say "conf 无变化"; fi ;;
  restart-bt) restart_bt ;;
  config) cmd_config "$@" ;;
  watchdog)
    case $1 in
      start) watchdog_start ;;
      stop) watchdog_stop ;;
      *) wd_running && echo running || echo stopped ;;
    esac ;;
  pkgs) pm list packages -3 2>/dev/null | tr -d '\r' | sed 's/^package://' | sort ;;
  log) tail -n 100 "$LOG" 2>/dev/null ;;
  diag) cmd_diag ;;
  reset) cmd_reset ;;
  early) cmd_early ;;
  boot) cmd_boot ;;
  _watch) wd_loop ;;
  *) usage; exit 1 ;;
esac
