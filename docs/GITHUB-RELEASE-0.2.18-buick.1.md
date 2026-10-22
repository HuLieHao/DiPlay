# GitHub Release 填写内容

选择已有标签 `v0.2.18-buick.1`，勾选 **Set as a pre-release**。标签保持指向 APK 构建源码，后续文档提交不移动该标签。

## Release title

```text
BuickPlay 0.2.18-buick.1（后装安卓车机个人定制预览版）
```

## Release notes

以下内容可直接复制：

```markdown
基于 DiPlay 0.2.18 的个人别克主题定制版，尚未合入上游 0.2.19。
当前使用第三方后装安卓车机，与凯越原装车机无关。BuickPlay 和 buick.1 是个人主题标识，不代表别克官方产品，也不限定车辆品牌。

### 本次改动
- 应用、iPhone 设备识别、AirPlay 广播和系统下拉通知名称统一为 BuickPlay。
- CarPlay 车辆按钮默认名称改为“别克”，替换为别克三盾图标。
- 默认制造商和型号改为 BuickPlay；已保存的名称直接读取，不做旧值迁移。
- 非 BYD 车机在车载热点模式下可找到热点自动化设置；实际开启取决于本地 ADB、权限和固件支持。
- 版本号 0.2.18-buick.1，versionCode 38；调试包名 com.shihab.diplay.hudtest。

### 设备与验证
- 最低 Android 7.1（API 25）。目标后装车机为 FF-5000 / AC8227L，32 位 ARM、约 1GB RAM / 16GB 存储、1024×600。
- 目标系统版本字段显示 10，实际基础为 Android 8.1（API 27）。已观察到 2.4GHz 联网，5GHz 硬件能力未核实。
- Android 11 调试手机已验证无线 CarPlay、车辆按钮和通知名称；目标后装车机的连接、ACC 恢复、热点和音频仍待实测。
- 完整检查：2503 个测试，0 失败、1 跳过；lint 和 APK 构建通过。
- 独立底部时钟尚未实现。

### 源码与安装包
对应源码标签：v0.2.18-buick.1。
附件以本页面实际提供的文件为准。source-debug.apk 不含认证材料，不能直接复现本地软件认证连接；包含认证材料的本地独立包目前未公开上传。
保留 GPLv3、AGPLv3 和第三方声明；车标及认证材料不由代码许可证授权。
```
