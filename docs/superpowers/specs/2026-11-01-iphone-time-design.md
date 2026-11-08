# iPhone 自动校时
用户明确要求直接使用 iPhone 时间自动校准，停止固件根因调查。实现范围：无线已认证 iAP2 DeviceTimeUpdate 0x4e0b；声明接收能力、严格解析 UTC Unix 秒及手机偏移；不将媒体 NTP 当作日历时间。

方案：系统时间优先经 AlarmManager 更新；确认 FF-5000/ac8227l 的设备允许使用已验证厂商临时 su 入口执行已核实的固定 AlarmManager Binder 校时命令（API27 transaction2），仅调用系统校时API，不直接写固件/启动文件/MCU。无权限或失败时，叠加时钟用进程内 iPhone UTC+elapsedRealtime 锚点（最长24小时）。校准不阻塞连接线程，异常报文、过期会话、重复更新不产生无限任务。系统时区不修改；叠加时钟按手机时区偏移显示。

替代方案：媒体NTP仅用于音视频时钟，缺少UTC保证；继续修固件已被用户取消。采用设备时间通知是用户选择的iPhone校时方案，权限与限流细节为实现决策。

协议参考：项目协议目录已有定义；公开实测日志 https://github.com/HaToan/carplay-wifi-extractor/blob/main/iap2.log 中1762923477与2025-11-12 11:57:57 UTC+7吻合，支持Unix纪元解释；本机需验证真实通知。无通知不猜测、不校准。

约束：固定kaiyue，不push；保留并发模型修改；车机自动时间保持关闭，GPS保持开启。默认不新增设置；所有操作留日志，不声称修复了固件开机时间覆盖。新APK交付递增code并与并发工作核对。
