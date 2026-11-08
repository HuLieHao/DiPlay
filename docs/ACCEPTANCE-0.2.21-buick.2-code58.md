# code58：无线连接详细日志

版本：`0.2.21-buick.2-debug`，versionCode `58`。固定分支 `kaiyue`，未推送。

## 本轮变化

沿用现有会话日志与诊断报告，不增加连接超时、不改变 IPv4/IPv6 偏好策略。

| 阶段 | 保存的证据 |
| --- | --- |
| 发起连接、MFi 准备、热点、选择 iPhone | 现有启动/状态日志，新增阶段前后耗时、控制器累计耗时、本轮累计耗时、事件单调时间、主线程派发延迟 |
| 地址选择 | 热点接口、全部候选地址、选择结果、持久化偏好来源、偏好写入触发条件；超时切换明确标记不是成功学习 |
| AirPlay 监听与入口发布 | IPv4/IPv6 实际监听地址与端口；蓝牙发送的地址列表与端口；已有 iAP2 协议日志 |
| 蓝牙、认证、热点参数、StartSession | 已有连接耗时、身份/认证里程碑、原始消息与首个参数发送时间 |
| 等待 iPhone TCP | 每 10 秒后台快照：接口、地址、STA 网络、路由、已有发现/探测计数与内核计数；新增本地文件证据 |
| TCP 到会话建立 | 本地/远端端点、IPv4/IPv6、接受时刻、是否内部探针、已有 AirPlay 各阶段日志 |
| 会话到画面 | 不再在 sessionActive 时结束观察；等到首帧，记录会话时间、首帧时间、两者间耗时及 connection_complete |
| 失败、取消、重试 | 失败原因链和当前状态快照、原有 deadline/退避/清理日志；关闭时输出最后快照并明确 cached 和 ageMs |

`controllerElapsedMs` 从控制器创建计算，`runElapsedMs` 从本轮开始计算；启动观察对象的相对时间原点是热点地址选定之后，日志标记 `timingOrigin`。两者不能当作同一个起点。跨控制器重试根据归档 startup/controllerGeneration、attempt/run 和时间串联。

新增修复：Host 回调先把全部原始日志写到所属控制器的归档，再处理界面状态。设置菜单打开或 generation 已更换不再丢弃已收到的普通日志；不会重复写入或允许旧回调修改当前 UI。

## 网络证据边界

被动读取 `/proc/net/arp`、IPv4/IPv6 路由、TCP 表和三个已知 DHCP 租约候选文件。单文件最多 16 Ki 字符，截断明确标记；每次快照最多 32 行，超出记录遗漏行数。保留原始内容和失败原因，无脱敏。

- Android 应用权限不足时明确记录 unavailable，而不是空表或无客户端。
- 2026-10-27 红米 Android 11 实测 run-as 应用身份执行 `ip neigh` 被拒绝：`Cannot bind netlink socket: Permission denied`。
- TCP 表只是采样时状态，不是 TCP SYN 包历史；租约文件不是 DHCP Discover/Offer/Request/Ack 过程。
- 未自动获取 root、连接本地 ADB 或抓包。完整 DHCP/ARP/TCP 包级定位仍需要停车复现时的外部只读取证；保留设置里的手动网络采集入口。
- 当前默认网络、STA DHCP/频率不代表 iPhone 状态或热点频段；保持日志中的明确标记。

## 保存与导出

新增记录沿用 `uiListener.onDebugLog → AsyncDiagnosticLog → SessionLogFile → DiagnosticArchive`。写入的是会话归档，非只存在 logcat。旧 `diplay.log` 镜像会缩短长行，完整网络证据以导出 ZIP 中会话归档为准。使用“所有保留的会话”可关联失败及后续重试；仍受现有容量、保留期限、磁盘余量约束，不承诺无限保存。

## 验证

- 已先验证新测试在缺失 frameRendered/fileEvidence 时失败。
- 定向测试通过：首帧延迟/重复事件、文件有界读取/权限失败/原始内容、双栈 TCP 接受。
- 菜单漏日志回归：新增两项测试先失败，修复后定向测试全部通过；独立审查及修复复核无阻塞问题。
- 第一轮 CI：`BufferedAudioStreamTest.aForeignSenderCannotClaimTheSingleTcpStream` 在 3 秒 socket 读取处超时；单独复跑通过，未修改音频实现或该测试。最终完整 CI 通过（4m54s）：3474 tests / 0 failures / 0 errors / 1 skipped；Lint、三个模块构建及 standalone 认证检查通过。
- 未安装覆盖正在运行的 CarPlay；实机新日志验收待下一次连接。

APK：`../artifacts/apk/BuickPlay-0.2.21-buick.2-debug-code58-20261027.apk`。
SHA256：`46836e2c6e379d90810fe3719effcd4b35155a9d4cb3f3baf6cffb1265786b82`。
