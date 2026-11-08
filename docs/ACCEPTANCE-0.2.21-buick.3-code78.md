# code78：清理失败后的停止重试恢复

移除永久性的 cleanupUnconfirmed 开关。保留每次实际启动的 Process 句柄，每次停止都重新检查：启动 worker 已结束、应用日志已关闭、所有已启动进程均已退出，以及写入 root-ready 的每次 root 尝试都有 collector-stopped 确认。

历史超时、强制终止或停止标记写入错误本身不再永久禁止导出。实际状态仍未确认时继续阻止导出；之后进程退出且 root 确认清理完成，再次停止即可完成收尾并允许保存/下载。没有按超时自动放行，也没有忽略仍然存活的进程。

回归：制造 stop 写入失败，第一次停止不放行；修复文件并补齐 root 停止确认，再次停止可导出。该用例在旧实现上失败、修改后通过。另一用例验证进程仍存活时即使已有停止标记也不能放行；进程已退出但缺少 root 确认仍不放行；两项都确认后可恢复。

定向测试通过。完整 CI 成功（5m8s）：3547项，0失败、0错误、1跳过；三模块 lint / Debug 构建通过。带本地认证材料的交付包构建通过，元数据核对 code78。

版本：0.2.21-buick.3-debug / code78，包名 com.shihab.diplay.hudtest。
APK：`/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code78.apk`。
SHA256：`8c51b2cc00bad31b15d79284d9c556ed62be359301f63438cd1be2f277390d1a`。
保留其它未提交改动；未安装、未推送。未实车验证。
