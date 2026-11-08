# 小灯驱动 CarPlay 日夜模式
用户已确认实施：新增车辆小灯、保留定时/固定日夜/Android 系统；无光感设备隐藏环境光（保留已有环境光选择以便迁移）；不覆盖旧选择。时钟跟随同一结果。
只读绑定 com.ts.MainUI/com.ts.can.carinfo.CarInfoService，descriptor com.ts.can.carinfo.ICarInfoService，transaction 5。先注册已核实的 broadcast_send_carinfo（ILL_LIGHT int 0/1），后台线程读取一次初值，此后广播更新；广播注册失败才每30秒兜底读取；false 有效，异常/未绑定按时间兜底，有限频率重绑。停止后不再读取，迟到回调丢弃。仅记录状态变化。设置显示最近结果及其来源，不宣称手机确认。
方案比较：标准 uiMode 不能代表厂商小灯；直接加载 MCU 库耦合且权限不明；采用已核实 Binder 接口。无需 root，不写系统，不调用厂商 CarPlay 服务。实车绑定权限与灯线状态仍待周末验收。

2026-10-29 用户追问后继续追踪 SendCarInfordata，确认实际广播链，修正第一版仅轮询的方案。原始DEX证据归档在 artifacts/analysis/vehicle-night-code64-20261029。
