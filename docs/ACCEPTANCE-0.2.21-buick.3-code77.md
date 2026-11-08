# code77：厂商 root 失败即结束详细采集

诊断只调用与 iPhone 自动校时相同的厂商 su 模式；删除普通 su 回退。命令与调用参数见 StartupCapture.kt。不会尝试其它提权方式。

启动失败、提权失败或独立采集进程退出时，自动执行统一停止收尾：封口详细日志队列、关闭详细应用日志副本、清理本次 root 尝试，保留实际失败原因。即使 root 清理无法确认，详细应用日志也结束；状态保留清理未确认而非伪装成功。常规应用日志继续工作，不改变 CarPlay 连接或音频策略。

保留失败记录和已有文件，不删除历史证据。没有 root 时，不继续运行只有应用日志的详细采集。回归覆盖 root 失败后关闭日志接收、失败原因保留、重复停止和启动中停止。

定向测试通过。完整 CI 通过（4m10s）：3545项，0失败、0错误、1跳过，三模块 lint 与 Debug 构建通过。带本地认证材料的交付包构建通过。

版本：0.2.21-buick.3-debug / code77；包名 com.shihab.diplay.hudtest。
APK：`/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code77.apk`。
SHA256：`81d29eae4f62f246d828b8ee4448cccd62b3f6d259dae58cb42c0ad9e3beb26a`。
未安装、未推送；实车厂商 su 结果待设备验证。
