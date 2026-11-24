# Encore

让 OPPO Enco X4 在 ColorOS / OxygenOS 上常驻 **LE Audio（LC3）** 的 LSPosed 模块与管理器。

ColorOS 平时让 Enco X4 走经典蓝牙（LHDC / AAC），只有白名单游戏运行时才切到 LE Audio。Encore 注入蓝牙进程，只为 X4 请求 LE Audio，不开启系统全局游戏态，其他蓝牙耳机仍按系统默认方式连接。

> [!WARNING]
> 本项目使用固件私有接口。路由接管在蓝牙进程内生效，「按播放内容切换」和诊断页的一键修复会写入持久系统设置。

## 功能

- **X4 路由接管**：X4 连接后直接请求 LE Audio，拦截 ColorOS 低延迟状态机对 X4 LE Audio 的自动撤销，并省去「先 LHDC、再切 LC3」的短暂切换。
- **Game_Vnd 低延迟（实验性）**：请求高通 `GAME_VND` 配置，最大传输时延从 20 ms 降到 5 ms。
- **场景控制**：按播放内容切换 Media / Game 场景，或把 X4 固定在 Game 场景。
- **实时链路**：编码、LC3 帧长、每声道码率、最大传输时延、RTN、呈现延迟，以及左右耳的 RSSI 和丢包率。
- **一键重连 X4**、**重启作用域**，改完设置不用进系统蓝牙页。
- **诊断与日志**：逐项检查 X4 的 LE Audio 前置条件，提供对应的修复入口，支持导出日志。
- MD3 / MD3 Expressive 界面，支持系统取色和九种配色。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 机型与固件 | 一加 13（PJZ110），`PJZ110_17.0.0.103(CN01)`。其他固件上模块不会启用 |
| LSPosed | 支持 libxposed API 102 的版本（如 LSPosed 2.2.1） |
| Root | KernelSU / APatch / Magisk 均可。实时链路、场景切换和诊断需要授予 Encore 超级用户 |
| 耳机 | OPPO Enco X4 |

## 安装

1. 从 [GitHub Releases](https://github.com/JustDreemurr/enco-x4-lc3-manager/releases) 的 Encore 版本下载并安装 `encore.apk`，也可[自行构建](#构建)。
2. 在 LSPosed 管理器中启用 Encore，作用域勾选「蓝牙」（`com.android.bluetooth`）。
3. 在 Root 管理器中允许 Encore 使用超级用户。
4. 打开 Encore，点「重启作用域」，让蓝牙进程加载模块。

更新 Encore 后，管理器会提示蓝牙进程仍在运行旧版本，点「重启作用域」即可。从旧包 `io.github.justdreemurr.x4learoute` 升级时先卸载旧包，两个包同时启用会重复注入蓝牙进程。

## 首次设置

配对并连接 X4 后，在 Encore 的「连接设置 → 诊断与日志」查看「LE Audio 前置条件」。发现阻止 LE Audio 连接的条件时，主页会显示对应项目，并提供「查看并修复」入口。

| 条件 | 如何满足 |
| --- | --- |
| 双模共存 `persist.sys.bt.leacoexist=true` | 在诊断页点「开启并重启蓝牙」 |
| X4 的双连接偏好 | 在诊断页点「开启」，也可在耳机设置的「游戏模式」中打开 LC3 低延迟 |
| 双模白名单应用 | 安装 Superpowered Latency Test 或任一 ColorOS 双模白名单应用。只需安装，无需启动 |
| 省电模式 | 在诊断页进入「省电设置」并关闭省电 |
| 通话与媒体音频 | 在系统蓝牙里打开 X4 的「通话音频」和「媒体音频」 |

如果同时装了旧方案 KernelSU 模块，请关闭它的「全局 LC3」，否则两者会同时改动路由。

## 使用

### 主页

- **音频卡片**：当前路由（LE Audio、Game_Vnd 或 A2DP）、设备和播放状态。
- **实时链路**：按设置的间隔（1、2、5、10 秒）读取蓝牙状态。左右耳的 RSSI 和丢包率来自蓝牙控制器的质量报告，约每 6 秒更新一次。
- **连接设置**：

  | 选项 | 默认 | 说明 |
  | --- | --- | --- |
  | X4 路由接管 | 开 | 优先用 LE Audio 连接 X4。关闭后由系统选路 |
  | Game_Vnd | 关 | 实验性高通低延迟，可能导致丢包和连接不稳定 |
  | 重连 Enco X4 | — | 断开并重连 X4，让设置立即生效 |
  | LE Audio Native Filter 增强 | — | 进入场景设置，见下节 |
  | 诊断与日志 | — | 查看 LE Audio 前置条件、修复入口和日志导出 |

- **LSPosed 模块**：蓝牙进程里的模块状态与 PID、「重启作用域」和系统蓝牙设置。

连接设置改动后，重连 X4 才会生效。

### LE Audio Native Filter 增强

配合 LE Audio Native Filter（@Flyfish233）的 Media / Game 档位使用。

- **按播放内容切换 Media / Game 场景**：ColorOS 对双模 LE Audio 耳机启用了共存优化，连接后预先建好 CIS 并完成 QoS 配置，以缩短开始播放的等待，但会把所有播放都固定在 Game 场景，Native Filter 的 Media 档因此不生效。开启后把 `persist.sys.bt.coexist_optimization` 设为 `false`，链路回退为安卓原生管理：音乐走 Media、游戏走 Game。代价是暂停后恢复播放慢约 0.15–0.2 秒，且影响所有双模 LE Audio 耳机。关闭时删除该属性。
- **固定为 Game 场景**：不改属性。X4 是活动 LE Audio 设备时，让 LE Audio 栈保持游戏状态，所有播放都走 Game 场景，不开启系统游戏模式。

没有「固定为 Media」：游戏音频的 Game 标记由蓝牙原生库按播放用途写入，Java 层无法改写。

### 诊断与日志

在主页「连接设置」中进入。打开页面时自动检测，也可以点「重新检测」。

| 分组 | 检测项 |
| --- | --- |
| 运行环境 | 固件适配、Root、LSPosed 作用域、蓝牙进程中的模块版本 |
| LE Audio 前置条件 | 双模共存、省电模式、白名单命中、配对、双模识别、双连接偏好、通话与媒体音频、LE Audio 连接状态 |
| 连接 | 蓝牙、X4 当前路由与播放状态、路由决策 |
| 冲突与场景 | 系统游戏态、旧方案模块的全局 LC3、LE Audio Native Filter、当前场景模式 |

每项用颜色标出状态：主色为正常，灰色为提示，第三色为需注意，红色为异常。

「导出日志」生成 `encore-diag-<时间>.zip`，由你选择保存位置：

| 文件 | 内容 |
| --- | --- |
| `summary.txt` | 版本、设备、固件、LSPosed 状态、Encore 设置、实时链路和全部检测结果 |
| `bluetooth_manager.txt` | 完整 `dumpsys bluetooth_manager`，含 Encore 路由状态和最近 80 条模块事件 |
| `audio.txt` | 音频播放配置与 `dumpsys media.audio_flinger` |
| `properties.txt` | 蓝牙、LE Audio、游戏模式相关系统属性 |
| `lsposed.log` | LSPosed 日志中与 Encore 相关的行 |
| `logcat.txt` | logcat 中 Encore、LE Audio、低延迟状态机和崩溃相关的行 |
| `enco_x4_lc3.txt`、`lea_native_filter.txt` | 已安装时，旧方案模块与 Native Filter 的状态 |

导出的蓝牙地址只保留后两段，设备专属的双连接偏好键会被隐藏。ColorOS 会对蓝牙进程的 logcat 限流，所以模块同时把最近的事件记录在 `dumpsys bluetooth_manager` 的 `Encore event:` 行里。反馈问题时请附上这个 zip。

### 设置

右上角进入，可调整实时链路刷新间隔、显示模式（跟随系统 / 浅色 / 深色）和配色。

## 常见问题

**X4 仍然走 LHDC**

打开「连接设置 → 诊断与日志」，按「LE Audio 前置条件」中显示的异常项处理。模块显示旧版本或未加载时，点「重启作用域」。修复后仍显示 A2DP 时，可以在 Encore 里重连 X4。

**用着用着回到了 LHDC**

ColorOS 的佩戴检测在耳机戴上时会重新选择活动设备；耳机戴回后自动恢复播放，也会经 AVRCP 把 X4 设为 A2DP 设备。系统只在游戏低延迟状态下才拦住这些切换，X4 因此回到 LHDC。从 0.6.3 起，路由接管开启且 X4 正在走 LE Audio 时，Encore 拒绝把 X4 设为 A2DP 设备。仍然回落时，诊断页的「Enco X4」会显示 A2DP，点「重连 Enco X4」可恢复，反馈时请附上导出的日志。

**开启 Game_Vnd 后有电音或断续**

Game_Vnd 把重传次数从 5 降到 3，信号差时更容易丢包。关闭后重连 X4。

**Native Filter 的 Media 档没有效果**

开启「按播放内容切换 Media / Game 场景」并重连 X4。

**怎样完全恢复系统行为**

关闭「X4 路由接管」并重连 X4；或在 LSPosed 中停用 Encore，再重启蓝牙。开过「按播放内容切换」的话，先把它关掉。

## 工作原理

- **路由**：X4 的 LE Audio 组可串流后，模块通过 `ActiveDeviceManager` 调用 `LeAudioService.setActiveDevice()`，与 ColorOS 游戏低延迟路径共用同一个激活入口。请求后 3 秒内，最多拦截一次低延迟状态机对 X4 组的自动撤销。重连时如果当前没有活动输出，模块把 X4 的 A2DP 激活延后最多 5 秒，等 LE Audio 就绪后直接激活。
- **佩戴检测**：X4 戴上时，ColorOS 佩戴检测（`OplusBluetoothWearCheck.processHeadsetStatusChanged`）只在 `isUltraGameMode()` 为真时选择 LE Audio，否则把 X4 设为 HFP 和 A2DP 设备。路由接管开启时，模块只对这一次判断返回真，耳机的 SCO / CIS 被占用时系统仍会跳过 LE Audio。
- **A2DP 激活**：ColorOS 在游戏低延迟状态下会拒绝为需要双连接的耳机激活 A2DP（`OplusA2dpServiceExtImpl.oplusSetActiveBefore`）。Encore 不开游戏态，所以在 X4 是活动 LE Audio 设备时，由模块拒绝 `A2dpService.setActiveDevice(X4)`，覆盖佩戴检测的 AVRCP / A2DP 消息和低延迟状态机等入口。
- **Game_Vnd**：调用固件的 WALT 入口（`setGameMode(false, true)`），并通过 Smart Audio 接口通知耳机侧。关闭时请求恢复两端状态。
- **场景**：「按播放内容切换」改的是蓝牙原生库读取的 `persist.sys.bt.coexist_optimization`；「固定为 Game」拦截 `LeAudioNativeInterface.setInGame`。

实现细节和一加 13 实测数据见 [`lsposed/README.md`](lsposed/README.md)。

## 构建

仓库分为两个独立部分，各自构建和发布：

| 目录 | 内容 | 构建 |
| --- | --- | --- |
| `lsposed/` | Encore：LSPosed 模块与管理器 APK | `sh lsposed/build.sh` → `dist/encore.apk` |
| `module/` | Enco X4 LC3 Manager：KernelSU / APatch / Magisk 模块（旧方案） | `./build.sh` → `dist/enco_x4_lc3-<version>.zip` |

### Encore

需要 JDK 17、Android 37 SDK，以及 libxposed `102.0.0` 的 api、service、interface AAR（默认从 Gradle 缓存读取，也可用 `LIBXPOSED_AAR`、`LIBXPOSED_SERVICE_AAR`、`LIBXPOSED_INTERFACE_AAR` 指定）。

```sh
sh lsposed/build.sh    # 输出 dist/encore.apk
```

首次构建会生成调试签名 `dist/x4-le-route-debug.p12`，覆盖安装新版本时必须使用同一个签名。

## KernelSU 模块（旧方案）

仓库中的 `module/` 是早期的 KernelSU / APatch / Magisk 模块「Enco X4 LC3 Manager」。它通过伪造系统常驻游戏态让 LE Audio 成为活动设备，会同时影响其他蓝牙耳机和系统的游戏模式行为，新用户建议使用 Encore。

它仍然适合用来满足[首次设置](#首次设置)中的条件：WebUI 的「前置条件」清单可以一键开启双模共存、双连接偏好，并管理自定义白名单。与 Encore 同时使用时，请保持它的「全局 LC3」关闭。

<details>
<summary>旧方案用法与发布</summary>

在管理器中刷入 `enco_x4_lc3-<version>.zip` 并重启。之后可以用模块的「执行」按钮、WebUI 或命令行操作：

| 命令（`su -c x4lc3 …`） | 说明 |
| --- | --- |
| `on [包名] [--force]` / `off` / `toggle` | 开关全局 LC3 |
| `status [--json]` | 查看状态 |
| `coexist [on\|off]` | 双模共存开关（会重启蓝牙） |
| `pref [on\|off]` | 耳机的双连接偏好 |
| `list` / `add <包名>…` / `del <包名>…` / `clear` / `sync` | 自定义白名单 |
| `config get` / `config set <项> <值>` | `RESTORE_ON_BOOT`、`WATCHDOG`、`WATCH_INTERVAL`、`ANCHOR` |
| `watchdog start\|stop\|status` | 守护进程 |
| `log` / `diag` | 日志、诊断信息 |
| `reset` | 撤销全部改动 |

未挂载 `system/bin` 时使用 `sh /data/adb/modules/enco_x4_lc3/x4lc3.sh`。卸载时模块会自动还原它改过的设置。

```sh
./build.sh    # 输出 dist/enco_x4_lc3-<version>.zip
```

推送 `v*` 标签后，GitHub Actions（`.github/workflows/release.yaml`）会校验标签与 `module/module.prop` 的 `version` 一致、打包、以 `CHANGELOG.md` 对应条目创建 Release，最后更新 `main` 上的 `update.json`。不要手动修改 `update.json`。

</details>

## 免责声明

本项目与 OPPO / OnePlus 无关，仅供学习与研究。使用造成的任何问题由使用者自行承担。
