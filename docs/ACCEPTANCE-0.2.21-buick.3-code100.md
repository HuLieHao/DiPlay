# code100：音频故障证据补强

版本：0.2.21-buick.3-debug / versionCode 100；包名 com.shihab.diplay.hudtest。

## 范围

补充音频异常事件、队列/解码/写入耗时关联、低频 AudioTrack 时间戳、异常触发的独立 root 音频快照。保留常规日志、完整网络抓包和现有连接证据。音频缓冲、播放速率、重缓冲阈值和连接策略不变。

## 新增证据

- 每个 renderer 独立标识；欠载、重缓冲暂停/恢复、队列和解码丢弃、慢写入均记录事件，不受原常规日志前5次限制。
- RTP序号/采样位置、renderer submit、解码输入/输出、写入起止时间用于关联；解码输出只按PTS匹配，歧义/缺失明确标记，不猜测。
- 每10秒记录 AudioTrack 时间戳是否可用、帧位置、纳秒时间和播放状态，以及处理阶段耗时；短流结束也保留耗时汇总。
- `audio-events.log` 保存事件，`app-full.log` 同步包含事件；`audio-anomalies.log` 保存独立root线程触发的 AudioFlinger、audio_policy、audio 与应用/音频服务线程调度快照。
- 应用事件队列有界并记录丢弃；root只合并快照请求，记录延迟、合并次数、命令退出/超时。新增线程纳入停止与父进程退出清理。

## 验证边界

异常快照是异步采样，不是冻结故障现场；需要按记录的触发时间、开始时间和延迟判断覆盖范围。AudioTrack underrun 的观察时间不等于精确发生时间。设备不提供播放时间戳或系统命令失败时应保留实际不可用状态。

慢写入以100ms为采样触发条件，它也可能是正常缓冲背压，不能单独判定卡顿。处理阶段最大耗时为本renderer生命周期累计值；计时从renderer submit开始，不是内核收包时间。

本次不自动开启独立的解密音频 XCAP/PCM 录制，不修改系统配置、路由、固件或 MCU。没有实车验证，不能据此宣称已解决卡顿或已证实 VPN 根因。

## 验证与交付

- 独立审查发现并修复重复PTS导致的错误关联；加入交错输出、已淘汰PTS复用和失效标记容量边界回归。无法可靠关联时明确记录unknown/disabled，不改变播放行为。
- 首轮完整检查发现2个旧测试使用两参数AudioPacket反射构造；已适配元数据参数并验证真实renderer十轮暂停/恢复/队列丢弃均有事件，常规简报仍保持原限制。
- AGENTS规定的完整检查通过（5m18s，--max-workers=2）：3601项测试、0失败、0错误、1跳过；mobile/home/maphost lint及debug构建通过。
- assembleStandaloneDebug通过（4s）；versionCode100、版本名、包名、v2签名和ZIP完整性已核对，认证资产存在且非空，新诊断代码存在于DEX。
- 1473个源码/资源/构建文件在最终检查及独立构建期间哈希一致。
- APK：`/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code100.apk`
- SHA256：`155aca71b8c5f4e1e33ffbd4ce2575654dfe2abd8324e6eb765414b6eda6c03e`
- 验证日志和审查报告：`artifacts/analysis/20261104-code97/code100-validation`。
- 保留现有分支、其他未提交修改及旧包；未安装设备、未提交或推送远程。
