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

当前已交付验收版为0.2.21-buick.1-rc.1-debug/code48，文件名和日志中的版本保留原样。后续无需继续划分rc分支或多个final；以递增code区分每次交付。需要正式发布时才打版本标签，不要求每次调试都建立标签。

## 2026-10-26 一次性整理

code48已验证源码从原独立工作区归入kaiyue，旧kaiyue提交历史通过合并保留，应用源码未改变。旧目录30个未提交/未跟踪文件已完整备份并保存为stash，不直接重放早期实现覆盖新版。

备份位于 `~/headunit/artifacts/repository-backup/20261026-single-kaiyue/`。原根目录Git元数据和Codex工作区归档于此，不再作为日常开发入口。恢复时先读备份内README，不要直接stash pop到当前新版。
