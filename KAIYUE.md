# BuickPlay：后装安卓车机个人定制版

BuickPlay 是基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 的个人改造项目，用于在后装安卓车机上尝试有线或无线 CarPlay。当前版本 **0.2.18-buick.1**，安装版本码 **38**，基于上游 **0.2.18**，尚未合入上游 0.2.19。

**这不是别克凯越原装车机项目。** 当前车辆安装的是第三方后装安卓车机，其硬件和固件与凯越车型没有直接对应关系。BuickPlay 和版本后缀 `buick.1` 表示个人别克主题定制，`kaiyue` 是历史分支名；不代表别克官方产品、原装车机支持或仅限别克车型使用。

兼容性应按车机的 Android 版本、硬件、固件、蓝牙和 Wi-Fi 能力判断，不能仅按车辆品牌或车型判断。本项目尚未完成通用后装车机的广泛适配验证。

[版本说明](docs/RELEASE-NOTES-0.2.18-buick.1.md) · [源码标签](https://github.com/HuLieHao/DiPlay/tree/v0.2.18-buick.1) · [Releases](https://github.com/HuLieHao/DiPlay/releases) · [上游介绍](docs/DIPLAY-UPSTREAM-README.md)

## 改造内容

- 应用、iPhone 设备识别、AirPlay 广播和系统下拉通知名称统一为 **BuickPlay**。
- CarPlay 车辆按钮默认名称为“别克”，图标为个人提供的别克三盾图片。
- 默认制造商和型号为 BuickPlay；已保存值直接读取，不增加旧名称迁移。
- 非 BYD 车机在“车载热点”模式下可以找到热点自动化设置入口。实际开启仍取决于本地 ADB、权限和固件支持。
- 调试包名保持 `com.shihab.diplay.hudtest`，可覆盖之前的测试包并保留设置。

独立底部时钟尚未实现。比亚迪仪表、HUD、车辆数据等上游功能依赖对应平台，不能据此认为后装车机可用。

## 设备与验证状态

| 对象 | 信息 | 状态 |
|---|---|---|
| 计划测试的后装车机 | FFKJ FF-5000、AutoChips AC8227L、Android 8.1（API 27）、1024×600 | BuickPlay 待实车验证 |
| 调试设备 | Redmi M2003J15SC，Android 11 | 已验证无线 CarPlay、车辆按钮及系统通知名称 |
| 其他后装车机 | 硬件、固件和权限因设备而异 | 未验证，不承诺兼容 |

最低 Android 要求为 **7.1（API 25）**，Android 8.0/8.1 满足安装版本要求，但不等于对应固件已实测支持。2026-10-13 完整检查通过：2503 个测试，0 失败、1 跳过，lint 和 APK 构建任务完成。手机测试不能代替后装车机的实际验证。

## 当前后装车机系统

以下信息来自此前对当前车机的只读采集，描述的是这一台设备，不代表所有 8227L 车机，也不是凯越原装配置。

| 项目 | 已采集的信息 |
|---|---|
| 设备型号 | FFKJ / FF-5000，第三方后装安卓车机 |
| 系统基础 | Android 8.1.0，API 27；系统版本字段显示 `10`，但 API、构建指纹和系统应用信息均指向 Android 8.1 |
| 固件标识 | `FF_8227L_10`，ALPS 分支 `alps-mp-o1.mp5`；`userdebug / test-keys` 构建 |
| 主控与 CPU | AutoChips AC8227L，四核 ARM Cortex-A7 |
| 系统架构 | 32 位 ARM，ABI 为 `armeabi-v7a,armeabi`；未提供 64 位 Android ABI |
| 内存 | 内核报告约 941 MiB 可用总量，与标称 1GB RAM 相符 |
| 存储 | 约 14.45 GiB 物理容量，与标称 16GB 相符；用户数据分区约 12GB |
| 屏幕 | 当前记录为 1024×600 |
| 内核 | Linux `3.18.79`，`armv7l`，构建时间为 2019-10-28 |
| 安全补丁字段 | `2019-10-05`；这是系统声明值，未审计补丁完整性 |
| Wi-Fi | 已观察到 2.4GHz 联网；驱动为 `mt-wifi`，固件属性 `ro.wlan.mtk.wifi.5g=0`。准确芯片型号和物理 5GHz 能力未核实 |
| 蓝牙 | Android 原生蓝牙栈及厂商蓝牙界面均存在；蓝牙版本和 BuickPlay 的实际配对表现待确认 |
| 原厂固件手机互联 | 已验证固件自带 PhoneLink 的有线 CarPlay；这不等于 BuickPlay 已在该车机验证成功 |

系统满足 BuickPlay 的最低安装版本要求。实车调试应重点验证 32 位运行、视频解码、音频、蓝牙配对、热点和 ACC 恢复。约 1GB 内存也需要观察与车机后台应用同时运行时的稳定性；自启动和热点自动化能否工作仍取决于固件权限。当前没有充分证据确认 MCU 的准确型号或版本，不按“同为 8227L”推断其他固件可以互换。

## 安装与连接

应用安装在安卓车机上，iPhone 使用系统自带的 CarPlay。先确认车机允许安装 APK，并具备所选连接方式需要的 USB、蓝牙和 Wi-Fi 能力。

1. 安装适合自己设备的 APK。覆盖之前的测试版时，需要相同包名和签名。
2. 无线连接先开启双方蓝牙并配对，在应用中选择该 iPhone。
3. 选择连接模式。车载热点模式需填写实际热点信息，并确认热点已开启。
4. 先验证基础连接、声音和画面，再调整分辨率或开启固件相关选项。
5. iPhone 车辆列表若仍缓存旧名称，先重连；仍未更新时，在 iPhone 的 CarPlay 设置中忽略旧车辆，再重新配对。

**热点设置入口可见，不等于热点能自动开启。** 调试手机曾遇到 Android 隐藏接口限制；其他固件是否可用需实测。“随车启动”还受系统自启动、后台和悬浮窗权限影响，ACC 唤醒也不一定触发开机广播。

## APK 与构建

本仓库不保存 APK、认证私钥或 Android 签名密钥。APK 如需分发，使用 GitHub Release 附件，同时提供对应源码、构建说明和许可声明。

| 文件 | 用途 |
|---|---|
| `BuickPlay-0.2.18-buick.1-source-debug.apk` | 无认证材料的源码调试包，不能直接复现当前本地软件认证连接 |
| `BuickPlay-0.2.18-buick.1-debug.apk` | 本地自用独立包，显式注入外部认证材料；目前未公开上传 |
| `BuickPlay-0.2.18-buick.1-source.zip` | 版本标签对应源码，不含认证私钥 |

文件是否可下载，以 Releases 中实际提供的附件为准。公开分发前需单独确认车标和认证材料的权利，开源代码许可证不自动授予这些资产的分发权。

源码调试包：

```sh
./gradlew :mobile:assembleDebug
```

本地自用独立包：

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/local-runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

外部目录需要 `offline-mfi/identity.pk8` 和 `offline-mfi/certificate.p7b`，不要提交到 Git。详见 [构建说明](docs/BUILD.md)。板载 MFi 认证仍是 [待实车验证的候选方案](KAIYUE-I2C-MFI-FEASIBILITY.md)。

## 实车测试与反馈

在停车状态验证冷启动、ACC 恢复、自动重连、热点、通话/Siri、导航与音乐音频、倒车退出后的画面恢复，以及方向盘按键。只有对应设备实测通过后，才登记为支持设备。

### 本机实测结论（FF-5000 / AC8227L / Android 8.1）

- **开机全链（2026-10-14 验收）**：上电→自启→自动开热点→iPhone 关联→CarPlay 出图，全程零操作；
  应用侧链路 ~35s，其余为平台自身开机时长（当时钟未同步下限 ~82s，波动大）。
- **连接速度总开关 = 手机蓝牙（2026-10-16 定论）**：蓝牙连着车机时，iPhone 加入热点仅需数秒、
  全链 16.9s 出图（请求→对端 11.1s + 视频 5.8s）；蓝牙不在时 iOS 独自加入无外网热点需 1~2 分钟。
  **日常指南：上车保持手机蓝牙连着车机。**
- **实时调试基础设施（2026-10-16 建成）**：车机热点内 ADB（192.168.43.1:5555，CarPlay 运行中可用）；
  Mac 布局 Wi-Fi=车机热点 + iPhone USB 供网；车机常驻 `setsid logcat` 记录器。证据归档于
  `artifacts/car-live-debug-2026-10-16/` 与 `artifacts/boot-acceptance-2026-10-14/`。
- 已知怪癖：车机 Wi-Fi STA 与热点硬互斥（CarPlay 期间不在手机热点上）；车机无外网时钟不同步，
  日志时间戳多时钟域混见，分析只用域内间隔；`xcertplay` TRACE 偶发不落记录器文件（待查）。

反馈请提供车机品牌/型号、Android 和完整固件版本、BuickPlay 版本、iPhone/iOS、连接模式、复现步骤与发生时间，并通过应用“诊断”导出报告。分享前检查热点密码等敏感信息。车辆车型只作为使用场景补充，后装车机问题按硬件与固件记录。

## 分支维护与版本规则

| 分支 | 用途 |
|---|---|
| `main` | 上游镜像，不加入个人定制 |
| `kaiyue` | 个人定制主线；历史名称，不表示原装凯越车机适配 |

定制提交使用 `[kaiyue]` 前缀，一主题一提交。同步时评估上游发布标签，不直接覆盖上游开发中的 main：

```sh
git fetch upstream --tags
git log --oneline v0.2.18..v0.2.19
git checkout kaiyue
git merge v0.2.19
```

解决冲突、完成完整检查与目标设备验证后再推送。只需要单个修复时，可评估 cherry-pick。同步记录包含上游版本、冲突和实测结果。

版本格式为 `上游版本-buick.定制迭代号`。当前 `0.2.18-buick.1`；同基线后续发布为 `0.2.18-buick.2`，升级基线后可使用 `0.2.19-buick.1`。`buick` 只标记个人主题，每次 APK 发布还应独立递增 Android versionCode。

## 来源、许可证与品牌说明

接收器源自 [xcertplay](https://github.com/shilapi/xcertplay)，使用 [GPLv3](LICENSE)；界面和网站改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)，包含 [AGPLv3](docs/licenses/DiAuto-AGPL-3.0.txt) 代码。保留作者署名、许可证和 [第三方声明](docs/THIRD_PARTY_NOTICES.md)，分发修改版时提供对应源码。

Buick/别克及其车标、CarPlay 及其图标分别属于相关权利人，不属于本项目开源代码许可。此项目是个人非官方改造，与别克/通用、Apple 或上游作者没有官方关联或背书，也不是 Apple 认证产品。实验认证材料的普遍分发适用性仍未解决。

本中文说明保存在 `KAIYUE.md`，同步到 `README.md`，供 GitHub kaiyue 分支首页展示。上游介绍保存在 `docs/DIPLAY-UPSTREAM-README.md`。
