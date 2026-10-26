# BuickPlay 分支、版本与检出规则

## 当前唯一验收来源

本轮验收分支为 `release/0.2.21-buick.1`，工作区为 `/Users/huliehao/.codex/worktrees/7740/headunit`。其父提交aef0cde2来自 `buickplay/next-diagnostics`。验收code48不得从 `~/headunit/DiPlay` 的kaiyue直接构建。

当前存在两套独立Git仓库：`~/headunit`及其已注册的Codex worktree是一套；`~/headunit/DiPlay`是另一套。后者有未提交改动。目录名相似不代表共享分支和提交；本次不迁移、不重置、不清理任何一套历史。今后以 `git rev-parse --show-toplevel --git-common-dir` 和 `git worktree list` 确认归属。

## 命名

| 类型 | 规则 | 用途 |
|---|---|---|
| 上游基线 | main | 跟踪原项目；不混入主题改造 |
| 定制集成 | buickplay/main（建议后续整理时建立） | 汇总已通过审查的定制改动；当前尚未建立 |
| 开发 | feat/<主题>、fix/<问题> | 一个明确目标，完成后合入定制集成分支 |
| 验收 | release/<定制版本> | 如release/0.2.21-buick.1；仅收验收修复，不新增无关功能 |
| 发布标签 | v<版本>-code<编号> | 唯一固定构建来源，显式发布时创建，不移动已有标签 |

避免新建next、latest、final等无固定含义的分支/版本名；旧分支保留历史，不为整理名称而删除。release分支可依次产生rc.1、rc.2；每次实际交付新APK都增加versionCode。正式版去掉rc后缀，versionCode继续增加。debug构建自动追加-debug，应用名保留-debug，不能把debug签名包当成正式签名包。

## 检出

1. 明确源仓库、基线提交和目标分支。执行 `git status --short`、`git branch -vv`、`git worktree list`；遇到未提交内容不要自动stash/reset/clean。
2. 同一仓库优先使用worktree。一个分支只检出到一个工作区；不要用--force或--ignore-other-worktrees绕过保护。
3. 新任务从明确基线建立，例如在本轮仓库运行：

```sh
git worktree add -b fix/connection-retry ../headunit-connection-retry release/0.2.21-buick.1
```

这会建立修复分支，不改变验收分支现有工作区。路径须先确认不存在。已有分支直接用 `git worktree add <新目录> <分支>`；已经检出的分支直接进入对应目录。

4. 获取远程更新先fetch再审查，不在验收分支盲目pull/合并上游。首次推送明确指定远程和分支，并设置同名upstream：

```sh
git push -u origin release/0.2.21-buick.1
```

本次尚未执行此推送或创建发布标签。不要把分支upstream指向无关的kaiyue等本地分支。已有错误关联需逐一确认后调整，不批量覆盖。

5. 打包前确认工作区干净，运行AGENTS.md完整检查，提交后从固定提交构建。记录应用版本、versionCode、源码提交、包名、签名与APK SHA256。认证材料、签名密码、原始设备日志不提交。
6. 合并或删除旧分支前，确认已合并且无独有提交。当前两套仓库先比较差异、保存未提交改动，再另行制定迁移方案；不要直接移动或删除.git。
