# iPhone Time Implementation Plan
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
**Goal:** 连接iPhone自动校准车机时间，普通手机无权限时正确显示iPhone时间。
**Architecture:** shared纯解析/单调时钟锚点；Controller认证后转交，Android后台校时；Overlay读取锚点并订阅刷新。
**Tech Stack:** Kotlin / Android API24+ / iAP2 / JUnit4。
**Spec:** docs/superpowers/specs/2026-11-01-iphone-time-design.md
## Global Constraints
固定kaiyue，不push；root限于用户授权的校时。无网络时间替代、无媒体NTP墙钟假设，不改系统时区/固件/MCU。
## Review Focus
- 非法字段、重复字段、溢出/旧纪元不改时间。
- 已关闭控制器/旧generation不能更新锚点或执行root。
- 校时线程不阻塞握手，洪泛/失败有界。
- Redmi不尝试厂商su；FF5000严格型号+硬件检测。
- 无通知/权限失败有明确日志，不能声称已自动校时成功。
## Tasks
- [x] 解析和时钟锚点：先写有效Unix秒、时区、异常报文、elapsed延续/失效测试，观察红后实现。
- [x] 声明0x4e0b能力，认证后Controller收帧处理与generation隔离；覆盖能力声明测试。
- [x] Android异步校时与FF5000受限root回退、结果日志；Overlay使用手机锚点，保持24小时制。
- [x] 定向测试、独立review、本仓库完整CI；核对并发版本后生成APK并安装验收真实通知。
## Execution ledger
用户要求直接实施，免设计问答；系统/RTC校准在其明确授权范围，仅允许固定时间命令。另一模型正在导出vendor并可能修改code71，保留其工作，最终版本号再核对。

- 定向纯解析/时钟/所有权测试先红后绿；独立审查两项P2（失败会话继续校时、跨控制器旧锚点覆盖）已修复并复审无新增发现。
- Ruling：FF5000 root回退改走已从本机framework.jar核实的IAlarmManager transaction2（setTime long），而非date+hwclock。仅支持API27 + FF-5000/ac8227l，使用exec固定service命令，取消进程能直接结束命令；既保留系统的时间变化通知又不需要自行操作RTC。原厂接口证据保存在artifacts/analysis/clock-boot-20261101/IAlarmManager.java。新测试更新中，完整CI需在最终变更后重跑。

- 最终 Binder 命令测试先红后绿，独立静态复核无新增发现。用户确认另一模型已完成并允许安装；车机当前为 code71，新包递增 code72。

- code72 已安装，真实收到蓝牙及无线隧道0x4e0b通知；手机/系统差506ms，跳过写入，叠加时间正常。最终完整CI通过（3m43s）。有明显偏差时的root写入尚未实车触发，记录为后续验收项。
