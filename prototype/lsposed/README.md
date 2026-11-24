# Encore 实现说明

使用说明见仓库根目录的 [README](../../README.md)。本文记录 Encore（包名 `io.github.justdreemurr.encore`）的实现细节、一加 13 实测数据和开发验证步骤。模块仅适配固件 `PJZ110_17.0.0.103(CN01)`，双模连接偏好、白名单、省电和多设备占用等条件仍由系统检查。

## 路由与低延迟

原型通过 `ActiveDeviceManager` 调用 `LeAudioService.setActiveDevice()`，与 ColorOS 游戏低延迟路径共用 LE Audio 激活入口。系统蓝牙游戏态退出后，原型会在普通音频模式下为 X4 请求 LE Audio。

在 Encore 中启用 `Game_Vnd` 后，原型会在 X4 重连时调用固件的 WALT 入口，请求高通低延迟配置。手机侧使用 `setGameMode(false, true)`，X4 的经典蓝牙连接就绪后，原型通过系统 Smart Audio 接口向这副耳机发送 WALT 通知。关闭开关或 X4 失去路由时，原型会请求恢复两端状态。关闭开关并重连可重新协商普通 LE Audio。ColorOS 全局游戏态、`GCP_TX` 和 `AudioGameMode` 由系统控制。

一加 13 双耳串流实测如下。管理器的「实时链路」会显示当前连接的实际协商值。

| 配置 | LC3 帧长 | 每声道码率 | 最大传输时延参数 | RTN |
| --- | --- | --- | --- | --- |
| 普通 LE Audio | 10 ms | 80 kbps | 20 ms | 5 |
| `GAME_VND` | 10 ms | 80 kbps | 5 ms | 3 |

这两次串流的播放呈现延迟参数均为 5 ms。传输时延和呈现延迟均为协议参数，手机到耳机的实际音频延迟需要单独测量。

单独启用手机侧 WALT 时，听感对照出现了轻微电音，约 6 秒的控制器采样窗口内每耳有 4–6 个 ISO 发送单元被丢弃。v0.2.7 加入耳机侧通知后，实际串流仍为 `GAME_VND`，双耳为 5 ms / RTN 3。用户在这次对照中未听到电音，控制器仍记录到部分丢弃。关闭开关并重连可恢复 20 ms / RTN 5 的普通 LC3。

原型在请求激活后的 3 秒内，最多拦截一次 ColorOS 低延迟状态机进入普通模式时对该 X4 组的自动撤销。拦截时要求 X4 的 LE Audio 连接仍有效、音频模式为普通模式，且没有有线输出、游戏态或其他活动 A2DP 耳机。

X4 重连时，原型等待系统完成通话配置（HFP）初始化。如果系统在启动窗口内因 HFP 初始化撤下 X4 的 LE Audio，且同一副 X4 在首次请求后的 8 秒内重新成为活动媒体输出，原型可补发一次激活请求。

`v0.1.12` 试验直接激活 LC3。在当前没有活动 A2DP 或 LE Audio 输出时，原型将首次 X4 A2DP 激活延后最多 5 秒，等系统处理完 HFP 且 X4 的 LE Audio 可串流后，直接请求 LE Audio。如果到期时仍无活动蓝牙媒体输出且 X4 的 A2DP 已连接，原型重新请求 A2DP。等待时的音频输出由系统决定。

## 场景

ColorOS 蓝牙原生库在 X4 连接时检查耳机是否支持双模，并读取 `persist.sys.bt.coexist_optimization`（未设置时按 `true` 处理）。两者都满足时，X4 的 LE Audio 组带上共存标记：连接后立即以 GAME 预建 CIS，`USAGE_MEDIA` 的播放也被映射为 GAME，激活走 ColorOS 的快捷路径。把属性设为 `false` 后，音乐走 MEDIA（Native Filter 的 Media 档生效），游戏走 GAME，可自动切换；暂停后需要重新协商编码和 QoS，恢复播放从约 220–290 ms 变为约 400–490 ms。

「固定为 Game」拦截 `LeAudioNativeInterface.setInGame`：系统传入的游戏状态照常记录，X4 是活动 LE Audio 设备且开关打开时，向原生栈传入 `true`。开关或活动设备变化时，模块直接调用 `setInGameNative` 让状态立即生效。

## 诊断

蓝牙进程中的模块在 `ActiveDeviceManager.dump` 前输出 `Encore version:`、路由状态和最近 80 条 `Encore event:`。ColorOS 对蓝牙进程的 logcat 有每进程配额（`LOG_FLOWCTRL ... DROPPED`），这些行是排查连接瞬间问题的主要来源。

管理器通过 Root Shell 运行只读脚本采集检测数据，脚本的标准输入接到 `/dev/null`，避免子命令读走 Shell 的输入。导出时用系统文件选择器（`ACTION_CREATE_DOCUMENT`）保存 zip，写入前把完整蓝牙地址替换为只保留后两段。

在这台手机上，`ActiveDeviceManager` 的 `mA2dpActiveDevice` 和 `mLeAudioActiveDevice` 可以同时指向 X4。系统在 X4 走 LE Audio 后重新激活 A2DP 时，LE 的记录不会被清除，所以只看 LE 段的活动设备会误判路由。管理器按以下顺序判断实际输出：LE 组为 `STREAMING` 时是 LE Audio；A2DP `mIsPlaying` 为真时是 A2DP；都不在播放时，以 `handleA2dpActiveDeviceChanged` / `handleLeAudioActiveDeviceChanged` 历史中最后一次非空激活为准。

## 佩戴检测导致的回落

X4 已在 LE Audio 串流时，系统可能把它切回 A2DP。复现方法：强制停止耳机功能（`com.oplus.melody`）后重启蓝牙，等 X4 先走上 LE Audio，耳机功能随后连上耳机的私有控制通道（RFCOMM `0000079a-d102-11e1-9b23-00025b00a5a5`），耳机上报佩戴状态。调用链为：

```
OplusBluetoothWearCheck$WearCheckHandler.handleMessage
  → OplusBluetoothWearCheck.processHeadsetStatusChanged
    → HeadsetService.setActiveDevice(X4)
      → OplusActiveDeviceManagerExtImpl.oplusHandleHfpActiveDeviceChanged
        → ActiveDeviceManager.setLeAudioActiveDevice(null)
          → A2DP 活动设备设为 X4
```

`processHeadsetStatusChanged` 在耳机戴上、双模耳机需要双连接时，只有 `isUltraGameMode()` 为真才调用 `setDeviceOrPeerAsLeaActiveDevice`，否则设置 HFP 和 A2DP。原 KernelSU 方案伪造常驻游戏态，因此不受影响；Encore 不开启系统游戏态，需要单独处理。

模块在 `ActiveDeviceManager.start` 后用厂商类加载器挂钩两处：`processHeadsetStatusChanged` 在路由接管开启、被戴上的设备是 X4 时设置线程标记；`BluetoothLowLatencyService.isUltraGameMode` 只在有标记且直接调用方是 `processHeadsetStatusChanged` 时返回真，避免影响 `isDualModeConnectingOrConnected` 等其他判断。`processHeadsetStatusChanged` 已去优化，防止内联绕过钩子。生效时转储出现 `Encore wear: Kept X4 on LE Audio after it was put on`。X4 是活动 LE Audio 设备时被设为 HFP 活动设备的调用栈会记录为 `Encore event: HFP activation while X4 LE Audio active`。

日常摘下再戴回耳机走的是另一条路径：耳机戴回后发出播放命令，佩戴检测的 `handleAvrcpActiveMsg` 在 `IsAllowToSetActiveDevice` 通过后直接调用 `A2dpService.setActiveDevice(X4)`，`ActiveDeviceManager.handleA2dpActiveDeviceChanged` 再经 `updateLeAudioActiveDeviceIfDualMode` 撤下 LE Audio。ColorOS 只在 `oplusSetActiveBefore` 里、且 `isUltraGameMode()` 为真时拒绝这类激活。模块在 `A2dpService.setActiveDevice` 钩子中，当路由接管开启、X4 是活动 LE Audio 设备时直接返回 `false`，并把第一个调用方记录为 `Encore event: Kept X4 on LE Audio, refused A2DP activation <- …`。实测在启动阶段也会拦下来自 `handleA2dpActiveMsg` 和低延迟状态机 `processLowLatencyModeChanged` 的激活。每次佩戴状态变化记录为 `Encore event: Wear status …`。

## 构建

需要 JDK 17、Android 37 SDK 和 libxposed `102.0.0` 的 API、service 和 interface AAR。构建脚本默认从 Gradle 缓存取 AAR，也可以通过 `LIBXPOSED_AAR`、`LIBXPOSED_SERVICE_AAR` 和 `LIBXPOSED_INTERFACE_AAR` 指定。脚本将本地签名的 APK 写入 `dist/encore.apk`。

```sh
sh prototype/lsposed/build.sh
```

## 真机验证

1. 关闭现有 KernelSU 模块里的「全局 LC3」，保持 X4 双模共存和双连接偏好开启。用 `adb shell dumpsys bluetooth_manager` 确认 `mGameMode: false`。
2. 安装 APK，在支持 API 102 的 LSPosed 管理器里启用 Encore，勾选 `com.android.bluetooth`。在 KernelSU 管理器中允许 Encore 使用超级用户，然后在 Encore 中点「重启作用域」。
3. 在 Encore 中启用 `Game_Vnd`，重连 X4 并播放音频。「实时链路」应显示 Game_Vnd、5 ms 最大传输时延和 RTN 3。
4. 也可以运行 `adb shell dumpsys bluetooth_manager` 核对。检查活动组为 `STREAMING`，配置场景为 `GAME_VND`，并查看左右耳活动 ASE 的 `latency`、`rtn` 和 `SDU Interval`。`Encore earbud WALT` 报告通知的请求状态。`adb shell dumpsys activity top` 中的 `Manager`、`Live` 和 `Shown` 行显示管理器读取并展示的数值。用 `adb shell dumpsys media.audio_flinger` 检查播放线程输出 `AUDIO_DEVICE_OUT_BLE_HEADSET`。

如果手动重连后系统把 X4 切回 A2DP，点「重启作用域」后再检查活动组。一次真机对照中，手动重连后的低延迟串流约 3 秒后被系统撤销，重新加载蓝牙后恢复了持续的 `GAME_VND` 播放。

如需恢复系统选路，关闭「X4 路由接管」，或在 LSPosed 中停用 Encore 后点「重启作用域」。

从旧版 `io.github.justdreemurr.x4learoute` 升级时，先卸载旧包，再重新启用 Encore 并勾选作用域。两个包同时启用会重复注入蓝牙进程。
