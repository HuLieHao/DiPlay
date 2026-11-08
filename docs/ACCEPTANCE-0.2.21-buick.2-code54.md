# code54：结束蓝牙唤醒实验，恢复十秒兜底

code53 已在红米验证目标手机 ACL_CONNECTED 提前唤醒：取消约 12.6 秒剩余等待，约 5ms 后执行重试，事件后约 4.64 秒首帧恢复。

按实验安排，code54 将调试版普通无线重试等待上限恢复为 10 秒，保留蓝牙提前唤醒、管理页即时刷新、主动断开保护及全部 code52 功能。有线普通重试仍最大 30 秒，专门的等待和启动失败预算不变。

- 版本：`0.2.21-buick.2-debug`，versionCode 54。
- 应用名 `BuickPlay-debug`，包名 `com.shihab.diplay.hudtest`。
- 覆盖安装保留原设置；未推送远程。
- 仅打开 iPhone 蓝牙不保证 ACL 事件到达，因此十秒定时兜底仍保留。
- 详细实测见 [code53 验证记录](ACCEPTANCE-0.2.21-buick.2-code53.md)。FF-5000 实车验证仍待进行。

## 交付

- 红米已从 code53 覆盖升级为 code54，已核对包名和版本并打开管理页。
- 完整 CI、Lint 和 standalone APK 构建通过；3456 个测试，0 失败、0 错误、1 个既有跳过。
- APK：`../artifacts/apk/BuickPlay-0.2.21-buick.2-debug-code54-20261026.apk`
- SHA-256：`834cdedcc16e68a34aa1c8900d120a279db11c8062d2f59a3230d2f67fc3e533`
- 构建记录：`../artifacts/analysis/reconnect-code54-20261026/full-ci.log`。
