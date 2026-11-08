# BuickPlay 0.2.21-buick.2-debug code65

本版仅整理 CarPlay 日夜模式选项：
- 总览及显示设置均移除“依照环境光”，小米和车机一致。
- 移除显示设置中的环境光阈值及延迟控制，不再进入设置搜索索引。
- 保留车辆小灯、按时间、固定日间、固定夜间、Android系统五种选项。
- 旧配置若为 ambient，加载时使用 schedule，保留原定时起止时间；其它模式不变。
- code64 小灯广播驱动和实车验收内容不变，参见 [code64验收步骤](ACCEPTANCE-0.2.21-buick.2-code64.md)。

完整 CI 通过（5分2秒）：3491项测试，0失败，0错误，1跳过；Debug 构建和 lint 通过。
已覆盖安装到红米 M2003J15SC（10014df30410），设备核实 versionCode=65、versionName=0.2.21-buick.2-debug，保留应用数据。未推送远程。
安装包：`../artifacts/apk/BuickPlay-0.2.21-buick.2-debug-code65-20261029.apk`
SHA-256：`499b2f3288b1a3f0f009b2a24fb1618e543fce303a16cf34bd218c1a744990cb`。
