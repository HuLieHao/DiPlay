# code104：FF-5000 原生音乐/GIS音量适配

版本0.2.21-buick.3-debug / code104，包名com.shihab.diplay.hudtest。

仅 FF-5000 / ac8227l / API27，自动音乐选择legacy stream3、导航选择厂商GIS stream11。显式通道优先，提示音及通话不自动改路由。legacy拒绝时保留usage回退，不向NavigationPlayback宣称已取得独立流。路由日志包含renderer、原配置、请求/实际legacy、AudioTrack sessionId、用途和profile。

本机型在未保存音量选项且音乐通道Automatic/3时默认启用既有单格音量反馈。导航有效播放且已知独立流时控制该流；通话优先交还系统。显式关闭及其他音乐通道默认不接管。按键诊断沿用有界WheelKeyLog。

复用系统GIS播放检测，不注入广播、socket信号，不复制PhoneLink库、不改固件/MCU/系统音量策略。MainUI是否识别GIS开始/结束、旋钮是否先被厂商消费、流11实际独立及无声/残留状态必须实车验收。不能声明已实测解决音量联动或原厂步长。

调试车机热点通过外部本地资产debug-car-hotspot.properties一次性迁移：仅精确机型/API、hudtest、debuggable生效；替换旧手机测试配置，之后保留手动编辑。源码无真实密码；正式/调试目录仍隔离。资产缺失时不迁移，不修改系统热点配置本身。

定向回归已先RED后GREEN，评审修正alert误路由及自定义媒体流默认接管问题。完整检查/打包状态见artifacts/analysis/20261106-code104。

## 验证交付

独立评审两项P2（alert自动路由、自定义音乐流默认接管）修复后复审通过；热点独立复审无P1/P2。AGENTS全检查6m52s通过，3633测试/0失败/0错误/1既有跳过；三个模块lint与debug构建通过。独立打包16s通过，构建期间源码快照一致。APK包名、code104、v2签名、ZIP完整性、认证及本地热点资产已验证。

APK：/Users/huliehao/headunit/artifacts/apk/BuickPlay-0.2.21-buick.3-debug-code104.apk
SHA256：ecb589c5a4ffc679ecd287bc4729e39770dde249e5261a6bdeb8ce1a4d737b69

未安装、未提交、未推送。完整采集与独立音量仍待实车验收。
