# code63：时钟统一跟随 CarPlay 日夜模式

版本：0.2.21-buick.2-debug / code63。分支 kaiyue，不推送远程。

- 删除“时钟外观”的深色/浅色/自动设置入口及独立配置读取、保存和备份白名单。
- 唯一的透明底时钟只使用现有 CarPlay 日夜计算结果：日间黑字，夜间白字。旧版曾保存的独立时钟外观不再影响显示。
- 按用户追加要求，删除固定白字黑底的第二行，只保留一个时钟。保留开关、80%/100%/120% 字号、24 小时制和左下方定位（原底行位置）。
- 九种语言说明更新；保存日夜模式后返回 CarPlay 生效，无需重新连接。沿用 code62 的外观协议修复，不新增地图外观命令。

## 地图与系统外观边界

Apple 的 CPContentStyle 文档明确地图内容样式独立于用户界面样式；WWDC23 车辆系统介绍也明确支持独立控制地图外观。因此不能将“CarPlay 侧栏深色”理解成“所有地图必定深色”。

- https://developer.apple.com/documentation/carplay/cpcontentstyle
- https://developer.apple.com/videos/play/wwdc2023/10150/

用户反馈当前高德导航日夜设置为“自动／跟随系统”，iPhone 也使用自动外观。没有取得该版本高德对 CarPlay 内容样式的具体处理证据，不能断言它只跟随手机，也不能保证三者自动切换时刻一致。本次工作过程中用户已将 CarPlay 日夜模式改为跟随 Android 系统；安装后 10:20 读取确认值为 system，小米报告日间，单行时钟为黑字、CarPlay 侧栏为浅色。该设置不等于跟随 iPhone 系统。助手未修改该偏好。后续如要统一地图外观，应先核对高德实际配色来源，再评估独立地图协议，避免重引入 code62 刚移除的固定外观声明冲突。

## 验证

回归覆盖旧独立偏好不再覆盖 CarPlay 昼夜、只剩开关和字号、单行文字及透明背景、宽屏/紧凑/阿拉伯文大字号截图。最终完整 CI（4m7s）、三个模块 Lint/构建和 standalone 认证检查通过：3479 tests / 0 failures / 0 errors / 1 skipped。

证据：`../artifacts/analysis/clock-follow-code63-20261029/`。

安装：已覆盖安装到小米 10014df30410，核对版本 code63；10:20:25 进入 CarPlay。实机截图 `device-single-clock.png` 确认单行透明时钟及保留字号 120%。

APK：`../artifacts/apk/BuickPlay-0.2.21-buick.2-debug-code63-20261029.apk`

SHA256：`20c9a1479686335170ecafc12609523a7d056f312eb06beff6c3cf9b4c9b79f8`
