# BuickPlay 0.2.21-buick.2-debug code56

接续 [code55](ACCEPTANCE-0.2.21-buick.2-code55.md) 的隔夜问题修复。

- 保留连续 5 分钟未连接即停止、关闭 CarPlay 页面、返回原管理页的行为；手动重新连接才能再次启动。
- 保留归档清理的单次扫描修复。原文、保存期限、容量及导出 pin 规则不变。
- code55 实机首次握手仍丢 205 条日志，因此应用日志队列从 64 扩到 512 条，单条仍限制 64KiB 字符，超限明确标记。没有无限缓存，也不阻塞连接线程等待磁盘。
- 新增真实异步写入测试：首条写入阻塞期间连续提交 300 条日志，恢复后逐条检查落盘。旧版失败（missing burst entry 0）。
- IPv6 偏好已由上游现有回退逻辑保存。本次未改动地址选择、认证、FIRST_TCP 30 秒期限；code55 已观察到 IPv6 首轮约 3.84 秒完成，但需继续验证离开后返回等不同场景。

本次不推送远程；车机尚未验收。原始资料在 `../artifacts/analysis/overnight-code54-20261027/`。

## 验证记录

- 同一超时实现已在 code55 红米实测：300001ms 停止，原 task266 管理页保留，无残留 CarPlay 页。iPhone 重开无线后未自行连接；手动连接 7.179 秒恢复首帧。
- 第一次 code56 全量 CI 出现 `SettingsReportFileServerTest.repeatedArchiveDownloadsReleaseSnapshotsAndPreserveBoundedOriginalEvidence` HTTP 200→404 单项失败；该类单独复跑全部通过。保留失败日志并进行第二次全量验证，不隐去首次失败。
- 第二次完整 CI：BUILD SUCCESSFUL，4m2s；3464 tests / 0 failures / 0 errors / 1 skipped。首次 404 尚未复现，保留为待追踪测试问题。
- APK：`../artifacts/apk/BuickPlay-0.2.21-buick.2-debug-code56-20261027.apk`。
- SHA256：`b3e300669fa48956d3ba473c8d99c0398a9e417d718b6ed97a536b2e8bf3ed72`。
- 已覆盖安装红米：versionCode=56，versionName=0.2.21-buick.2-debug。
- 实机连接：09:50:19.107 开始 → 09:50:22.284 IPv6 TCP → 09:50:24.135 首帧，5.028 秒。
- 截至 09:50:57，新进程起点以来 696 行、11 条 milestone，未发现新 DIAGNOSTIC_QUEUE 丢弃记录。旧进程记录仍保留，不能直接统计整个混合日志的历史 droppedTotal。
- 仍需车机验证：长离线后手动连接、真实断电/重启后的留存、IPv4/IPv6 不同网络环境下的首轮接入。未证明 30 秒等待的唯一根因，也未通过缩短协议超时掩盖问题。
