---
type: handbook-l3
level: L3
module: agent-infra
title: Worktree 创建
summary: 在平台 bare 仓库上按 sha 建 detached worktree，重名先清理
---

# GitWorktreeManager 创建 Worktree

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 平台 worktree 创建 | `GitWorktreeManager#addWorktree` | `platform/agent-infra/src/main/java/top/rongx/agent/infra/git/GitWorktreeManager.java:214-228` | agent-infra |
<!-- generated:end -->

## 行为拆解

1. `bareDir(projectId)` 定位平台 bare 仓库，`worktreesDir(projectId)/<name>` 是目标目录。
2. 先创建父目录；已存在同名 worktree 则 `worktree remove --force` 后重建。
3. `git worktree add --quiet --detach <path> <sha>`：按提交检出，不占用分支。
4. 命名约定：`wt-verify` 为常驻自检，`wt-build-*` 为构建/补丁工作区。

## 状态与副作用

- 在 bare 仓库的 `worktrees/<name>` 下写管理目录，在目标路径写 `.git` 文件。
- 不修改用户目录的仓库；用户代码通过 `fetchLocal` 同步对象进来。

## 失败与降级

- `git worktree add` 失败抛 `GitIntakeException` 并带原始输出；
  目录还在但 `.git` 损坏时由 `removeWorktreeAt` 的 prune 分支兜底。

## 相关测试

`platform/agent-infra/src/test/java/top/rongx/agent/infra/git/GitWorktreeManagerTest.java`。
