# CarPlay 认证改用车机板载 MFi 芯片 — 可行性调研（2026-10-12）

> 状态：**待实车验证的候选方案**。现有笔记指向 `/dev/i2c-3`、地址 `0x10`，但尚未在当前车机上验证应用访问、芯片版本、证书读取、挑战签名及 iPhone 完整认证。节点权限检查只能作为前置检查，不能据此判断完整 CarPlay 链路已经可用。

## 背景

- 源码构建的 DiPlay 不含官方 APK 内置的"实验性软件身份"（构建时私有注入），自建包连不上 CarPlay。
- 此前备选：a) 用车机板载 MFi 芯片做认证（本文）；b) 从官方包提取身份资产本机注入。
- 车机：FFKJ FF-5000 / AC8227L / Android 8.1。此前逆向 ATC 原栈确认：MFi 协处理器在
> **`/dev/i2c-3`，7 位地址 `0x10`**（libcoprocessor.so 调用路径）。

## DiPlay 侧证据（v0.2.18 源码）

1. **认证目标四选一**：LOCAL（软件身份）/ USB_CH341（外接认证头）/ **I2C（板载芯片）** / REMOTE。
   设置界面含"I2C 设备"路径输入框（全语言文案齐全），会话建立时
   `CarPlayController.kt:919` 执行 `LinuxI2cTransport.open(路径)`。
2. **扫描地址恰好命中**：`MfiDeviceScanner` 按 **0x10 → 0x11** 探测（写寄存器选择 0x00 + STOP + 纯读，
   2 秒窗口），首个候选即本车芯片地址。
3. **协议同源**：`MfiAuthenticationClient` 实现 LIVI 规范（Apple MFi 认证协处理器标准 I2C 寄存器协议：
   protocolMajor / readCertificate / signChallenge）。ATC 原栈的有线 CarPlay 能过真机认证，
   其协处理器必走同一 Apple 规范——两边为同一协议的两种实现。
4. **自带自检工具**：内部页 `MainActivity.runSelfCheck(路径)` → `MfiSelfCheck`（扫描 → 读版本 →
   协议握手），不需连 iPhone 即可判定芯片通不通。debug 包可 `am start` 拉起。
5. AirPlay 侧身份为软件生成 Ed25519（`AirPlayIdentity.generate()`），与芯片认证并行、无冲突。

## 车机侧前置检查：节点访问权限

DiPlay 文档原话："目标构建必须授予本应用节点访问权限，不绕过 SELinux 与 Unix 设备权限"。
（`LinuxI2cTransport` 类注释同义。）车机上线后执行：

```sh
adb shell ls -l /dev/i2c*                          # 0666/组可读→通；root 0600→不通
adb shell getenforce                               # Permissive→只看权限位；Enforcing→大概率拦 untrusted_app
adb shell cat /sys/bus/i2c/devices/3-0010/name     # 确认 0x10 挂载的芯片/驱动
```

## 判定与后续

| 结果 | 动作 |
|---|---|
| 节点可访问 | 设置 → 认证目标=I2C、路径 `/dev/i2c-3` → 拉自检页验证 → 直接连 CarPlay。零系统改动，用原厂授权芯片 |
| 节点不可访问 | 应用层无解（改 ueventd/SELinux 属系统修改，不做）；退回方案 b：官方身份资产本机注入（`DIPLAY_AUTH_ASSETS_DIR` 构建注入，仅自用不分发） |

## 红利与风险

- **红利**：芯片认证不依赖打包身份 → **debug 包（.hudtest）也能连 CarPlay**；正式版出包永远不需要
  官方那份身份资产；认证凭证属于这台车机本身，干净合规度最高。
- **待验证风险**：原 ATC PhoneLink（uid 1000）也可能使用该芯片；芯片是否空闲、是否存在并发访问冲突需要实车确认。
- 三条命令和自检页只验证前置条件；最终仍以证书、签名及 iPhone 完整连接为准，尚无实测耗时结论。

## 验证顺序（车机在线时）

1. 三条探测命令（上节）
2. 权限通 → `adb shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.MainActivity`
   跑 I2C 自检（/dev/i2c-3），看 scan/protocolMajor 结果
3. 设置 → MFi 目标 = I2C、路径 /dev/i2c-3 → 连 iPhone 验证完整 CarPlay
4. 成功后正式版将 I2C 模式设为本车默认，问题闭环
