# 凯越车机定制分支（kaiyue）— 同步与改造规则

本仓库是 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)（`upstream`）的 fork
（`origin` = [HuLieHao/DiPlay](https://github.com/HuLieHao/DiPlay)）。
`kaiyue` 分支承载针对 2015 款别克凯越后装车机的二开改造：

- 车机：FFKJ FF-5000 / AutoChips AC8227L / Android 8.1 (API 27) / 1024x600 / MT6627 仅 2.4GHz
- 分支基线：**v0.2.18**（kaiyue 自此分支切出）
- 定制版本：**0.2.18-buick.1**，安装版本码 38（2026-10-13）；见 [发布说明](docs/RELEASE-NOTES-0.2.18-buick.1.md)。

## 分支角色

| 分支 | 角色 |
|---|---|
| `main` | 上游镜像，保持与 upstream/main 一致，**不做任何自定义提交** |
| `kaiyue` | 改造主线；上游发新版时将发布标签 merge 进来 |

## 同步规则（上游发新版时）

只合并发布标签，永远不追 upstream/main（可能处于半重构状态）。

```sh
git fetch upstream --tags
git log --oneline v0.2.18..v0.2.19        # 先评估冲突面
git checkout kaiyue
git merge v0.2.19                          # 解决冲突 → 构建验证 → 实车验证
git push origin kaiyue
```

只要某个修复不要整版：`git cherry-pick <sha>`。
文件级覆盖（`git checkout <tag> -- path`）只允许用于本地从未改过的文件。

## 降低合并痛苦的纪律

1. 定制提交全部落在 `kaiyue`，一主题一提交，message 以 `[kaiyue]` 开头
2. 少改上游现有行：多新增文件/分支判断，能加参数的不内联改逻辑
3. 同步合并提交与功能提交绝不混在一个 commit
4. 每次同步在下方登记：版本、日期、冲突文件、实车验证结果

## 已知事项（本车相关）

- `bydAdbSettings` 的 BYD 门控已放开（kaiyue），分组标题字符串仍为"比亚迪功能 · 需要 ADB"——
  纯文案问题，未动 7 个语言文件以缩小冲突面；介意时可另行提交
- 官方"自动开启热点"开关的权限申请走本地 ADB（127.0.0.1:5555，`appops set WRITE_SETTINGS allow`）；
  本车 adbd 常开，条件天然满足
- 热点助手（kaiyue-headunit-analysis/hotspot-helper）在 DiPlay 链路验证通过后退役

## 同步记录

| 日期 | 动作 | 冲突 | 验证 |
|---|---|---|---|
| 2026-10-11 | 建分支 kaiyue @ main(=v0.2.18) | — | — |
| 2026-10-11 | [kaiyue] 非BYD车机放开车载热点自动化设置入口 | — | 手机(Android11)验证：入口出现/开关/RSA授权/权限落库 ✓；实车待验 |
| 2026-10-12 | 按"最小改动"决定移除 BOOT_COMPLETED 提前开热点：应用内路径在首次 onResume 即发起，端到端仅差 4~6 秒；同时移除两处 review 兜底（ADB 必存，不做多余设计） | — | 已回退，改动面收敛为 1 文件 7 行 |
| 2026-10-12 | [kaiyue] 新增 KAIYUE-I2C-MFI-FEASIBILITY.md：CarPlay 认证改板载芯片可行性调研 | — | 待车机探测（3 命令 + 自检页） |
