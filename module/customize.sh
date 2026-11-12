# shellcheck shell=sh
# 安装脚本（KernelSU / APatch / Magisk 通用）

DATA=/data/adb/enco_x4_lc3

if [ "$KSU" = true ]; then
  MGR="KernelSU ($KSU_VER_CODE)"
elif [ "$APATCH" = true ]; then
  MGR="APatch ($APATCH_VER_CODE)"
else
  MGR="Magisk ($MAGISK_VER_CODE)"
fi
ui_print "- 环境: $MGR"

OPLUSROM=$(getprop ro.build.version.oplusrom)
if [ -z "$OPLUSROM" ]; then
  ui_print "! 未检测到 ColorOS / OxygenOS（ro.build.version.oplusrom 为空）"
  ui_print "! 本模块依赖 OPLUS 蓝牙实现，在其他系统上不会生效"
else
  ui_print "- 系统: $(getprop ro.build.display.id)（oplusrom $OPLUSROM）"
fi

COEXIST=$(getprop persist.sys.bt.leacoexist)
if [ "$COEXIST" != true ]; then
  ui_print "! 双模共存开关 persist.sys.bt.leacoexist 未开启（当前: ${COEXIST:-空}）"
  ui_print "! 重启后在 WebUI“前置条件”中一键开启，或执行 su -c x4lc3 coexist on"
fi

ui_print "- 已验证: OnePlus 16 PYB110_17.0.1.201、OnePlus 13 PJZ110_17.0.0.103"

if [ "$KSU" != true ] && [ "$APATCH" != true ]; then
  ui_print "- Magisk 没有内置 WebUI：可用 KsuWebUI / WebUI X 打开本模块界面"
  [ "${MAGISK_VER_CODE:-0}" -lt 28000 ] && ui_print "! 模块“执行”按钮需要 Magisk 28.0+"
fi

# 数据目录在模块外，升级模块时保留白名单与配置
mkdir -p "$DATA"
[ -f "$DATA/config" ] || printf 'RESTORE_ON_BOOT=1\nWATCHDOG=0\nWATCH_INTERVAL=10\nANCHOR=\n' > "$DATA/config"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/x4lc3.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/system/bin/x4lc3" 0 2000 0755

ui_print "- 安装完成，重启后生效"
ui_print "- 用法: 管理器中点击“执行”切换全局 LC3，或打开 WebUI 管理白名单"
