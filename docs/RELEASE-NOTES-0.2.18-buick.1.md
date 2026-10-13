# BuickPlay 0.2.18-buick.1 — 2026-10-13

基于上游 DiPlay 0.2.18 的非官方凯越个人定制版本，分支为 `kaiyue`。尚未合入上游 0.2.19。

## 改动

- 应用标题、iPhone 设备名称、AirPlay 广播名称和系统常驻通知标题改为 BuickPlay。
- 默认车辆按钮名称为“别克”，图标使用用户提供的 512×512 不透明 PNG。
- 默认制造商和型号为 BuickPlay。已保存值直接读取，不增加 DiPlay/BYD 旧值迁移。
- 非 BYD 车机在车载热点模式下可以找到热点自动化设置；实际开启仍取决于本地 ADB、权限和固件支持。
- 更新热点搜索测试，使其验证凯越分支的现有行为；GitHub Android checks 覆盖 kaiyue 分支。
- versionCode 为 38；调试包名仍为 `com.shihab.diplay.hudtest`，可覆盖之前的测试包。

## 构建与发布

公开源码保留原项目的 GPLv3、AGPLv3 和第三方声明。分发二进制时应同时提供该版本的对应源码与构建说明。别克车标和认证数据不由开源代码许可证授权，公开分发前须单独确认相应权利。详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

不含认证材料的源码调试包：

```sh
./gradlew :mobile:assembleDebug
```

可命名为 `BuickPlay-0.2.18-buick.1-source-debug.apk`。默认认证方式需要外部材料；这个包不能直接复现当前本地软件认证连接，详见 [构建说明](BUILD.md)。

本地自用的独立调试包：

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/local-runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

可命名为 `BuickPlay-0.2.18-buick.1-debug.apk`。该包包含外部注入的认证材料，保留在本地；不将材料、APK 或 Android 签名密钥提交到 Git。

## 上传 APK 到 GitHub 的位置

使用 GitHub Releases 的附件，不将 APK 添加到源码仓库：

1. 仓库首页进入 Releases，选择 Draft a new release。
2. 新标签使用 `v0.2.18-buick.1`，Target 选择 kaiyue。
3. 填写改动、源码对应提交、构建方式、验证结果和认证材料说明。
4. 拖入已确认可以分发的 APK，勾选预发布版本，再发布。

在确认车标及认证材料分发权利前，可先保存草稿或仅发布源码。GitHub 会为标签提供源码下载；该标签必须指向构建 APK 使用的同一份源码。

## 验证范围

已在 Android 11 调试手机上验证无线 CarPlay、车辆按钮及系统通知名称。凯越 Android 8.1 车机上的开机/ACC 恢复、热点自动开启、音频和倒车恢复仍待实车验证。独立底部叠加时钟尚未实现。
