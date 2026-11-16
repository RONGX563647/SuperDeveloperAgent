---
type: handbook-l1
level: L1
module: platform
title: 全局数据流
summary: 工作区目录、会话状态、运行记录与证据的存储位置约定了哪些数据流向哪里
---

# 全局数据流

## 工作区目录约定

平台运行数据默认在 `agent.workspace.root`（`./data/workspaces`）下，按项目隔离：

| 路径 | 内容 | 说明 |
|---|---|---|
| `<workspace>/<project>/git` | 平台 bare 仓库 | 远程 clone 或从用户目录收编得到 |
| `<workspace>/<project>/worktrees/<name>` | 平台 worktree | `wt-verify`、`wt-build-*`，按 sha detached 检出 |
| `<workspace>/_platform/agent-state` | 会话状态 | 按 `(userId, sessionId)` 分目录，加密落盘，权限收紧到属主 |
| `<workspace>/_platform/deploy/<project>-<env>` | HOST 部署状态 | `app.pid` 等，停止遗留进程只认这里 |
| `<workspace>/_platform/git-askpass.*` | 凭据注入脚本 | 运行时从环境变量取值，不落明文 token |

用户目录始终是它自己仓库的主人：平台不把它登记成 bare 的 worktree，
而是通过 `fetchLocal` 把对象同步进 bare，构建与运行都在平台自己的 worktree 里。

## 请求数据流

1. 网关解析 `ProjectContext`（项目、环境、用户、只读角色），作为显式参数向下传递，不用 ThreadLocal。
2. `ChatService` 用 `sessionId` 绑定/校验会话归属，防止跨项目复用会话。
3. Agent 状态由 `EncryptedAgentStateStore` 加密写盘；恢复时解密读回，重启可续接。
4. 落盘前 `ConversationContextTrimmer` 按 `maxChars` + `keepMessages` 裁剪，SYSTEM 消息永远保留。

## 执行与证据数据流

1. 流水线触发后，阶段执行结果写入运行记录；`RunHistoryStore` 负责持久化，重启后 `restoreHistory` 恢复。
2. 每次运行保留日志尾部、阶段结果与产物引用；`status/progress/logTail/artifacts` 都从这里读。
3. 部署配方（`DeployRecipe`）记录版本、证据运行 id、git sha；生产部署必须"证据成功 + 确认令牌"。
4. 会话归档（`docs/process`）记录代码改动与测试证据，与本文档的"行为 → 源码位置"互补：
   归档回答"哪次会话改了什么"，Handbook 回答"现在的行为落在哪"。

## 边界

- 会话状态与部署状态在 `_platform` 下，不属于任何被接入项目的仓库。
- worktree 只由平台创建与清理；用户目录的 `.git` 不会被平台改写（除了 `git remote add agent` 这一处关联）。
- 用户目录被删除时，接入服务把项目标记为待修复，而不是静默重建。
