# code95：CarPlay 主屏日夜模式同步

版本 `0.2.21-buick.3-debug` / versionCode `95`；包名 `com.shihab.diplay.hudtest`。在 `kaiyue` 分支开发，已安装红米，未推送远程。

## 已修复

- 首次 `/info` 按当前宿主日夜决策声明 `nightMode`、主屏 `uiAppearanceMode` 和 `uiAppearanceSetting=1`，不再固定日间，也不沿用控制器启动时的旧值。
- 连接中发送 `setNightMode` 和 `uiAppearanceUpdate`，固定日间可以覆盖 iPhone 位置夜间对主屏外观的影响；切换不重连。
- 时钟仍使用同一宿主决策：浅色黑字、深色白字。
- 事件通道未准备好时保留最新决定；两条命令全部写出后才清除待发送状态。写入失败按原有逻辑关闭会话，新会话重新获取宿主状态。
- 根 `nightMode` 声明确认使 iPhone 的 `night mode supported` 从 NO 变为 YES。
- 删除临时探针及主屏不支持的独立地图命令。保留初始状态、命令类型、CSeq、响应和耗时诊断，写出/收到响应均不冒充实际画面已变色。
- 显式转发 VPN 层日夜查询，修正本次增量编译未为旧委托类生成新增方法导致首次声明为空的问题。

## 手机实测（2026-11-04）

设备为红米10X 4G、iPhone11/iOS18.7.7；CarPlay 外观保持自动。

| 场景 | 结果 |
| --- | --- |
| 白天同一连接 日→夜→日 | code93：侧栏、高德地图、时钟同步变化，无需重连 |
| iPhone 临时设21点、固定日间 | code95：侧栏浅色、时钟黑字；高德自动仍深色 |
| 21点同一连接切固定夜间 | code95：侧栏深色、时钟白字 |
| 固定夜间断开后重连 | code95：首次 `THEME_INITIAL night=true explicitAppearance=true`，深色侧栏及白字时钟 |
| 21点同一连接切回固定日间 | code95：侧栏浅色、时钟黑字，高德自动仍深色 |
| 21点固定日间，对照苹果地图 | code93：苹果地图浅色 |
| 21点将高德自身设置改日间 | code93：高德变浅；用户随后恢复高德自动 |

用户已恢复 iPhone 自动时间。红米当前保留固定日间，便于后续对照；未修改车机。

## 高德限制：未声称已解决

高德“自动”并不保证与主屏外观一致。证据同时包括：

1. iPhone CarKit `Resolved map style: Light`，位置夜间 YES、车机夜间 NO。
2. DashBoard `Updating map style to light for scene ... com.autonavi.amap`。
3. 同时苹果地图浅色、高德自动深色；高德手动日间变浅。
4. code95 补充夜间模式能力，iPhone 确认支持后，现象仍存在。

已定位到高德显示策略与主屏/CarPlay地图样式不一致这一边界；尚不能断言其内部具体算法，也没有验证能通过接收端协议强制覆盖高德的自动判断。实际使用仍可保留高德自动，接受它独立选择地图日夜；若要求强制浅色，需要高德自身选择日间。本次不能宣称“所有地图自动模式完全受车机控制”。

## 验证与安装包

- 核心测试覆盖：连接前最新值、实时宿主查询、重连初值、排队状态合并、同一通道切换、写入失败关闭会话、新会话获取状态、UI命令响应关联、夜间能力初值。
- 已观察初始状态、无效地图命令及缺少响应关联的测试 RED→GREEN。
- 独立只读评审未发现关键/重要缺陷；指出的“向已关闭会话注入新 socket”测试不真实，已改为验证会话关闭和新会话的状态获取。
- 最终完整 CI：BUILD SUCCESSFUL（5m5s），3563项测试、0失败、0错误、1跳过；shared/common/home 单测及 mobile/home/maphost lint、Debug构建通过。
- APK签名验证通过，minSdk24，安装的包名/版本核对为 code95。
- APK：`../artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code95.apk`（相对项目根的工作区路径）。
- SHA256：`01d0e20a1a0b0bc829ec6aeda183900c17cbec8805fd5c04567d66f230235f23`。
- 原始日志/截图：工作区 `artifacts/analysis/appearance-20261104/`，包含 `android-code95.log`、`iphone-code94-95-evidence.log`、`buick95-*.png`。

## 实车待验收

在车机验证跟随小灯：冷启动开/关灯、连接后开→关、夜间关灯与重连。红米没有真实小灯输入，本次不替代车机广播、Binder和系统休眠唤醒的验收。高德实际画面继续单独记录。
