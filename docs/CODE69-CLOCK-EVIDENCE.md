# code67 时间异常离线复核与 code69 诊断改进

证据目录：`/Users/huliehao/Downloads/BuickPlay-code67-8256550702931378004`。
仅离线读取；未连接车机、未修改系统时间。

四个 session 的 summary 记录了墙钟从 2026-10-31 跳到 2007-03-17：

| summary 文件 session 前缀 / 行 | previousWallMs | wallMs | monotonicMs |
| --- | ---: | ---: | ---: |
| b40cb6eb / 166 | 1793425695713 | 1174108920039 | 79991 |
| 28d13407 / 543 | 1793429889302 | 1174113174241 | 494735 |
| 5ac31584 / 823 | 1793439554889 | 1174122853020 | 379657 |
| f8db553e / 148 | 1793444019041 | 1174127307329 | 113310 |

`diagnostics.txt` 的 reportGeneratedAt 为 `2007-03-17T18:30:01.319+08:00`。
这些证据确认应用观察到异常系统墙钟，不能证明时间修改来源；不能据此归因 RTC、电源、网络校时或 iPhone。没有自动校时或日夜、连接策略变更。

离线可复现的诊断缺陷：session 列表只读取 metadata 第一行，漏掉后续追加的 `clockValid=false`，使异常会话的列表仍显示 true；同一 session 的时钟异常仅记录第一次，无法保留后续校正/再次回跳的差值。改进保留 metadata 有界一次标记，同时对实际跳变写入 summary，附上前后单调时间与墙钟差值；持续处于 2007 年但正常走时不逐条重复记录。原始 detail 行不改写。

导出 manifest 明确排序限制：ZIP 文件名排序不是事件先后；会话内按 part 数字及文件行号，跨会话仅在相同 process 的单调时间上下文中比较；不同 process 不据墙钟或文件修改时间推断可靠顺序。时钟异常后本进程日期筛选仍保持保守无效。

时钟覆盖层的进程内最近状态进入诊断报告：页面显示/隐藏原因、是否 attached、视频区域、覆盖层位置大小、系统时间样本的有效性、日夜颜色及实际字号。状态只随已有 UI 更新或分钟 tick 刷新，不增加定时任务或逐秒日志；报告读取快照而不跨线程读取 View。快照明确为本进程最近观察，并非原会话录像或手机时间证明。车机实际绘制位置仍需后续设备验收。

## 2026-10-31 固件进一步定位

车机原厂反编译 MainUI.java:234–244 的 GPS LocationListener 在 nUpdateTime==0、非KABT、bAutoTime()成立时，直接执行 SystemClock.setCurrentTimeMillis(location.getTime())；bAutoTime()读取Settings.Global auto_time；2385行订阅gps provider。日期2026-10-31与2007-03-17相差7168天，即1024周。四次日志墙钟回跳在1024周之外还分别差1575.674、1515.061、1501.869、1511.712秒，不能把整个偏差说成精确周数回绕。GPS传统周数1024周回绕机制参考 https://www.gps.gov/news/gps-week-number-rollover 。这使GPS接收机时间异常经MainUI校时成为首要假设，尚未获得跳变瞬间location.getTime/NMEA日期及实际setter证据。

其他可写时间路径：MainUI.AdjustArmLocalTime使用2016-10-01基准，当前时间大于基准即返回，因此不符合2026→2007直接回拨；MainSet.getNetworkTime直接使用HTTP Date，仍需排除，但没有本次响应头证据。新network快照中的MNLD定位更新、AGPS socket缺失/权限错误仅证明GPS服务在工作及有辅助服务异常，不能单独证明日期回绕。

下次只读取证：记录auto_time/GPS定位时间或NMEA UTC日期、系统墙钟及elapsedRealtime、MainUI/LocationManager/MNLD/AlarmManager在回跳前后的日志；确认GPS提供2007日期并触发setter才可定案。关闭自动时间、手动校时或修改固件均属于后续明确授权的对照/修复，本轮未执行。code70未改变系统校时行为。
