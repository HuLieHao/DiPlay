# code70：缩短首次 TCP 空等

版本0.2.21-buick.2-debug / code70，继承code69全部改动，未推送远程。

用户授权缩短30秒等待以快速重试。首次StartSession发送后等待外部iPhone TCP的上限从30秒改20秒；重复StartSession不刷新期限。TCP已到达后会话握手仍30秒，首帧仍30秒，已显示画面的稳定会话不受启动计时影响；重试退避、次数与5分钟总体停止规则不变。

code67五次成功样本firstTcpAfterStartMs分别1992、312、416、636、1863ms。20秒保留余量，单轮无TCP空等减少10秒；两轮失败的样本理论少20秒，但提前重试可能改变iPhone入网时序，实际收益必须实车验证。较慢入网超过20秒会提前重试，这是本轮权衡；未直接降至5/10秒。

测试：首次TCP20秒、19.999秒接入后完整30秒握手窗口、旧超时取消、重复启动不延长均纳入Watchdog测试。旧实现两项红测已确认。完整CI通过4m11s，tests/failures/errors/skipped=[3502, 0, 0, 1]；Lint、构建和签名验证通过。

APK：BuickPlay-0.2.21-buick.2-debug-code70-20261031.apk
SHA256：4365719cb479d72ca139439a9b43d1d059e4b81c2f997e0cd81c9d9d6c3a610e
未安装到车机，未推送远程。
