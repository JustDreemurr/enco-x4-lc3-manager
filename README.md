# Enco X4 LC3 Manager

一键开关 OPPO Enco X4 的**全局 LC3（LE Audio）**。适用于 ColorOS / OxygenOS 手机，KernelSU、APatch、Magisk 通用。

在 OPLUS 系统上，Enco X4 平时走经典蓝牙（LHDC / AAC），只有在白名单游戏运行时才会切到 LC3。本模块可以让 LC3 随时常驻，所有应用的声音都走 LC3 低延迟；也可以把任意应用加入白名单，让它们打开时自动切到 LC3。

> [!WARNING]
> 非官方用法，风险自负。本模块会修改系统设置与蓝牙配置，但不修改任何系统 APK，所有改动都可以撤销。

## 功能

- **全局 LC3 开关**：在管理器里点“执行”按钮，或在 WebUI 里点开关即可切换。切换不需要重启蓝牙。
- **自定义白名单**：任意应用都可以加入双模 LC3 白名单。
- **前置条件检查**：逐项检查开启 LC3 所需的系统条件，未满足的可以一键修复；条件不满足时拒绝开启，避免耳机反而降为 SBC。
- **实时状态**：显示当前音频走的是 LE Audio 还是经典蓝牙，以及实际编码（如 `Lc3_48_2_Low_Latency` / `LHDC V5`）。
- **开机自动恢复**：重启后保持上次的开关状态。
- **守护进程**（可选）：游戏态被系统改回时自动恢复；前置条件失效（如耳机重连）时暂停游戏态以免降为 SBC，恢复后自动重新开启；云控覆盖白名单配置后自动重新合并。
- **完整撤销**：一键还原模块做过的所有改动，卸载时也会自动还原。

## 兼容性

| 机型 | 固件 | 状态 |
| --- | --- | --- |
| OnePlus 13（PJZ110） | `PJZ110_17.0.0.103` | ✅ 已验证（需先开启两个前置条件） |
| OnePlus 16（PYB110） | `PYB110_17.0.1.201` | ✅ 原理已验证 |

其他 ColorOS / OxygenOS 机型理论上可用，未测试。遇到问题请先看[常见问题](#常见问题)，并附上[诊断信息](#诊断信息)。

| 管理器 | 执行按钮 | WebUI |
| --- | --- | --- |
| KernelSU | ✅ | ✅ |
| APatch | ✅ | ✅ |
| Magisk 28+ | ✅ | 需借助 KsuWebUI / WebUI X |

## 安装

1. 下载或[自行构建](#构建)模块 zip；
2. 在 KernelSU / APatch / Magisk 中刷入；
3. 重启手机。

从 v1.2.1 起，后续版本可以直接在管理器中检查并安装更新。v1.2.0 及更早的版本需要手动刷入一次 v1.2.1 或更新的版本。

## 首次设置

打开模块的 WebUI，状态卡片下方有**“前置条件”**清单，三项都打勾后才能开启全局 LC3：

| 前置条件 | 说明 | 如何满足 |
| --- | --- | --- |
| 双模共存开关 | 系统属性 `persist.sys.bt.leacoexist`。部分机型（如一加 13）出厂未开启 | 点“开启”（会重启蓝牙，耳机短暂断开） |
| 双连接偏好 | X4 的一项设置，关闭时系统不会建立 LE Audio 连接 | 在 WebUI 中点“开启”，或在**耳机功能 → 游戏模式**中打开 LC3 低延迟，两者等效、会同步显示 |
| 白名单应用 | 手机上至少安装一个双模白名单应用 | 已安装系统内置白名单中的游戏（如王者荣耀、和平精英）即可；否则在“自定义白名单”中添加任意一个已安装的应用 |

全部满足后，X4 会同时建立经典蓝牙和 LE Audio 两条连接。平时仍然走 LHDC，开启全局 LC3 后切到 LC3。

## 使用

### 执行按钮

在管理器的模块列表中点击本模块的“执行”按钮，即可切换全局 LC3 开/关，并显示切换后的音频状态。

### WebUI

| 区域 | 内容 |
| --- | --- |
| 状态 | 全局 LC3 开关、当前音频与编码、前置条件清单、游戏态参数、最近的活动设备切换记录 |
| 自定义白名单 | 添加 / 删除应用（可从已安装应用中选择），显示已安装的系统内置白名单应用 |
| 设置 | 开机自动恢复、守护进程、检查间隔、游戏态包名 |
| 工具 | 重启蓝牙、查看日志、收集诊断信息、撤销全部改动 |

修改白名单后需要重启蓝牙才会生效，WebUI 会给出提示和按钮。

### 命令行

需要 root。如果管理器挂载了 `system/bin`（Magisk、APatch，或安装了 metamodule 的 KernelSU），可以直接使用 `x4lc3`；否则使用完整路径 `sh /data/adb/modules/enco_x4_lc3/x4lc3.sh`。

```sh
su -c x4lc3 toggle                          # 切换全局 LC3
su -c x4lc3 status                          # 查看状态
su -c x4lc3 add com.miHoYo.GenshinImpact    # 加入白名单并重启蓝牙
```

<details>
<summary>完整命令</summary>

| 命令 | 说明 |
| --- | --- |
| `on [包名] [--force]` | 开启全局 LC3。省略包名时自动选择已安装的白名单应用；前置条件未满足时拒绝开启，`--force` 强制 |
| `off` | 关闭，切回经典蓝牙 |
| `toggle` | 切换 |
| `status [--json]` | 查看状态 |
| `coexist [on\|off]` | 查看 / 开启 / 还原双模共存开关（改动会重启蓝牙） |
| `pref [on\|off]` | 查看 / 开启 / 关闭耳机的双连接偏好（与耳机功能中的 LC3 低延迟开关等效） |
| `list` | 列出自定义白名单 |
| `add <包名>...` | 添加到白名单 |
| `del <包名>...` | 从白名单删除 |
| `clear` | 清空白名单 |
| `sync` | 按白名单重新合并配置文件 |
| `restart-bt` | 重启蓝牙 |
| `config get` | 查看设置 |
| `config set <项> <值>` | 修改设置：`RESTORE_ON_BOOT=0\|1`、`WATCHDOG=0\|1`、`WATCH_INTERVAL=3-600`、`ANCHOR=<包名\|空>` |
| `watchdog start\|stop\|status` | 控制守护进程 |
| `pkgs` | 列出已安装的第三方应用 |
| `log` | 查看日志 |
| `diag` | 收集诊断信息 |
| `reset` | 撤销全部改动 |

`add` / `del` / `clear` / `sync` / `reset` 后加 `--no-restart` 可以跳过自动重启蓝牙。

</details>

### 设置项

| 项 | 默认 | 说明 |
| --- | --- | --- |
| 开机自动恢复 | 开 | 开机后恢复上次的全局 LC3 状态 |
| 守护进程 | 关 | 游戏态被系统改回时自动恢复；前置条件失效时暂停游戏态、恢复后重新开启；监控白名单配置是否被云控覆盖 |
| 检查间隔 | 10 秒 | 守护进程的轮询间隔 |
| 游戏态包名 | 自动 | 开启全局 LC3 时使用的白名单应用。自动：自定义白名单中已安装的 → 系统内置白名单中已安装的 |

## 常见问题

**开启后没有变化，耳机还变成了 SBC**

说明双模共存开关没开：系统把游戏态当成了经典蓝牙的低延迟模式。到 WebUI 的“前置条件”中开启双模共存开关。新版模块在这种情况下会直接拒绝开启。

**耳机重连后回退到 SBC，要手动开启双连接偏好、再关开全局 LC3 才恢复**

在部分机型上（目前报告于 realme RMX5010），耳机重连后双连接偏好会被改回 0，系统随即断开 LE Audio；此时游戏态还开着，就会走经典蓝牙低延迟模式，降为 SBC。是什么改回了这个值还在排查中（[issue #1](https://github.com/JustDreemurr/enco-x4-lc3-manager/issues/1)）。

建议打开守护进程：前置条件失效时它会暂停游戏态，耳机保持 LHDC 而不是 SBC；双连接偏好重新开启后自动恢复全局 LC3。守护进程还会在日志中记录双连接偏好的每次变化，遇到这个问题时请附上[诊断信息](#诊断信息)。

**前置条件都满足了，但还是没有切到 LC3**

- 确认 X4 已连接，且 WebUI 中“当前音频”的设备是 X4；
- 省电模式下系统不会建立 LE Audio 连接，请关闭省电模式；
- 修改过白名单的话，确认已经重启蓝牙；
- 收集[诊断信息](#诊断信息)排查。

**用一段时间后 LC3 自己断了**

游戏助手可能会改回游戏态，例如打开并退出一个游戏之后。可以在设置中打开守护进程，日志中会记录每一次恢复。如果同时出现了 SBC，见上一条。

**开启全局 LC3 有什么副作用？**

模块通过伪造“游戏中”状态来触发 LC3，可能同时触发游戏模式的其他系统行为（性能策略、勿扰等）。不需要时关掉即可。

**系统更新后失效了**

OTA 或云控更新可能覆盖白名单配置、重置相关设置。重启后在 WebUI 中检查前置条件即可；开启守护进程可以自动重新合并配置。

### 诊断信息

WebUI → 工具 → **收集诊断信息**，或执行 `su -c x4lc3 diag`。结果保存在 `/sdcard/Download/x4lc3-diag-*.txt`，其中蓝牙 MAC 地址只保留后两段。

## 撤销与卸载

- **撤销全部改动**：WebUI → 工具 → 撤销全部改动，或执行 `su -c x4lc3 reset`。会关闭全局 LC3、清空自定义白名单，并把模块修改过的共存开关、双连接偏好和白名单配置还原为原值。
- **卸载**：在管理器中卸载并重启，模块会自动完成同样的还原。

模块只还原**自己改过**的内容。你在耳机功能中手动打开的双连接偏好，或在模块之外手动设置的共存开关，不会被改回。要手动关闭共存开关：

```sh
su -c 'setprop persist.sys.bt.leacoexist false && svc bluetooth disable && sleep 3 && svc bluetooth enable'
```

## 工作原理

Enco X4 会向手机上报“支持 LE Audio 共存”，系统因此按 **经典蓝牙 + LE Audio 双模** 处理它：平时让经典蓝牙（A2DP）作为活动设备；只有当“处于游戏态且前台应用在双模白名单内”时，才让 LE Audio 成为活动设备。

建立双连接需要同时满足：

1. 双模共存开关 `persist.sys.bt.leacoexist=true`（蓝牙进程启动时读取，默认 `false`）；
2. 耳机的双连接偏好为 `1`：`oplus_lea_dual_connection_preference_<sha256(MAC)>`，存于 `content://com.oplus.appplatform.settings/global`，蓝牙进程监听该键；
3. 手机上至少安装了一个双模白名单应用；
4. 未开启省电模式，经典蓝牙的 A2DP / HFP 均为允许连接。

在此基础上，模块做两件事：

- **全局开关**：写入 `debug_gamemode_savegame=<白名单应用>`、`debug_gamemode_value=1`（需要时还写 `oplus_games_low_latency_state=<包名>:1`），伪造常驻游戏态，LE Audio 随即成为活动设备。开启前会备份这些设置的原值，关闭时还原。
- **白名单**：双模白名单 = 系统内置列表 + `/data/misc/bluedroid/bt_oplus_iot_list.conf` 中 `[dualmode_a2dp_lowlatency]` 段的 `add_list` − `delete_list`。模块把自定义包名**合并**进该文件（不覆盖云控内容），蓝牙启动时读取。

<details>
<summary>实现细节</summary>

- 所有逻辑都在 `module/x4lc3.sh`，执行按钮、开机脚本和 WebUI 都调用它。
- 数据目录 `/data/adb/enco_x4_lc3/`，升级模块时保留：
  - `whitelist`：自定义白名单
  - `config`：设置项
  - `state`：开关状态
  - `backup`：开启前备份的游戏态设置
  - `base.conf` / `applied.conf`：云控原始配置 / 模块写入的配置
  - `coexist_orig` / `pref_set`：模块修改共存开关、双连接偏好前的原值
  - `x4lc3.log`：日志
- `post-fs-data.sh` 在蓝牙启动前同步白名单配置，开机不需要额外重启蓝牙；`service.sh` 在开机完成后检查配置是否被覆盖，并恢复开关状态。
- 当前音频与编码读取自 `dumpsys bluetooth_manager` 的 `BluetoothActiveDeviceManager` 段。

</details>

## 构建

```sh
./build.sh    # 输出 dist/enco_x4_lc3-<version>.zip
```

### 发布新版本

发布由 GitHub Actions（`.github/workflows/release.yaml`）自动完成：

1. 修改 `module/module.prop` 的 `version`（如 `v1.3.0`）和 `versionCode`（递增）；
2. 在 `CHANGELOG.md` 顶部添加 `## v1.3.0` 条目，写明更新内容；
3. 提交并推送到 `main`；
4. 打标签并推送：`git tag -a v1.3.0 -m v1.3.0 && git push origin v1.3.0`。

工作流会校验标签与 `version` 一致、检查脚本语法、打包、以 `CHANGELOG.md` 中对应条目作为说明创建 Release，**最后**才把新的 `update.json` 提交到 `main`。管理器通过 `module.prop` 中的 `updateJson` 读取它来检查更新，因此不会出现“提示有更新但 zip 还不能下载”的情况。不要手动修改或提交 `update.json`。

目录结构：

```
module/
├── module.prop
├── customize.sh       # 安装
├── post-fs-data.sh    # 蓝牙启动前同步白名单配置
├── service.sh         # 开机恢复
├── action.sh          # 执行按钮
├── uninstall.sh       # 卸载还原
├── x4lc3.sh           # 核心脚本
├── system/bin/x4lc3   # 命令行入口
└── webroot/           # WebUI
```

## 免责声明

本项目与 OPPO / OnePlus 无关，仅供学习与研究。使用本模块造成的任何问题由使用者自行承担。
