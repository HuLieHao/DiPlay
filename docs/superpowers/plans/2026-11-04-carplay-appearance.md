# CarPlay 外观同步实施计划

> **For agentic workers:** Use superpowers:executing-plans to execute task-by-task in the current kaiyue checkout.

**Goal:** 固定日夜和有效小灯状态能控制 CarPlay 外观，首次连接和实时切换一致，时钟同步。
**Architecture:** 保留 CarPlayNightMode 的状态来源；使用已验证的初始化明确外观及实测通过的动态协议。动态协议未通过前不发布初始化强制外观。
**Tech Stack:** Kotlin、Android、AirPlay binary plist、iPhone CarKit 日志。
**Spec:** ../../THEME-DIAGNOSTICS.md，以及用户确认的场景对照表。

## Global Constraints
- 在 kaiyue 开发，不建 worktree、不推送、不覆盖其他模型修改。
- Android 8.1/API27 兼容；灯光变化不重连；新包 versionCode 至少92。
- 不把命令写出/HTTP成功等同于手机应用外观，不把CarKit地图解析值等同于高德画面。

## Review Focus
- 晚间关灯：位置夜间YES时仍按明确外观切换。
- 连接中快速切换：最终外观以最新状态为准。
- 首次连接与重连：不能短暂被固定初值锁住。
- 无有效小灯数据：保持既有时段兜底语义。
- 地图应用自身策略：实际画面与日志分别验证。

### Task 1：取得可验证的动态协议
- [ ] 从原生实现或可复现的手机处理日志确定type、参数、初始能力声明。
- [ ] 同一会话浅→深→浅，记录手机收到、CarKit解析与实际画面；不再重复已否定的名称。
- [ ] 独立验证地图更新，包含高德实际画面。

### Task 2：正式实现（Task 1通过后）
Files: shared/.../airplay/AirPlayInfoPlist.kt、AirPlaySession.kt；common/.../CarPlayHostActivity.kt、CarPlayNightMode.kt；对应单元测试。
- [ ] 用已取得的真实协议建立失败测试：初始值、更新顺序、状态覆盖、连接重建。
- [ ] 实现最小初始化与动态同步，保留发送诊断；清除全部临时探针。
- [ ] 单元测试验证有效小灯优先级和未知态时段兜底，时钟沿用同一明确外观决策。

### Task 3：构建与验收
- [ ] 执行AGENTS.md指定完整CI。
- [ ] 构建新编号独立调试包，保留配对设置并安装红米。
- [ ] 白天及模拟晚间各验收固定日→夜→日、首次与重连、界面/地图/时钟。
- [ ] 实车小灯开关列入待上机验收，不能用红米模拟代替实车结果。
- [ ] 更新结果与证据，不推送。

## Execution ledger (2026-11-04)
- Task 1: code93 same-session day→night→day verified by screenshots and CarKit logs. Dynamic uiAppearanceUpdate requires initial uiAppearanceMode numeric presence.
- Ruling: Main screen does not accept mapAppearanceUpdate (explicit iOS log); remove this command and its initial map declarations. iOS resolves main map style from UI/night state. At simulated21:00 Apple Maps is light while Gaode auto is dark despite Dashboard sending light to its scene. Gaode manual day turns it light. Do not claim host can override an app's own appearance policy; further capability check ongoing. Cost if wrong: Gaode remains inconsistent until its setting or implementation supports the host signal.
- Task 2: AppearanceSynchronizationTest initial/queued behavior RED (fixed initial day and extra unsupported map command) → GREEN. Includes live provider reconnect and partial-write retry checks. NightModeResponseTest UI response tracking RED→GREEN. Initial root nightMode presence RED→GREEN.
- No commit/push per user preference; all evidence retained locally.
- Full CI in progress; code94 focused tests and standalone build passed, installed for final capability test.

- Task 3: code95 final full CI passed (5m5s; 3563 tests, 0 failures/errors, 1 skipped). APK signed/installed/version95 verified. Live THEME_INITIAL false then true verified across reconnect; same-session night/day screenshots archived.
- Final review: independent read-only reviewer found no important/critical issue; minor unrealistic closed-session replacement test corrected. Additional live integration caught missing incremental VPN delegate method, fixed with explicit forwarding and verified bytecode + live handshake.
- Ruling: real vehicle illumination remains on-car acceptance; Redmi validates resolved-state transport but cannot validate actual CAN/Binder inputs. Cost if wrong: a vehicle-only input failure can remain despite correct transport.
- Scope status: host UI/clock fix implemented and verified; Gaode automatic forced-light requirement not achieved, limitation documented explicitly in code95 acceptance. No push.
