# 个人迭代方式

只适配自己的这一台车机，固定一个目录、一个分支逐版迭代。

- **源码目录：** `~/headunit/DiPlay`
- **日常分支：** `kaiyue`
- **安装包、日志和备份：** `~/headunit/artifacts`
- 不再为每项功能、修复、验收新建分支或worktree。旧分支保留为历史，不作为新的开发入口。

## 每一版

1. 进入源码目录，确认分支和未提交内容：

```sh
cd ~/headunit/DiPlay
git branch --show-current
git status --short
```

应在kaiyue；若不是，先检查是否有未保存改动，不要强制切换或清理。

2. 直接在kaiyue修改，按AGENTS.md运行检查。
3. 每次交付新APK增加versionCode：48、49、50……；同一源码重复构建无需加号。APK文件名包含版本和code，不使用finalN。
4. 提交并推送到origin/kaiyue，然后构建固定提交的安装包，记录SHA256。构建认证材料和正式签名材料仅放本地，不提交。
5. 调试包使用BuickPlay-debug并与正式包分开；区别在构建类型，不在分支。保留上一版已验证APK用于回退，不清应用数据换取表面通过。

```sh
git push origin kaiyue
```

当前下一版为 **0.2.21-buick.2-debug / code49**；通过验收后发布 **0.2.21-buick.2**，届时versionCode继续增加。code48曾使用的rc命名仅保留在历史包和日志里，新交付不再使用rc或finalN。

版本格式为 `<上游基线>-buick.<定制迭代号>`，调试构建自动追加`-debug`：

- 上游基线不变：0.2.21-buick.1 → 0.2.21-buick.2 → 0.2.21-buick.3。
- 升级上游基线后，定制迭代号从1重新开始：0.2.22-buick.1。
- 同一目标版本反复调试时，buick迭代号不变，递增versionCode区分每次交付。
- 正式发布后开始下一轮功能迭代，再增加buick迭代号。
- **versionCode是全项目持续递增的整数，基线升级、调试转正式均不重置。** 当前基线只更新了上游提交但版本号未变化时，不重置定制迭代号。
- 需要正式发布时才打版本标签，不要求每次调试都建立标签。

## 2026-10-26 一次性整理

code48已验证源码从原独立工作区归入kaiyue，旧kaiyue提交历史通过合并保留，应用源码未改变。旧目录30个未提交/未跟踪文件已完整备份并保存为stash，不直接重放早期实现覆盖新版。

备份位于 `~/headunit/artifacts/repository-backup/20261026-single-kaiyue/`。原根目录Git元数据和Codex工作区归档于此，不再作为日常开发入口。恢复时先读备份内README，不要直接stash pop到当前新版。
