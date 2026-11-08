# code96：最新工作区重新构建及与code78对比

版本：0.2.21-buick.3-debug / versionCode 96；包名 com.shihab.diplay.hudtest。
在 kaiyue 当前工作区构建，保留另一模型的全部修改；本轮产品源码仅递增版本号。本轮未安装设备、未推送远程，保留旧APK。

## 相对code78的主要变化

| 项目 | 变化与实际影响 |
| --- | --- |
| CarPlay日夜同步 | 首次/info读取当前宿主日夜状态，声明nightMode和主屏明确外观；连接中同时发送setNightMode与uiAppearanceUpdate，同一连接可切换日夜。事件通道未就绪时保留最新值，两条命令写出后才清除待发送状态，重连重新查询。主屏与时钟使用同一宿主决策。 |
| 日夜设置简化（code95归档后新增） | 菜单移除“跟随系统”，保留跟随车灯、按时间、固定日间、固定夜间。旧system设置读取时按VEHICLE处理；车灯状态不可用时沿用时段兜底。code78及code95 APK反编译确认旧版只把AMBIENT映射为SCHEDULE，未映射SYSTEM；code95菜单仍显示SYSTEM。 |
| 板载MFi读取时序 | FF-5000通过Linux I2C /dev/i2c-2访问板载芯片时，选择寄存器后等待5ms；新增读取阶段/错误/耗时诊断。使用本地离线认证时不进入这项等待。 |
| 热点及系统资源证据 | 使用配置中的hostapd控制目录查询客户端；补无线info/survey；约每5秒采集关键进程、线程CPU/I/O、磁盘、CPU频率和温度。命令返回空内容与失败可区分。 |
| 蓝牙及音频证据 | 收集系统已有的HCI日志，按完整记录增量复制并处理改名轮换；增加AudioFlinger、audio_policy和audio快照。没有HCI日志时明确记录不可用，不自动开启系统抓包。 |
| 校时时间关联 | 新增network.pcap.clock.tsv，将包序号/字节位置、内核REALTIME与用户态BOOTTIME/MONOTONIC关联，标记观察到的时间跳变。保留原始PCAP时间，不把程序读取时间当作内核收包时间。 |

code78已有的应用启动自动采集、手动停止后导出、root失败停止、流式ZIP、内核逐包时间戳、SLL2接口编号和停止重试恢复继续保留；这些不计作本次新增。连接超时和音乐缓冲/播放策略不在本轮修改范围。

## 证据与限制

对比依据为code78/79/80/81/95归档记录、当前源码及code78、code95关键类反编译结果。code78的AirPlayInfoPlist没有当前明确主屏外观声明；code95之后的设置简化通过旧APK与当前源码交叉核对。本轮未把Git HEAD（历史较早）当成code78源码基线。

另一模型在红米+iPhone上验证过code95主屏/时钟的日夜切换。高德“自动”仍可独立采用深色，不能宣称所有地图都已受宿主控制。code96尚未实车验证，小灯输入、板载MFi成功率、采集负载和音乐卡顿仍需实测；诊断增强本身不等于已修复连接慢或音频卡顿。

## 构建和校验

- 执行AGENTS.md完整命令成功（9s，包含Gradle缓存命中）；测试结果汇总3563项，0失败、0错误、1跳过，三模块lint/Debug构建通过。
- 三组原生测试（PCAP格式、内核时间戳、时间关联）通过。
- assembleStandaloneDebug成功，包含已授权本地认证材料；ZIP完整、v2签名有效，版本/包名已核对，四种ABI均包含时间关联实现。
- 构建前后主源码文件哈希一致，未发现构建中其他修改混入；git diff --check通过。
- APK：/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code96.apk
- SHA256：`b78681f683d242128c23b06d5df4e0bd99256b96f82d2fe2546b77cb20c5d9f6`。
