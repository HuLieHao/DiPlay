# code98：连接证据补强及 FF-5000 固件排查

版本：0.2.21-buick.3-debug / versionCode 98；包名 com.shihab.diplay.hudtest。

## 本轮范围

- iAP2 为 CSM 消息/分片和链路包建立关联，分别记录排队、底层流写入成功/失败、链路 ACK、重传和协商参数。流写入不等于手机收到，链路 ACK 不等于手机 CarPlay 服务接受。
- root 采集补充 IPv4/IPv6 回程路由查询与 IPv6 内核计数。查询注明 source、oif、mark、UID，失败或固件不支持保留原始结果，不把 root 查询冒充应用实际选路。
- 蓝牙采集状态明确区分已有文件、有效记录和不可用；不自动开启 HCI snoop，不修改系统配置。
- 保留启动自动采集、停止后流式下载、原始 PCAP 内核时间戳/SLL2/完整记录边界。没有调整连接超时、重试策略、音频策略或系统路由。

## 固件排查结果

使用 2026-10-24 归档的真实 services.jar 与 mediatek-framework-net.jar，校验 SHA256 与采集清单一致。反编译发现：热点 tetherInterface 只添加其 IPv4 网段；只有存在非空 IPv6 广播前缀时，configureLocalIPv6Routes 才补入 fe80::/64。当前热点没有上游网络，MTK 协调器传递空 IPv6 配置，导致 local_network 没有链路本地路由。

该状态在 code78 日志中已存在。code97 的18次 IPv6 SYN 对应18次 ListenDrops，缺失回程路由能够解释现象，但尚未执行实车 route get 或内核分支追踪。本次增加只读证据采集来验证，不宣称已修复实车连接。

IPv4 路由成功安装，成功尝试从 StartSession 到 TCP accepted 约310ms，与旧日志307–331ms一致。前两次IPv4失败仍缺手机侧发送路径证据；本次首先补齐本地iAP2写入和链路ACK边界，不能把 Shadowrocket 直接定为根因。

详细固件证据：/Users/huliehao/headunit/artifacts/analysis/20261104-code97/firmware-network-findings.txt。

## 验证与交付

- 独立静态审查未发现 P1/P2；确认 ACK 与应用接受分开描述，路由查询只读，未开启 HCI snoop。
- AGENTS.md 完整命令最终通过（47s）；3576项测试、0失败、0错误、1跳过，三模块lint/Debug构建通过。
- 首次完整运行有现有 BufferedAudioStreamTest.pausedTcpPreloadCannotExceedTheAdvertisedByteBudget 等待超时；原样单独复跑通过（5s），随后完整复跑通过。未修改音频代码或测试超时。
- assembleStandaloneDebug 通过（3s）；认证资产存在且非空，ZIP 完整，v2 签名有效，包名/versionCode/versionName已核验。
- 构建前后1466个源码/资源及构建文件哈希一致；没有构建中混入代码修改。
- APK：/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code98.apk
- SHA256：f2299eeaf1d007e174582c3ca3446d945bf0e1ac7996c813827abb8836d42f6b
- code96、code97保留，未安装设备、未提交或推送。

## 验证边界

尚无 code98 实车运行验证。对端 maxRetransmissions=0 时保留原始ACK与发送序号日志，不自动推导包级ACK关联；诊断回调异常已隔离，实车回调/抓包负载仍需观察。路由查询的20秒是启动查询预算，子进程清理可能额外耗时。HCI复制记录可能来自历史，不代表本次实时完整覆盖。
