# code75：修复采集导出、时间戳和接口标识

针对 code74 审查中的三项已确认问题。保留原始抓包和详细日志，不调整 CarPlay 连接重试、热点或音乐参数。固定 kaiyue 工作区，无推送。

## 大日志取回

使用设置 → 诊断 → 手机获取报告。通过现有二维码或地址和验证码打开页面，点击下载报告；ZIP 直接边生成边发给手机，不在车机 cache 目录准备整包，也不检查是否还有另一份未压缩数据大小的空闲空间。每个文件使用固定长度快照、校验和和原有 PCAP 完整记录边界；原文件保留。

“保存诊断报告”本地落盘路径仍需要足够空间，大日志或车机空间不足时使用手机下载路径。日期/会话选择仍筛选普通归档；详细采集仍包含所有保留的进程目录，manifest 明示。此次没有通过删除历史文件解决问题。

HTTP 使用 chunked 传输，只有生成成功才发送终止块。中途失败不伪装成功下载，重新点击可重试；不支持断点续传。活动下载不受页面空闲超时打断，但连续30秒无成功写出会关闭连接，释放归档占用和抓包暂停。

为避免下载 ZIP 流量被再次抓回车机磁盘，手机下载期间暂时暂停**数据包写盘**，继续读取/丢弃该期间的包并记录 exportPaused/exportDiscarded；应用和系统日志继续。成功、异常、断连或写入停滞超时后恢复。root 启动与导出开始使用同一锁登记暂停状态。暂停标记创建失败会写入应用诊断，不能把失败视为已经暂停。

## 抓包时间和接口

- 使用 recvmsg 取得内核逐包时间戳。优先 SO_TIMESTAMPNS_NEW，旧内核回退 SO_TIMESTAMPNS_OLD，再回退微秒 SO_TIMESTAMP_OLD；日志记录实际选项和精度。32位 OLD 与64位 NEW 数据结构分别解析。
- 在启用时间戳后才绑定 ETH_P_ALL，避免启用前的包混入。缺失、截断或无效时间戳会停止抓包并报告，不再用 gettimeofday 填充。这是内核软件时间，不是无线硬件空口时间；系统校时仍可能造成实时时钟跳变。
- PCAP 使用纳秒格式和 Linux SLL2（LINKTYPE 276），每包保存接口编号、方向、链路类型、地址和协议。结合 network-state/system-snapshots 的接口信息区分 ap0/wlan0 等。code74 的旧 PCAP 保持原格式，不重写历史文件。

协议核对：[Linux 内核时间戳文档](https://www.kernel.org/doc/html/latest/networking/timestamping.html)、[libpcap SLL2 格式](https://raw.githubusercontent.com/the-tcpdump-group/libpcap/master/pcap/sll.h)。

## 验证

新增/扩展：零暂存空间流式导出、真实 HTTP 下载和鉴权、长下载跨空闲期限、生成失败不发送终止块、非读取手机超时与再次下载、失败导出释放归档占用、root 登记前后/嵌套导出暂停恢复。原生测试覆盖 SLL2 字段、纳秒 PCAP 头、32/64位内核控制消息、微秒转换、截断和异常时间戳。

独立审查发现并已修复：非读取手机造成无限暂停；root 登记和导出开始竞态。对应回归先失败再通过。

最终完整 CI 通过（4m16s）：shared 1722 项、common 1815 项、home 6 项，共3543项，0失败、0错误、1跳过；三个模块 lint 和 Debug 构建通过。两项原生测试编译并执行通过。带本地认证材料的 Debug 包构建通过，四种 ABI 的原生库均核对包含 SLL2/kernel_realtime 实现，流式下载入口和认证素材已核对。

APK：`/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code75.apk`。包名 `com.shihab.diplay.hudtest`，版本 `0.2.21-buick.3-debug / code75`。SHA256：`1a4b746f12cb86d55718881f103ec0bb64c583721ee16787a7b8c0f334dcc940`。

没有实车验证 root 启动、热点接口实包或长时间采集负载，不能据此宣称连接慢已解决。
