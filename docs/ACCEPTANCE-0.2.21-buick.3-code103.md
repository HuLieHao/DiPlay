# code103：FF-5000 IPv4 与连接等待优化

版本：0.2.21-buick.3-debug / versionCode103；包名com.shihab.diplay.hudtest。

## 范围

仅对 FF-5000 / ac8227l / API27 固定发布 IPv4 连接地址，忽略已保存的 IPv6 偏好，超时后不再切换 IPv6；没有可用 IPv4 时明确报告准备失败。其他设备保持原有行为。

首次 TCP 等待20秒后保留当前监听和连接准备资源，允许额外2秒宽限；22秒绝对截止后失败，手动停止及代次切换立即失效。首次 TCP 超时重试退避上限降为4秒，并扣除已经等待的2秒宽限，实际后续等待为0、2、2、2、2秒；等待旧资源清理完成再重试，最多5次重试。

不修改车机路由、防火墙、IPv6系统开关、固件或MCU；不修改iPhone VPN。此优化减少无效IPv6尝试及本应用额外等待，不能保证解决iPhone/VPN不发起IPv4连接的问题。

## 音频复核

早晨日志 type102 media UDP端口42687：截至最后一个应用统计窗口616.700秒，PCAP和应用均为18022包、537个前向序号缺口；完整PCAP为18307包、539个缺口，最长内核到包间隔1.304秒。236.773秒开始的重缓冲附近，抓包已经出现长到包间隔及缺失序号。

说明这些缺口在进入应用解码前已经存在，不能据此进一步区分手机未发送、Wi-Fi损失或抓包前驱动损失，也不能归因VPN。未修改音频缓冲或播放时钟策略。详细脚本与证据：`../artifacts/analysis/20261106-code102/audio-followup.txt`、`audio_packets.py`、`audio_packets.json`。

## 验证

- 定向回归通过；独立集成审查未发现P1/P2或其他需要修正的问题。
- AGENTS完整检查通过（6m1s，--max-workers=2）：3620项测试，0失败、0错误、1既有跳过；三个模块lint和debug构建通过。既有Gradle弃用、JVM/native及测试图像解码警告仍存在。
- assembleStandaloneDebug通过（25s）；包名/版本、ZIP完整性、v2签名及非空本地认证资产已核验。
- 检查与独立构建期间1479个源码/资源/构建文件哈希保持一致。
- APK：`/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code103.apk`，25296062字节。
- SHA256：`494ccb6668dccef30072dc74f6495646ee371479f169e1bd9ce4a6278ac1c97d`。
- 检查和审查记录：`../artifacts/analysis/20261106-code102/code103-validation`。
- 保留旧包及其他未提交改动；未安装、提交或推送，尚未实车验证连接改善。

旧控制器清理如果真正卡住，自动重试会继续等待实际关闭，以避免资源重叠；用户取消仍可使重试失效。此版本不承诺绕过VPN拦截，也没有音频修复。
