# 新 code67 音频离线分析

范围：`/Users/huliehao/Downloads/BuickPlay-code67-8256550702931378004`，只使用非 `legacy` 的日志及对应 `versionCode=67` 元数据。以下行号均指 `logs/5ac31584-5188-4e2a-b80f-497d7ad026d3-e228e4ed-3092-4151-9393-de4cf64521fa-000000.log`。这是约 15 秒的一条 AAC_LC MEDIA 流，不能外推成长期播放表现。

## 实际观察

- 969–973：48 kHz、双声道、500 ms track buffer、300 ms start threshold；首个时钟样本 PCM=15360、compressed=17408、target=14400，filteredError=1.2755556，integral 和 inheritedIntegral 均为 0，requestedRate=48720。
- 980–983：第一次暂停前只剩 175 PCM 帧；暂停 312124154 ns 后恢复。恢复时 sampleAge=676146846 ns，仍保留暂停前 filteredError=1.03300695。
- 987–1002：第二次暂停约 1625 ms，第三次约 520 ms。第三次恢复时 sampleAge=978314462 ns，仍低于一秒 stale 门槛。
- 1006：Receive 音频 interArrivalMaxMs=1807、readMaxMs=1806，有一次 21 包前向序列缺口；不能单凭接收线程日志判定是无线丢包、发送端停顿还是本地调度阻塞。
- 991、1008、1067、1071：共四条 MEDIA stats，记录的 maxGapMs 最大 1808，maxWriteMs 最大 253；dropped、writeErrors、zeroWrites、partialWrites、decoderDroppedTotal 全部为 0；末尾累计 rebuffers=4。最后一条是终止快照，不能把四条都当成完整五秒窗口。
- 1073：终止时 integral=0.00501315、filteredError=0.4806502、requestedRate=48720，compressed=37504；不能将其解释为测得车机硬件慢 1.5%。

## 可离线证明的缺陷与修正范围

`MediaClockTrim.sample()` 用“上一队列按 rate 持续消耗”估算两次写入间的平均值，只有两次样本相隔超过一秒才重启平均；`AudioRenderer.startPlayback()` 在 rebuffer 恢复时此前没有通知它。因此已经明确暂停的 312 ms / 520 ms 仍进入连续播放模型，同时下一次 `rateDue()` 会把恢复前的时间算入积分步长。暂停不是时钟漂移；这与类中“rebuffer 后重新开始平均”的既有约定冲突。

修正只在显式 rebuffer 恢复后重启采样/更新时基，保留已有 integral 和当前 AudioTrack rate，下一次真实 PCM 写入重新计算；不把暂停区间作为新的漂移证据。恢复诊断仍先保留暂停前最后一个真实样本，随后清理模型时基。该行为不依赖诊断报告是否已达到五次上限。

回归 `AudioClockTrimTest.aShortRebufferRestartsClockObservationWithoutLearningThePause` 使用 676 ms 样本间隔、保留 0.008 integral、恢复后目标水位 14400，要求第一步输出 48384 Hz（仅已有积分，无暂停前误差、无暂停积分）。同时覆盖第 1 与第 6 次恢复，防止把修正误放进有界日志分支。

## 48720 Hz 的解释与限制

首样本 `(15360 + 17408 - 14400) / 14400 = 1.2755556`，比例系数 0.03 使请求达到 +1.5% 上限，即 `48000 × 1.015 = 48720`；继承积分不是首样本触顶原因。现有压缩 backlog guard 只冻结积分，不关闭比例项。后续 backlog 消退但滤波尚未消退时积分仍可变化。是否应调整该策略，需要连续输入/播放观测与车机试听，当前不凭这段流改控制参数。

1.5% 速率差每 500 ms 仅多消耗约 7.5 ms 音频，不能解释 1808 ms 接收空档。离线修正能消除已知暂停区间的错误建模，不能承诺修复网络空档或消除全部 rebuffer。保留当前缓冲默认、协议、压缩队列计量与码率上限；未连接设备、未更改系统。
