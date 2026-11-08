# FF-5000 小灯主动通知核实（2026-10-29）

结论：存在小灯变化广播，实际 action 是 `broadcast_send_carinfo`，整数 extra 是 `ILL_LIGHT`。不是 `BROADCAST_ADAS_LIGHT_STATE`。本次同时核对 JADX 源码及原始 DEX 指令。

## 完整链路
1. `MainUI.DealMainUITaskNoCamera()` 调用 `SetAdasLightState()`。
2. 比较 `nILL` 与 `Mcu.GetIll()`，仅变化时进入；更新厂商皮肤。
3. `android.os.SystemProperties.set("forfan.adas.light_state", "on"/"off")`，打印 `BROADCAST_ADAS_LIGHT_STATE on/off` 日志。
4. 两个分支均调用 `AmapAuto.GetInstance().SendCarInfordata()`。
5. `SendCarInfordata()` 创建 Intent，action 为 `broadcast_send_carinfo`，加入 `ILL_LIGHT=Mcu.GetIll()`，调用 `Context.sendBroadcast(Intent)`；没有指定包、receiver 权限或仅本地广播。
6. AmapAuto 在 MainUI 内初始化，使用 MainUI 的 Application Context；不是依赖 xfapp 或高德 App 才发送。

源码（相对 headunit 工作目录）：
- `artifacts/analysis/connection-priority-20261025/mainui-java/sources/com/ts/main/common/MainUI.java`：867、2933–2947、3368。
- `artifacts/analysis/connection-priority-20261025/mainui-java/sources/com/ts/main/txz/AmapAuto.java`：55、620–631、692–718。
- 原始指令：`artifacts/analysis/vehicle-night-code64-20261029/mainui-illumination-bytecode.txt` 和 `carinfo-broadcast-bytecode.txt`。

## 需要区分
- `BROADCAST_ADAS_LIGHT_STATE` 是常量/日志名，其值 `forfan.adas.light_state` 在该路径被写为系统属性；仅看到名字和日志不足以证明 action。
- `com.ts.can.BROADCAST_CAN_INFO` 找到的发送点携带 CanType/CanSubType，属于车型配置通知，不能代替小灯变化事件。
- `…LEXUS_AIR`、`…BMW_L_MENU` 各有车型专用用途，不证明存在通用灯光广播。
- 标准 Android `uiMode` 与厂商皮肤分离；系统快照显示日间，不否定厂商小灯驱动皮肤。
- 固件已有 `broadcast_request_carinfo` 请求接收器，可触发 `SendCarInfordata()`。本版仍使用只读 Binder 读取初值，不额外发送请求广播。

## 扫描范围及局限
对 system-readable 档案内 1115 个候选文件（含143个APK/JAR，展开DEX及native成员）和vendor分片438个候选文件做字符串搜索，ADAS灯光字符串仅命中MainUI。该搜索不能证明不存在动态拼接的其它action，真正结论来自实际调用链和DEX指令。

## code64 采用方式
先动态注册正确 action，再后台查询一次初值；正常状态由广播更新，不周期读取 MCU。广播优先于迟到初值。保留服务绑定用于监测死亡；退出前台注销广播及解绑。服务异常按时间兜底并重绑；广播注册失败才使用30秒低频读取。
Android13及以上使用 RECEIVER_EXPORTED 接收厂商进程广播；只消费正确 action、存在且值为0/1的 ILL_LIGHT，不消费门状态等其它字段。

实车尚待验证：普通调试包能否接收、0/1是否随本车实际灯光变化、两方向和重进页面是否正常。无广播到达时不能仅凭初值正确宣布验收通过。
