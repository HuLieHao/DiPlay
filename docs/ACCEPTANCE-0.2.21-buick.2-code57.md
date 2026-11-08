# BuickPlay 0.2.21-buick.2-debug code57：热点分析日志

## 范围

仅补充连接等待期的被动日志，不调整热点、频段、信道、地址族、扫描、重试或连接页面。不执行 root、shell 网络命令或主动联网探测。原有详细原文保存策略不变。

## 新增证据

- 请求侧：热点模式、Wi-Fi Direct 专用信道配置、自动启用配置、generation 与单调时间。
- 提供方结果：SSID、发布地址、地址族及回退偏好；频段/信道/频率明确标为 backend_reported，未独立验证射频实际运行值。
- 设备：manufacturer/model/device、SDK、Android release、Build.DISPLAY，区分红米与 Android 8.1 车机。
- 热点接口：实际网卡地址和前缀、MTU、IPv4/IPv6 数量及 IPv6 scope。
- 安卓 STA：Wi-Fi 开关、networkId、supplicant 状态、SSID/BSSID、频率、RSSI、link speed。标注 station_only，不能用于证明热点频段。
- 安卓网络：默认网络、Wi-Fi/蜂窝/VPN 标志、系统联网验证、接口/IP/路由/DNS；明确不是 iPhone 互联网检测。最多 8 个网络。
- ARP：被动读取 /proc/net/arp，最多 16KiB 字符，缓存不代表当前关联。读取失败记录 failureClass 和 message，不猜测手机是否已加入热点。
- 独立计时：首次认证完成、首次发送热点配置、首次 TCP 距热点配置及启动请求的耗时；重复发送不重置首个节点。
- 原每快照只保留 4 行，增至最多 16 行；超出明确记录 omittedLines。结束快照标明缓存及 ageMs。

采样复用既有 10 秒启动观察线程；会话成功或控制器关闭即停止。无需手动点击采集才有这些信息；更完整的系统/厂商证据仍可由手动网络采集补充。

## 页面讨论（未修改）

左侧保留连接步骤与耗时。右侧拟精简为热点状态、iPhone 网络通道、最近进展及等待时间；频段、信道、发布地址和握手时间线放在“连接详情”。固定“互联网未检测”不占主区域。页面调整另行处理。

## 测试

新增首节点计时、8 行快照保留、Android 8.1/13 环境信息及权限失败降级测试；定向测试通过。完整 CI 和手机结果见后续追加。

计时语义：authenticatedAtMs/firstWifiConfigAtMs 相对于本次 WirelessStartupDiagnostics 创建（热点准备之后），不是点击连接的总耗时；firstTcpAfterWifiConfigMs 是从首次热点参数发送到接收 TCP。完整连接总耗时仍用既有连接步骤及首帧事件核对。

完整 CI：BUILD SUCCESSFUL，4m53s，3470 tests / 0 failures / 0 errors / 1 skipped。APK 已覆盖安装红米。
APK：`../artifacts/apk/BuickPlay-0.2.21-buick.2-debug-code57-20261027.apk`。
SHA256：`19d0ac6a1e979c95401ee2fe36bbb3f277ed55570212626bad6e13ab6479d641`。

实机日志验证：10:54:34 环境快照识别 Xiaomi M2003J15SC / Android11 SDK30，记录 STA 5300MHz 及 wlan0 默认路由；明确不是热点频率。/proc/net/arp 被 Android 拒绝（EACCES），已原样记录失败，没有提权。首次认证 1900ms、首次参数发送 1925ms、发送参数至 TCP 2617ms 均落盘；10:54:40.590 首帧成功。会话建立后停止采样并标明末次缓存年龄约 5221ms。车机尚未验收；未推送远程。
