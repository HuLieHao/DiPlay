# code102：首窗口边界与停止尾部集成验证

版本：0.2.21-buick.3-debug / versionCode102；包名com.shihab.diplay.hudtest。

## 范围

提前初始化首个音频统计窗口，避免首包峰值早于窗口起点。增加root先停止、应用随后刷尾部的集成回归，覆盖停止等待、最终读取边界以及真实ZIP导出内容。

不调整播放、缓冲、连接或采集频率，不修改系统配置；保留code101和其他未提交改动。

## 验证与限制

- 生产变化仅音频worker入口提前初始化统计窗口；没有修改停止流程或新增生产测试钩子。
- 真实worker回归先复现首窗口峰值早于起点，再验证修正后的第一/第二窗口及峰值时间。
- 新增停止集成测试：受控模拟root停止，真实应用队列/镜像线程/stop执行后续刷盘；确认needsStop在刷盘前阻止下载，最终长度/读取位置/未读字节正确；真实exportTo ZIP逐字节匹配事件、应用日志、边界和root游标文件，原始文件保留。该测试不覆盖HTTP/UI或真实su执行。
- 27项定向测试通过，独立审查无P1/P2。
- AGENTS完整检查通过（5m21s，--max-workers=2）：3607项测试、0失败、0错误、1跳过；三个模块lint及debug构建通过。
- assembleStandaloneDebug通过（40s）。完成后核对包名/版本code102、ZIP完整性、v2签名及非空本地认证资产。
- 最终检查与独立构建期间1475个源码/资源/构建文件哈希一致。
- APK：`/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code102.apk`
- SHA256：`fc1b5be0b1285add08c25c3121403147dcf5aeddd2e597b45b4c0fc5383c78ed`
- 验证日志及审查：`artifacts/analysis/20261104-code97/code102-validation`。
- 保留旧包、其他未提交改动；未安装、未提交或推送。

测试中的root退出由受控边界模拟，不等于实车root/驱动验收；尚未验证实车采集负载或音频卡顿根因。
