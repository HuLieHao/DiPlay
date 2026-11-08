# BuickPlay 0.2.21-buick.2-debug code64：车辆小灯日夜模式

## 本版范围
- 新增“跟随车辆小灯”（说明标注待实车验证）；保留按时间、固定日间/夜间、Android 系统。旧设置不覆盖。
- 小灯从 `com.ts.MainUI/com.ts.can.carinfo.CarInfoService` 的 AIDL `requestCarIllInfo` 读取：descriptor `com.ts.can.carinfo.ICarInfoService`、transaction 5。固件实现为 `Mcu.GetIll() == 1`。仅只读，不需要 root，不修改厂商配置，不调用 PhoneLink 会话。
- CarPlay 前台先注册 `broadcast_send_carinfo`，读取整数 extra `ILL_LIGHT`（0=关闭，1=开启）；随后后台查询一次初值。正常状态无周期 MCU 查询，随广播立即更新。Binder IPC 最多一个在途调用；3 秒读超时按时间兜底，绑定失败/失联按 10 秒间隔重试。只有广播注册失败才每 30 秒读取兜底。卡死的厂商调用不会阻塞主线程，也不会叠加创建读取线程。
- 小灯 false 是有效的日间状态，不能当作“未接线”。接口返回 false 但实际无灯线的问题，需要实车开关灯验证。
- 离开 CarPlay 前台停止读取；返回时重新获取。无连接页面的后台常驻轮询。
- 小灯模式下可设置兜底时间；时钟和 CarPlay 日夜信号使用同一决策。无独立时钟模式。
- 无光感设备隐藏环境光选项；如果原来已选环境光则保留该选择，避免升级静默改设置。
- 设置显示最近一次主机判定及来源，不声称手机已经确认或地图一定变色。
- 日夜变化无需重新连接。地图自行决定配色，CarPlay 外观应设为自动。

## 周末车机验收（停车时进行）
1. 安装 code64 调试包，保留正式版；调试应用名称 BuickPlay-debug。
2. 设置 → 显示 → CarPlay 日夜模式，选择“跟随车辆小灯”（说明标注待实车验证）；确认兜底时段。
3. iPhone CarPlay 外观选择自动，连接无线 CarPlay。
4. 关闭小灯，等待 3 秒；打开小灯，等待 3 秒；重复两轮，记录车机当地时间。
5. 检查 CarPlay 侧栏/系统界面和叠加时钟颜色。地图作为独立观察项，不能仅凭高德没变色判定信号失败。
6. 返回设置查看“最近一次判定”，再回到 CarPlay；验证重新读取正常、无需重新连接。
7. 保持导航或音乐，开关灯确认没有断连/音频卡顿。
8. 保存诊断报告，必要时通过手机网页下载 ZIP，保留整个会话。

日志关键字：`VEHICLE_ILL`（broadcast / mcu_initial / receiver_unavailable / binding / bind_unavailable / read_error / read_timeout / service_disconnected）、`NIGHT_RESOLUTION`（vehicle / vehicle_fallback_schedule）、已有 `THEME_REQUEST` / `setNightMode` 发送日志。

如果始终返回 false，不能判断是白天、未接 ILL、协议盒不输出还是其它固件问题；保持未验收状态，不默认启用。不要为验收改变系统权限或厂商文件。

## 本地验证
仓库要求的完整 CI 通过（4分53秒）：3491项测试、0失败、0错误、1跳过；三个应用的 Debug 构建与 lint 通过。适配器覆盖 SDK27/33。窄屏、宽屏及阿拉伯语大字体截图已检查。独立审查发现的“广播先到导致失去死亡监测”已补失败回归并修复，复核通过。实车广播接收与灯线语义仍未验收。

## 已核实的主动通知链路及实车验证
厂商源码及原始 DEX 双重核实：`MainUI.SetAdasLightState()` → `AmapAuto.SendCarInfordata()` → action `broadcast_send_carinfo`，整数 extra `ILL_LIGHT=Mcu.GetIll()`，两方向都会发送。MainUI 自身初始化 AmapAuto，不依赖 xfapp。`BROADCAST_ADAS_LIGHT_STATE` 实际是属性/日志名，不是此 action。
实车仍须验证：调试应用动态接收权限、0/1 与实际小灯对应关系、退出后重进能读取当前态。日志应出现 `VEHICLE_ILL broadcast state=true/false`，接着 `NIGHT_RESOLUTION` 和已有主题发送日志。只有 `mcu_initial` 而没有 broadcast 时不能视为事件验收通过。
正常事件驱动无周期查询；只有广播注册失败才启用低频查询。先注册再读取，广播优先于迟到的初始查询；旧页面的接收器、绑定回调和读取结果全部失效。

## 安装包
- 文件：`../artifacts/apk/BuickPlay-0.2.21-buick.2-debug-code64-20261029.apk`
- package：`com.shihab.diplay.hudtest`，versionName：`0.2.21-buick.2-debug`，versionCode：64，minSdk：24。
- SHA-256：`231f2844e6591ce044cdfe54d83669e4d6b18565ed279e38e9209dcc70d12efa`
- APK签名校验通过。未安装车机，未推送远程。
- 主动通知详细证据见 [FF5000-ILLUMINATION-NOTIFICATIONS.md](FF5000-ILLUMINATION-NOTIFICATIONS.md)。
