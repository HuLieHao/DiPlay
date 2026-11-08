# code74：App 启动自动采集

用户确认从 App 启动采集，优先保证 CarPlay 连接继续运行，不要求开机常驻。此版是诊断版本，不宣称修复慢连接或音乐缓冲。沿用当前 kaiyue 工作区和既有改动，不推送、不批量提交。

## 使用

安装 BuickPlay-debug 调试包，打开它（不是原有正式版）。仅 FF-5000 / ac8227l / API27 自动启动厂商 root 采集；其它设备不自动尝试提权。无需电脑、无需手动点网络快照。首次安装后先打开一次并导出报告核对自检，再用于正式上车连接。保持原有连接设置，不清应用数据。

连接前开始异步启动，采集不能阻塞 CarPlay；因此极快的首轮可能早于 root 抓包就绪，日志会保留两者时间。连接成功后持续记录；跨控制器重试不中断。App 进程退出后 root helper 检测 PID/starttime 并停止子进程。没有新增开机守护程序、热点重置、蓝牙开关或连接超时调整。

停车后在设置 → 诊断 → 保存诊断报告，选择全部；选择其它范围时，也会包含所有保留的 startup-captures 目录，manifest 明确说明范围。可使用现有“手机获取报告”功能下载 ZIP。不需要单独导出抓包。

## 内容

- `startup-captures/<run>/app-full.log`：原始应用日志独立副本，带 wallMs/elapsedMs，不受原归档容量裁剪。
- `<run>/app-metadata.txt`：包、构建、进程、时钟信息。
- `<run>-root-N/network.pcap`：内置 AF_PACKET 全接口、无协议过滤、完整数据包；标准 PCAP / Linux cooked v1，可用 Wireshark 打开。无需车机安装 tcpdump，不切换监听模式。
- `network.pcap.complete`：ZIP 内实际导出 PCAP 的完整记录字节边界，来自固定快照；未发布的尾部可能未包含。
- `network.pcap.selftest`：捕获到了已知本机回环自检载荷；只证明本机抓包可用，不证明热点流量已采到。
- `root-launch.log`：root/native 就绪、每秒抓包数量、内核丢包计数、故障和进程健康。
- `logcat-all.log`：全部可用 logcat buffer，包括启动时仍保留的旧记录和后续连续记录。
- `kernel-follow.log`：支持时持续 dmesg；不支持时仍周期保存 dmesg，并保留失败输出。
- `network-state.log`：每秒 TCP/UDP/ARP/路由/接口计数、内核网络统计与 CPU/内存状态。
- `system-snapshots.log`：启动及周期性完整 Wi-Fi/蓝牙/网络服务、路由、防火墙计数、驱动关联状态、DHCP/热点配置与进程信息。缺失命令、超时和权限不足有明确记录。

所有文件保留原始内容，不按大小轮转或覆盖；仅应用进程间传递日志的内存队列有上限，以避免采集挤占连接内存。丢弃会计数；磁盘写入失败会报告并停止对应写入，不伪装采集成功。现有日志摘要仍保留原限制，但完整应用副本及 root 文件独立保留。

## 自检验收

`diagnostics.txt` 的 Startup capture 段应显示 root_running、mirror=writing、loopbackSelftest=true，并且 pcapCompleteBytes 大于24。导出期间若程序仍运行，查看 root-launch.log 最新 health，packetThread/logcat 应为 true，packets 应随流量增长。使用 App PID 与记录时间对应本次启动；不要用历史标记证明当前采集健康。

如果 root_unavailable，查看两次尝试的 root-launch.log 和 launch-status.log。如果 native_capture_unavailable 或 errno 非零，系统日志可能仍在采集，但不能认为抓包成功。真实热点接口能否接收包、厂商 su/SELinux 是否允许、新进程加载原生库、实车 App 退出清理均待设备验收。

## 构建与验证

完整项目 CI 通过（6m6s）：shared 1722 项（跳过1），common 1806 项，home 6 项，共3534项、失败0、错误0、跳过1；三个模块 lint 与 Debug 构建通过。原生 PCAP 格式测试通过，内置抓包库四种 ABI 均已编译并核对打包。独立审查的三项问题均已修正并通过定向回归测试。首次完整检查曾与定向测试争用结果目录而失败，最终单独完整复测成功。

带本地认证材料的 Debug 构建通过；包名 com.shihab.diplay.hudtest，versionCode=74，versionName=0.2.21-buick.3-debug。APK：`/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code74.apk`。SHA256：`3dc6e96d58eb9209f268bb9c90861d4ea5151dde86aec35661ab60464ca6eb2f`。未连接车机，未安装，未验证 root/热点实包；没有宣称慢连接已修复。
