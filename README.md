# Enco X4 LC3 Manager

在 OnePlus / OPPO（ColorOS）手机上一键开关 OPPO Enco X4 的**全局 LC3（LE Audio）**，并管理双模 LC3 白名单。KernelSU / APatch / Magisk 通用模块。

工作原理（全部在手机上以 root 本地执行）：

- **白名单**：把包名合并进 `/data/misc/bluedroid/bt_oplus_iot_list.conf` 的 `[dualmode_a2dp_lowlatency] add_list`。蓝牙启动时读取，改动需重启蓝牙。
- **全局开关**：写 `debug_gamemode_value/savegame`（必要时 `oplus_games_low_latency_state`）伪造常驻游戏态，所有声音走 LC3。切换无需重启蓝牙。
- 开关的前提是手机上**至少有一个已安装的白名单应用**（系统内置的或自定义的），模块会自动挑选。
- 另有两个前置条件，WebUI 的“前置条件”清单会逐项检查并提供一键修复（见下文“前置条件”）。前置条件不满足时模块**拒绝开启**，因为此时游戏态只会触发经典 A2DP 低延迟，耳机反而降为 SBC。

> [!WARNING]
> 非官方用法，风险自负。已在 OnePlus 16 `PYB110_17.0.1.201` 与 OnePlus 13 `PJZ110_17.0.0.103` + Enco X4 上验证。开启游戏态可能触发游戏模式的其他系统行为（性能策略、勿扰等）。

## 安装

```sh
./build.sh          # 生成 dist/enco_x4_lc3-<version>.zip
```

在管理器中刷入 zip 并重启。

| 管理器 | 执行按钮 | WebUI |
|---|---|---|
| KernelSU | ✅ | ✅ |
| APatch | ✅ | ✅ |
| Magisk 28+ | ✅ | 需借助 KsuWebUI / WebUI X |

## 使用

- **执行按钮**：切换全局 LC3 开/关，并显示切换后的状态。
- **WebUI**：开关按钮、状态（活动设备、游戏态、共存开关）、自定义白名单增删、重启蓝牙、设置、日志、撤销全部改动。
- **终端**（需挂载 `system/bin`，KernelSU 需要安装 metamodule；否则直接用 `sh /data/adb/modules/enco_x4_lc3/x4lc3.sh`）：

```sh
su -c x4lc3 toggle
su -c x4lc3 add com.miHoYo.GenshinImpact   # 加白名单并重启蓝牙
su -c x4lc3 status
su -c x4lc3 coexist on                     # 开启双模共存开关（前置条件）
su -c x4lc3 pref on                        # 开启耳机的双连接偏好（前置条件）
su -c x4lc3 help
```

### 设置

| 项 | 默认 | 说明 |
|---|---|---|
| 开机自动恢复 | 开 | 开机后保持上次的全局 LC3 状态 |
| 守护进程 | 关 | 游戏态被系统改回时自动恢复；监控 conf 被云控覆盖时重新合并 |
| 检查间隔 | 10 秒 | 守护进程轮询间隔 |
| 游戏态包名 | 自动 | 自动：自定义白名单中已安装的 → 内置白名单中已安装的 |

## 前置条件

| 条件 | 说明 | 修复 |
|---|---|---|
| 双模共存开关 | `persist.sys.bt.leacoexist`，代码中为 `getBoolean(..., false)`，蓝牙进程启动时读取。一加 16 默认 `true`；**一加 13（PJZ110）默认未设置，即关闭** | `x4lc3 coexist on`（会重启蓝牙） |
| 双连接偏好 | 每个耳机一项，`oplus_lea_dual_connection_preference_<sha256(MAC)>`，存于 `content://com.oplus.appplatform.settings/global`，须为 `1`。由欢律游戏模式的“LC3 低延迟”开关经耳机回传写入；蓝牙进程监听该键，改动即时生效 | 欢律 → 游戏模式 → LC3 低延迟，或 `x4lc3 pref on` |
| 白名单应用 | 至少安装一个双模白名单应用 | 白名单中添加一个已安装的应用 |

在一加 13 上的实测：两项都开启后，X4 建立 BR/EDR + LE 双连接（平时仍走 LHDC V5）；开启全局 LC3 后活动设备切到 `LE_AUDIO`，流配置为 `Two-OneChan-SnkAse-Lc3_48_2_Low_Latency`。

由模块修改的共存开关与双连接偏好会记录原值，`reset` 或卸载时还原；用户自己（如在欢律中）开启的不会被改动。

## 实现要点

- 数据在 `/data/adb/enco_x4_lc3/`（升级模块不丢失）：`whitelist`、`config`、`state`、`backup`（开启前的 settings 原值，关闭时还原）、`x4lc3.log`。
- **conf 合并而非覆盖**：已存在的云控 conf 会保存为 `base.conf`，模块只往 `add_list` 里合并自己的包名（并从 `delete_list` 中去掉）；云控再次改写时重新合并。清空白名单/卸载时还原原文件。
- `post-fs-data.sh` 在蓝牙启动前同步 conf，开机不需要额外重启蓝牙；`service.sh` 在开机完成后恢复状态。
- 卸载时还原 conf 与 settings，删除数据目录。

## 是否需要守护进程：如何自测

游戏助手/Athena 是否会重写游戏态尚未实测。在守护进程**关闭**时开启全局 LC3，然后分别：

1. 锁屏放置一段时间；
2. 打开并退出一个被识别为游戏的应用；
3. 重启手机（关闭“开机自动恢复”）。

每步后在 WebUI 看 `debug_gamemode_value` 是否还是 `1`（或 `su -c x4lc3 status`）。若会被改回，再打开守护进程，日志中的“守护: … 已恢复游戏态”记录可以看出被改回的频率。
