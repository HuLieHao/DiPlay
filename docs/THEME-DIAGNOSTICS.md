# 小灯、CarPlay 外观与叠加时钟诊断

截至 code95，已修复主屏外观初始化和运行时同步，日志进入已有诊断报告，不依赖 root 抓包。以下历史实验保留用于追溯；当前结果以本节和 [code95 验收记录](ACCEPTANCE-0.2.21-buick.3-code95.md) 为准。

## 当前结论（2026-11-04，code95）

- `setNightMode(false)` 只是车机日间信号，iPhone 可将位置夜间结果与它合并，不能单独用于强制浅色。
- 主屏初始化声明 `uiAppearanceMode`（0浅/1深）和 `uiAppearanceSetting=1`（UserChoice），运行时发送 `uiAppearanceUpdate`，才能按 BuickPlay 已解析的日夜决策控制侧栏。字段并非手机回传的当前外观。
- `/info` 每次从宿主读取当前状态，并声明根 `nightMode`，防止首次/重连继续使用旧初值。显式转发查询回调，避免增量编译遗留委托类漏掉新增方法。
- 主屏不接受独立 `mapAppearanceUpdate`；已移除该命令及主屏地图独立能力声明。
- 本机测试：21点固定日间，侧栏/苹果地图浅色、时钟黑字；固定夜间变深、时钟白字；无需重连。夜间重连的首次声明为 night=true。
- 高德“自动”仍可能独立显示深色：同一21点场景，CarKit解析Light且DashBoard向高德场景发送Light，苹果地图浅色，高德仍深色；高德改“日间”后变浅。补充夜间模式能力后 iPhone 确认支持，但该现象仍存在。不能声称已强制控制高德自动外观，也不能把它等同于车机命令未送达。
- 实车小灯输入沿用已有决策；本次红米验证不能替代真实车机开关灯验收。

## 2026-11-04 最终晚间干净会话对照（尚未修复高德自动）

在用户重新设置21点并解锁后，保持固定日间、高德自动，断开重建 CarPlay 并重启高德。日志 `artifacts/analysis/appearance-20261104/iphone-clean21.log`：

- 21:46:42.561941，AMapiPhone[11690] 初始 UI 为 **Light / UserChoice**，主屏 isNightMode=NO。
- 21:46:42.562979，同进程 Initial system night mode=NO，initial location night mode=YES。
- 21:46:43.240529，内容样式 Light；重启后的 AMapiPhone[11764] 在21:46:45.759300再次收到 Light。
- 实际高德地图仍深色，CarPlay侧栏浅色、时钟黑字。截图 `amap-clean21-final.png`。

因此排除“仅仅因为旧会话初始 UI 是 Dark”或“仅重启/重连即可修复”。正常下午的旧位置夜间 YES 属于另一项残留现象；不能拿它解释全部晚间现象。现有证据确认协议样式已抵达高德应用场景，但尚未取得高德地图决策函数，不能把位置夜间相关性写成已经证明高德调用了某私有方法。

进一步只读核对 iOS 18.7.7：`setDisableLocationNightMode` 的调用受 `sessionStatusOptions` 位以及 `com.apple.CarPlayApp` 的 `kCarPlayAppDisableLocationNightMode` 偏好共同限制，属于手机内部开关，不是已验证的 AirPlay 接收端参数。另一条 VehicleDataProtocol 外观路径在当前工程未实现，不应凭方法名虚构报文。证据保存在 `ios18-handler-evidence/location-night-control.txt`。

用户已恢复自动时间，随后重建正常时间会话。此次未修改手机内部偏好、未修改产品代码、未生成新的 APK。当前无法诚实宣称已找到并验证能强制高德自动地图跟随小灯的接收端修复。

## 2026-11-04 13:38–13:40 进一步对照：旧会话的位置夜间状态

本节修正上一轮“下午仍深色，因此更偏向高德自身规则”的推断；不能仅凭时间显示为下午就排除 iOS 的位置夜间状态。

设备高德版本 17.01.0（17.01.0.1338）。保持高德自动、iPhone 自动时间、BuickPlay 固定日间：

| 操作与时间 | iPhone / 高德进程证据 | 实际地图 |
| --- | --- | --- |
| 13:38:42 仅重启高德，PID 11179→11590 | Initial system night mode: NO, initial location night mode: YES；初始 UI Dark；13:38:43 场景 contentStyle Light 并通知代理 | 仍深色 |
| 13:39:30 断开并重新连接 CarPlay，高德 PID 仍为11590 | Initial system night mode: NO, initial location night mode: NO；初始 UI Light | 变浅色 |
| 13:40:00 同会话切固定夜间 | 场景 contentStyle Dark | 地图、侧栏深色，时钟白字 |
| 13:40:26 同会话切回固定日间 | 场景 contentStyle Light | 地图、侧栏浅色，时钟黑字 |

证据：headunit 下 `artifacts/analysis/appearance-20261104/iphone-amap-restart.log`；截图 `amap-clean-day-reconnect.png`、`amap-clean-session-night.png`、`amap-clean-session-day.png`。

已确认：仅重启高德不足以清除旧会话位置夜间 YES；新建 CarPlay 会话后该值为 NO，自动地图正常日→夜→日。旧会话经历过手动21点测试，下午仍保留位置夜间值，因此之前下午现象不能证明纯粹是高德自主日落计算。

只读复核 iOS18.7.7 CarKit 的 `effectiveGlobalNightMode`：非 VehicleDataProtocol 分支把主屏 nightModeDictionary 值与 locationBasedNightMode 做 OR；而 scene.contentStyle 是另一条地图场景样式链。两条状态可以不一致。高德的表现与“同时受合并夜间状态影响”相符，但没有取得商用高德回调内部实现，尚不能断言它直接调用了哪一个私有接口。重连同时更新了初始外观和位置状态，尚不是单独改变位置状态的实验。

不应把重连作为每次开关小灯的产品方案，也不应把本次正常日间动态切换当作晚间强制日间已修复。下一层需要区分高德/其组件使用旧全局夜间状态、初始外观缓存，或自身自动地图规则；没有证据支持通过反复发送相同 uiAppearanceUpdate 来覆盖。

结束保持固定日间，地图浅色，已停止本轮采集；未修改产品代码或构建新包。

## 2026-11-04 补查：高德进程确实收到内容样式

只读检查 iPhone 11 / iOS 18.7.7（22H340）的 CarPlay.framework：

- `CPTemplateApplicationScene._mapStyle` 读取 `_templateSettings.mapStyle`；`_updateContentStyle` 更新 contentStyle 并通知应用代理。
- `CPSessionConfiguration._updateContentStyleWithScene:` 读取该 scene 的 contentStyle。没有在这条实现中发现绕开 scene、直接读取另一份全局夜间状态的分支。
- 本次按实际进程名 `AMapiPhone` 采集，补齐了过去只观察系统宿主日志的盲区。

实时证据 `artifacts/analysis/appearance-20261104/iphone-contentstyle-check.log`（相对 headunit 根目录）：

- 13:35:20.391514，`AMapiPhone(CarPlay)`：`Template scene content style updated to dark`，随后 `Informing delegate of style update`。
- 13:35:49.777078，同一进程：`Template scene content style updated to light`，随后同样通知代理。
- 切回固定日间后，侧栏浅色、叠加时钟黑字，但高德地图仍深色；13:36 再次截图仍相同。证据 `contentstyle-final-day.png`。所以不能仅用“21点日落规则”解释全部现象，也不能声称这次高德地图随日夜切换成功。
- 此前高德手动“日间”能令地图变浅，是已验证的应用侧控制入口；此次没有再修改手机时间或高德配置，结束恢复 BuickPlay 固定日间，并停止本次实时采集。

定位边界：已经证实样式传到高德进程内的 CarPlay 场景并通知代理；尚无高德自身地图渲染决策的日志或源码，不能断言具体是哪一个条件覆盖、缓存或忽略了该通知。也不能仅据框架的通知日志证明高德代理内部执行了地图换肤。

官方资料提供的旁证：

- [Apple contentStyle](https://developer.apple.com/documentation/carplay/cpsessionconfiguration/contentstyle) 明确内容样式独立于界面外观，供导航应用选择地图样式。
- [高德导航 SDK 日夜模式](https://lbs.amap.com/api/navigation-sdk-for-ios/guide/custom-ui/other-custom-ability) 提供按日出日落的自动模式。
- [高德官方 CarPlay 示例](https://github.com/amap-demo/iOS-HelloCarPlay) 的 MainViewController.m 和 DriveViewController.m 设置 `AMapNaviViewMapModeTypeDayNightAuto`。这是 SDK 示例，不是当前商用高德 App 源码，不能当作其内部根因的直接证明。

目前未找到 BuickPlay 可调用的、能强制覆盖高德地图自身决策的额外接口。不要添加未经验证的地图命令或周期重发，将其宣称为修复。本次仅补证据与文档，未改应用代码、未生成 APK。

## 待验证方案：UISync 目标外观（2026-11-04 深入追踪）

### 通道前置条件复核（自动时间恢复后）

**当前主屏不可直接采用 UISync 候选：已发现能力门槛并与本机会话日志交叉核对。**

- `supportsGaugeCluster`（DashBoard 0x22c611c68）读取 supportedFeatures 的 bit3，即掩码8。`CRFeatureAvailability.supportedCarPlayFeaturesForSession:`（CarKit 0x1ac158910）取会话 MFiCertificateSerialNumber，再经系统服务 `supportedCarPlayFeaturesForCertificateSerial:reply:` 查询，结果并非接收端 SETUP enabledFeatures 字符串列表。
- 实测 `iphone-clean21.log:11804` 报 `supportedFeaturesForSession: 3`，11941 报 `supportedCarPlayFeatures: 3`；3不含8。当天13:39日志也为3。因此本会话不能进入上述主屏 UISync 初始化分支；仅增加 uiSync/显示字典不能满足该条件。尚不推断更换任意认证芯片或升级iOS便能满足条件。
- 显示配置确切来源：CarKit 会话属性 EndpointInfo → displayPanels → displayPlugins；带插件构造路径设置 supportsDDPContent。普通 ScreenInfo 对应另一条逻辑屏幕构造路径。该发现用于界定候选的适用范围，不构成可交付修复。
- 14:10 当前截图 `amap-auto-restored-1410.png`：侧栏浅色、时钟黑字、Dashboard 高德浏览地图深色，无路线指引卡片。iPhone USB 仍可枚举，小米仍连接；这只是当前状态，不证明高德导航态行为。
- 下一项验收边界：之前21点截图是高德浏览地图，需要用户确认当时是否已进入路线导航。日志中的 `accessoryNavigationStartRouteGuidance` 是接收端订阅导航信息，不能据此认定用户已经开始路线导航。浏览地图与导航地图的自动样式是否一致尚未验证。

- 已核对当前 iOS 18.7.7 的双向信封：`DBUISyncChannel.sendPayload:forDisplayID:description:`（0x22c6cd2b0）写入 `displayUUID` 与 `data`；接收方法（0x22c6cd79c）按 `displayUUID` 查 observers，没有对应 observer 不会把数据交给 UISync session。内部 type18 消息不能直接当作普通 AirPlay command 发送。
- `_updateLayerMetadataService`（0x22c6a6850）仅在 layerMetadataServiceRequesters 非空、会话存在、内部 UISyncChannelDisabled 偏好未禁止时创建通道。请求者来自 `supportsLayerTracking`（调用点 0x22c69cd54），不能仅凭 SETUP 增加 uiSync 认定通道会建立。
- 主显示初始化中，UISync session 创建前检查 `supportsGaugeCluster`、`supportsDDPContent` 和已有 uisyncChannel（0x22c61f8ec–0x22c61f940）。另一显示初始化路径也检查 supportsDDPContent（0x22c60b3d0）。
- `supportsDDPContent` 读取对象偏移 0xa（0x1ac1775fc）；带 displayPluginDictionary 的物理屏幕构造路径明确写入 true（0x1ac175ec0）。普通逻辑屏幕构造方法 `initWithLogicalScreenDictionary:isPrimaryDisplay:` 没有该写入。因此需要继续追踪显示插件配置，不能把这个候选描述为已可用于当前单屏接收器的修复。
- 本轮未新增产品代码、未升级或安装 APK、未修改 iPhone 内部偏好。用户已恢复自动时间；不需要为了静态分析再次修改时间。

这是新的协议候选，尚未实机验证，不能称为高德修复。

- 已解析当前 iPhone 的 DashBoard：`DBUISyncSession.didReceiveData:` 读取 plist 字典，`type` 整数映射到 Swift 消息枚举。枚举 index11 为 `targetAppearanceChange`，线上的整数为 **18**。
- 对应处理函数从 `data` 字典读取 **appearanceMode**，接受 -1/0/1，映射为系统外观 -1/1/2。候选负载为 `{type:18, data:{appearanceMode:0}}`（浅色），深色为1。
- 回调属于 **DBUISyncSessionEventDelegate** 的 `didChangeAppearanceMode:forDisplayID:`，不是此前初步追踪猜测的 CRSSessionServiceDelegate。最终调用 `CARSession.handleDDPChangeAppearance:screenID:`，设置 targetAppearanceModes；该值存在时 effectiveGlobalNightMode 使用该显式值，不再 OR 位置夜间值。
- `DBUISyncChannel.initWithSession:` 创建 CARSessionChannel，channelType/clientType UUID 为 **07D9F906-8D64-4B54-A808-A20BCA2C51C2**，channelID 为 **7DED4F02-38A6-4CBA-8731-4B9BA40CB044**，withoutReply=true。仍需核对信封中的 displayID、主屏是否会创建此通道，以及握手要求。
- 本项目已有 type130 remote-control 数据通道基础（VideoInCar），带 X-Apple-StreamID 的 /command 可承载 plist；不能在未核对 transport/controlType 前直接套用。
- 下一步：确认 UISync 通道开启条件、信封和初始交互；仅做可撤回的调试验证。有效标准为晚间固定日间时，高德实际地图浅色、同连接日→夜→日正确，且普通连接/声音不回归。若通道只限特定屏幕或需未实现协议，必须记录限制。

只读反汇编证据：headunit `artifacts/analysis/appearance-20261104/ios18-handler-evidence/uisync-target-appearance.txt`。分析工具位于 `/tmp/buick-*`；未修改产品代码。

## 当前小灯策略（2026-11-04 调整）

小灯模式不再按时段兜底。启动尚未读取、接口不可用、读取失败或状态失效时，显示按关灯（日间）处理；只有明确的开灯状态才进入夜间。日志仍保留 `vehicleState=unknown`，判定来源为 `vehicle_default_day`，不把未知伪装成实际关灯。独立的定时切换模式仍保留，只有选择该模式时才显示时段设置。本文此前的 `vehicle_fallback_schedule` 记录属于调整前行为。

## 记录链路

### 路线导航补测（2026-11-04 14:14–14:16）

用户确认此前21点仅浏览地图，随后明确开始路线导航并保持解锁。新证据为 `iphone-navigation-comparison.log` 及 `buick-navigation-*.png`。

- 旧会话（已恢复自动时间但 location night mode 仍YES）：导航地图深色。14:14:45 切固定夜间，AMapiPhone收到contentStyle dark并通知delegate；侧栏、导航卡片、到达信息变深。14:15:15 切固定日间，收到contentStyle light并通知delegate；这些面板变浅，地图底色仍深。
- 14:15:54 重建CarPlay会话，AMapiPhone同一PID11764日志初始system night NO、location night NO。打开高德后路线导航仍在，14:16截图地图浅色。重连期间Dashboard一度显示苹果地图，未把该画面当作高德证据；最终截图已确认高德全屏导航。
- 这证明“恢复自动时间但旧会话仍保留位置夜间状态”的影响同样可出现在导航中；不能据此宣称夜间强制日间已修复。已请求用户在此干净导航会话改21点，等待结果后立即恢复自动时间。当前BuickPlay恢复固定日间，未改动产品代码。

最终补测结果：用户改21点后明确报告“导航地图变深”。`amap-navigation-21.png` 确认正在路线导航，地图深色、侧栏仍浅色、叠加时钟仍黑字。日志14:18:21及21:18:15出现 significant time change；这之后本次采集中未见新的深色contentStyle通知（不能将日志缺失绝对等同于没有任何内部事件）。结合此前AMapiPhone明确收到Light的证据，地图颜色不能仅由模板contentStyle解释。用户随后确认已恢复自动时间；停止本次syslog采集。

当前修复边界：code95解决接收端初值及CarPlay主UI显式外观同步；高德浏览和导航地图的自动配色均尚未验证能受接收端强制控制。不能交付“再发一次日间命令”或UISync空实现作为高德修复。高德自身手动日间是已验证的操作绕行方案，但不满足自动跟随小灯的原目标。后续需要高德提供/支持跟随车机的地图策略或可用控制入口，不能用SDK内部控件API冒充商业App外部接口。

### 高德外部设置入口复核（2026-11-04）

- 官方 iOS URI 文档 `https://lbs.amap.com/api/amap-mobile/guide/ios/navi` 的导航参数没有日夜模式；其中 `style` 指路线偏好，不是地图颜色，且文档说明 iOS 7.7.4 起不支持该参数。
- `https://lbs.amap.com/api/amap-mobile/guide/ios/map` 的 rootmap 入口只声明 sourceApplication；没有颜色设置。只能据此认定这些公开入口没有日夜控制参数，不能宣称不存在任何私有入口。
- 导航 SDK 的 setMapViewModeType 是嵌入 SDK 的应用调用自身地图控件的接口，不是外部控制已安装高德商业 App 的接口。不能在 Android BuickPlay 中添加这个方法来控制 iPhone 高德。
- 当前剩余验证：实际路线导航态的同连接日→夜→日与晚间固定日间。已询问用户此前是否开始路线导航，尚待答复；不能用接收端订阅导航信息的日志代替该确认。未修改设置或编造可用修复。

- `VEHICLE_ILL`：初始 Binder 查询／广播来源、开关或未知状态、单调时间、监听代次。忽略不含 `ILL_LIGHT` 的其他车况广播；携带非法小灯值时记录忽略原因。
- `NIGHT_RESOLUTION`：本地结果和来源，区分 `vehicle` 与 `vehicle_fallback_schedule`，附会话标识和预期时钟颜色。
- `THEME_CONTEXT`／`THEME_HOST_STATE`：所选模式、小灯开／关／未知、是否时段兜底、当时分钟、时段起止及计算值、本地结果。`scheduleNight=true` 仅说明时段计算结果；`fallback=false` 时不会覆盖明确的小灯关闭状态。
- `THEME_CLOCK`：实际调用时钟更新后的显示状态与配色来源，仅在显示／日夜状态改变时输出。
- `THEME_INITIAL`：本次 `/info` 的实时日夜值及是否声明明确外观。
- `THEME_REQUEST`：请求 CSeq、命令 type、夜间值、会话标识、对端及单调时间。
- `THEME_DELIVERY`：是否写出、是否仍排队、事件通道是否就绪。写出不代表对端已切换画面。
- `THEME_RESPONSE`：关联 CSeq、命令 type、夜间值、协议响应状态、响应体及耗时。
- `THEME_UNANSWERED`：事件通道结束时仍未收到响应的请求；最多追踪 8 条，超出时明确记录旧项退出追踪。没有响应不直接认定发送失败，也不证明手机忽略了请求。

所有外观判断日志标注 `actualAppearance=unknown`：当前未取得 iPhone 最终实际外观的上报值。

## 停车复现

1. 开启应用调试日志，选择“跟随车辆小灯”，保持 CarPlay 外观为自动。
2. 晚间小灯关闭时连接，记录画面深浅及新增时钟颜色。
3. 开灯，等待约 5 秒，再关灯并等待约 5 秒，记录每个动作的时间和实际画面。
4. 保存／手机下载诊断报告；若详细 root 采集正在运行，先停止采集。
5. 分析以上日志链。响应成功不能替代现场画面确认。若没有请求响应，报告这一事实，不把本地结果当作 iPhone 状态。

源码变更后需重新构建安装才生效；旧 code78 不含本次新增日志。

## 晚间关灯的预期判读

例如 `minuteOfDay=1200 scheduleNight=true vehicleState=off fallback=false resolvedNight=false clockColor=black`：即使时段判定是夜间，小灯明确关闭仍覆盖时段，不是在使用兜底。后续应看到 `THEME_REQUEST night=false` 及 `THEME_DELIVERY written=true` 或明确的排队／写入失败。

若现场画面仍深色：

- 没有写出：查事件通道准备和排队。
- 返回非成功状态：查命令响应及上下文。
- 已写出、没有响应：只能标注未观察到响应；不可认定已切换。
- 返回成功：说明请求得到协议响应，仍不等于最终外观被强制设为浅色。


## 2026-11-04 iPhone 实测：位置夜间判断覆盖车机信号

设备：iPhone 11 / iOS 18.7.7，红米调试端，CarPlay 外观 Automatic。

- clean code81 白天：`setNightMode(true)` 在手机端解析为 Dark；`false` 解析为 Light；不必重新连接。
- iPhone 临时改为同日21点：21:06:05.003 的 CarPlay/CarKit 日志显示 `location night mode: YES, display night mode: NO`，UI 和 map 最终均为 Dark。日间信号已被接收，但不等于强制浅色。
- 恢复自动时间、手动把 iPhone 自身外观设浅色后，10:10:27 仍是位置夜间 YES、车机夜间 NO、最终 Dark。手机自身外观与 CarPlay 位置夜间状态不能混为一谈。
- 10:13:11 手机重新计算位置夜间为 NO，日志给出当地日出06:34:02、日落17:20:35并调度下一次变化。恢复时钟没有立即清除之前的位置夜间状态。
- 因此，叠加时钟只依据本地 `resolvedNight` 配色可能与手机最终画面不同步。

证据：工作区 `artifacts/analysis/appearance-20261104/confirmed-night-override.log`、`iphone-history.logarchive`、`iphone-restored-live.log`。

### 历史：code87–91 修复路径验证（已由 code95 结论替代）

code87/88 是临时协议探针，不是正式候选版本：

- code87 仅发送 `uiAppearanceUpdate`，手机日志为 `Unhandled command ... forwarding to clients`，未观察到外观管理器应用它。不能以写入成功或HTTP成功认定生效。
- code88 在 display 初始化中增加 `uiAppearanceMode=0, uiAppearanceSetting=1`。手机解析为 ui appearance value=1（Light）、setting=UserChoice；证明初始化字段被识别。注意线上的 mode=0 与手机日志的内部 style=1 是不同枚举。
- 待验证：UserChoice 在位置夜间 YES 时是否保持浅色；正确的运行时 UI/map 更新命令；浅→深→浅无需重连；首次/重连初始值一致；时钟同步。
- 不采用重连模拟小灯变化，不把估算日落时间当成 iPhone 最终外观，也不将临时协议探针带入正式版。

### 历史：code88–91 实测与当时结论

- code88 的晚间对照通过：用户确认侧栏浅色、高德地图深色；iPhone 21:17:03 日志显示 UI 为 Light、UserChoice、location night YES。初始化 UserChoice 可以覆盖位置夜间判断。
- 同时 CarKit 的 resolved map style 为 Light，而现场高德为深色；必须将地图 App 的实际画面与系统样式分开验收，不应将 CarKit 解析结果等同于高德已变色。
- UserChoice 初始化浅色还会覆盖后续 setNightMode(true)，因此不能只添加初始化字段发布，否则小灯开启仍可能保持浅色。
- 临时 code87–91 探针确认：`uiAppearanceUpdate`、`updateUIAppearance`、`uiAppearance`、`appearanceModeUpdate`、`updateAppearance`、`uiAppearanceModeUpdate`、`UIAppearanceUpdate` 均被手机记作 Unhandled command，转发 UnknownRemoteEvent，未观察到外观管理器应用新值。该结果不代表 iPhone 不支持动态外观，只能说明当前试验的调用形式不可用。
- 方向：连接初始化时声明明确的UI/map外观，运行时同步变更，时钟跟随同一结果。尚未确认正确动态命令与高德实际响应，完整修复未完成。
- 不采用灯光变化时重连；不凭手机系统深浅色估算CarPlay最终样式。
- 临时源码探针已移除，保留已分配 versionCode=91 防止后续编号重复；手机恢复clean code81。probe APK仅供历史诊断，不得作为发布包。
- 继续定位应优先获取并静态核对包含UIAppearanceUpdate/MapAppearanceUpdate封装的原生SDK实现，确认线上type、params和能力协商，而非继续枚举名称。

### 原生实现核对补充

2026-11-04 后续调查：
- 只读搜索本地车机档案原生库，没有独立 ui/map appearance 更新接口字符串。
- 公开 Carlinkit 2020 程序使用改过签名的 UPX：魔数0x22552255替换回UPX签名后可离线解包（仅本地分析副本，未执行固件）。2024公开Decrypted版本直接可读。两者均只有setNightMode，没有所需独立外观接口，不能作为动态命令格式依据。
- Mac当前系统AirPlay框架不包含上述CarPlay接口；只读缓存检索未取得命令格式。公开iOS二进制差异文件亦未提供对应未变化的处理实现。
- 本轮没有新增正式外观实现或安装新包；小米仍为code81，源码编号91仍保留。实施计划见superpowers/plans/2026-11-04-carplay-appearance.md。
- 尚缺的证据具体是：含AirPlayReceiverSessionUIAppearanceUpdate/MapAppearanceUpdate的原生实现（例如参考资料提到的450.14.2），或正常浅→深→浅控制时的解密命令记录。现有setNightMode原生实现无法填补这项缺口。


## 2026-11-04：从当前 iPhone 的 iOS 18.7.7 实现确认接收条件

通过已信任 USB 的只读开发服务读取当前系统二进制，确认 `uiAppearanceUpdate` / `mapAppearanceUpdate` 是真实命令，参数为 `uuid`、`appearanceMode`、`appearanceSetting`。此前未观察到应用外观，不能据此判定命令不受支持。

CarKit 在处理动态 UI 命令前检查 `supportsAppearanceMode`；该标记由屏幕初始化字典中数值型 `uiAppearanceMode` 的存在决定。地图对应检查 `supportsMapAppearanceMode`，依赖 `mapAppearanceMode` 和屏幕类型。初始化外观管理器同时读取对应的 `*AppearanceSetting`。缺少初始能力声明时，只发送动态命令会被接收端忽略。

code93 仅用于验证完整声明加动态更新，初始值暂为日间，尚不能作为最终修复发布。待完成同一会话日→夜→日、模拟晚间、高德实际画面的验收后，再将初值接入当前设置并补齐正式实现和测试。原始证据在工作区 `artifacts/analysis/appearance-20261104`，Apple 二进制仅本地分析，不纳入仓库。
