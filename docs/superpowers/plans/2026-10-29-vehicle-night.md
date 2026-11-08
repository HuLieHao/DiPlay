# Vehicle Night Mode Implementation Plan
> **For agentic workers:** Use superpowers:executing-plans inline.
**Goal:** 实现已确认的小灯模式及定时兜底。
**Architecture:** 厂商 Binder 只读适配器后台采样；现有控制器管理状态、日程与生命周期；设置显示来源。
**Tech Stack:** Kotlin / Android Binder / Robolectric.
**Spec:** ../specs/2026-10-29-vehicle-night.md
## Global Constraints
kaiyue 原地开发，保留其它改动，不推送；9语言；Android8支持；code64。
## Review Focus
false 不误判；停止后迟到结果；绑定失败和死亡；主线程不做 IPC；旧选择保留。
## Tasks
- [x] 控制器测试先覆盖小灯开关、异常兜底、恢复、停止回调，运行失败后实现。
- [x] 新建 VehicleIllumination.kt：显式绑定、只读 transaction5、后台单次读取初值、广播更新及注册失败低频兜底、解绑及迟到保护；添加 Binder 协议测试。
- [x] Host 接入；设置模式排序、能力过滤、来源说明及9语言；备份兼容。
- [x] 运行仓库完整 CI，打包 code64，记录周末实车验收步骤；不推送。

## 实施记录
- 小灯读取沿用前台控制器生命周期，离开 CarPlay 前台暂停，返回重读；无额外常驻服务。
- 小灯标签缩短，待验证提示移到说明；两个日夜入口采用已有统一样式并按内容高度展开，避免大字体裁切。
- 独立审查两轮无阻断问题；新增绑定失败重试和 stop 后迟到连接的适配器测试。

- 根据新增原始DEX证据切换广播驱动；正常停止周期查询。新增广播两方向、非法extra、旧receiver和事件先到的回归验证。

- 最终广播版完整CI通过（3491 tests，0 failures，0 errors，1 skipped），APK code64签名通过；未推送、未安装。
