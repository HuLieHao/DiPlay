# code81：校时前后的抓包时间关联

在当前 kaiyue/code80 工作区补强审计第5项，保留其他未提交改动和旧APK，不安装、不推送。

## 行为与分析方法

新增 `network.pcap.clock.tsv`，由原生抓包线程生成并随诊断报告导出：

- 每个完整PCAP记录对应一条packet行，包含1起始包序号、记录结束字节位置和原始内核REALTIME时间戳（纳秒）。
- recvmsg返回后、PCAP写盘前依次读取 BOOTTIME → REALTIME → MONOTONIC → BOOTTIME，记录用户态读取时间与采样区间。BOOTTIME对应Android elapsedRealtimeNanos，包含休眠；MONOTONIC对应原生health的monoMs，不包含休眠。
- 启动、空闲期间约每秒、停止时补时间锚点。比较REALTIME与BOOTTIME偏移区间，超出采样不确定度及5ms容差时记录event=2并递增sampleEpoch，同时在root-launch.log记录偏移变化。
- 关联文件使用缓冲写入、约每秒刷新、停止时刷新并同步。文件或时钟读取失败会明确记录不可用，原有网络抓包继续。
- 内核逐包时间戳、SLL2接口编号、PCAP完整边界、系统校时策略及连接行为均保持原状。没有生成伪造时间的“修正PCAP”。

`sampleEpoch`表示观察到的时钟映射阶段，不直接表示包的到达阶段。校时前排队、校时后才读取的包可能属于旧阶段；必须结合原始内核时间和相邻锚点，不能把用户态读取时间当成内核收包时间。可在明确且稳定的区间用偏移范围对齐应用日志；跨校时、采样区间过宽或存在归属歧义时保留不确定性，不直接计算跨阶段网络延迟。小于阈值或两次采样间发生又恢复的调整不保证识别。

TSV头部附有字段和限制说明。忽略末尾不完整行，以及pcapEnd超出导出PCAP长度的行。正常页面导出仍要求先停止采集。

## 验证

- 原生回归覆盖稳定时钟、约111秒回拨、向前校时、休眠、采样调度延迟、无效采样、行序列化和输出错误；PCAP格式、原始内核时间戳回归通过。
- 报告流式导出失败后重试保留原始PCAP与时间关联文件，字节一致。
- 独立审查无待处理缺陷；独立ARMv7/API27编译通过。
- 最终完整检查成功（3m55s）：3558项，0失败、0错误、1跳过；三模块lint和Debug构建通过。
- standalone debug构建成功；APK ZIP完整、v2签名有效；核对认证材料及四种ABI的新原生时间关联代码均包含。
- 版本：0.2.21-buick.3-debug / versionCode 81；包名 com.shihab.diplay.hudtest。
- APK：/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code81.apk
- SHA256：`852d365d5e217f0bb352eef1158342544fefff65d83cb3c0f77ea4dac56c8fdc`。
- code80哈希核对未变；未安装、未推送。

## 实车待验证

确认报告包含TSV；发生iPhone校时时对照app-full.log的时间调整记录与TSV中的event=2。观察长时间播放时采集负载。本机验证不代替实车校时、驱动及写盘负载验证。
