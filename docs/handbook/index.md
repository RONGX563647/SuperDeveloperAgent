---
type: handbook-index
level: L0
title: Harness Handbook
summary: 三级工程文档导航：L1 系统总览、L2 阶段、L3 行为到源码位置
---

# Harness Handbook

给"要改运行时行为"的人用的三级文档：先看全局，再看阶段，最后按行为直接跳到源码位置。

## 阅读路径

| 你要做的事 | 从这读 |
|---|---|
| 刚接手项目 | [L1 系统总览](l1/system-overview.md) → [执行模型](l1/execution-model.md) |
| 改一轮对话/事件流 | [L2 Agent 执行链路](l2/runtime-agent-loop.md) → [L3 ChatService](l3/runtime-chat-service.md) |
| 改工具或权限 | [L2 工具执行](l2/tool-execution.md) → [L2 治理](l2/governance-policy.md) |
| 改流水线/部署 | [L2 流水线](l2/pipeline-stages.md) → [L2 部署配方](l2/deploy-recipe.md) |
| 改会话状态/上下文 | [L2 状态与上下文](l2/session-state-context.md) |
| 改项目接入 | [L2 项目接入](l2/project-intake.md) |
| 提交/推送被门禁拦下 | [L2 质量门禁](l2/quality-gates.md) |

## L1 系统总览

- [system-overview](l1/system-overview.md)：模块职责、依赖方向、三条主干链路
- [execution-model](l1/execution-model.md)：一条请求从 SSE 到状态落盘的完整时序
- [data-flow](l1/data-flow.md)：工作区目录、会话状态、运行证据的存储约定

## L2 阶段与组件

| 阶段 | 文档 |
|---|---|
| Agent 主循环 | [runtime-agent-loop](l2/runtime-agent-loop.md) |
| 工具执行与工具面 | [tool-execution](l2/tool-execution.md) |
| 动作分级与治理 | [governance-policy](l2/governance-policy.md) |
| 流水线阶段 | [pipeline-stages](l2/pipeline-stages.md) |
| 会话状态与上下文 | [session-state-context](l2/session-state-context.md) |
| 项目接入 | [project-intake](l2/project-intake.md) |
| 部署配方与发布 | [deploy-recipe](l2/deploy-recipe.md) |
| 质量门禁与修法 | [quality-gates](l2/quality-gates.md) |

## L3 实现单元

L3 的"行为 → 源码位置"表由代码锚点生成，行号不需要手工维护：

| 文档 | 覆盖行为 |
|---|---|
| [runtime-chat-service](l3/runtime-chat-service.md) | 聊天主流程 |
| [runtime-agent-factory](l3/runtime-agent-factory.md) | 工具面与权限组装 |
| [gateway-chat-controller](l3/gateway-chat-controller.md) | SSE 入口与事件下发 |
| [governance-env-policy](l3/governance-env-policy.md) | 环境策略拒绝 |
| [gateway-deploy-gate](l3/gateway-deploy-gate.md) | 部署证据与确认令牌 |
| [executor-pipeline-engine](l3/executor-pipeline-engine.md) | 流水线触发 |
| [state-agent-state-store](l3/state-agent-state-store.md) | 会话状态落盘 |
| [state-context-trimmer](l3/state-context-trimmer.md) | 上下文裁剪 |
| [infra-project-intake](l3/infra-project-intake.md) | 项目接入入口 |
| [infra-git-worktree](l3/infra-git-worktree.md) | Worktree 创建 |
| [tools-deploy-tools](l3/tools-deploy-tools.md) | 部署状态查询 |

## 维护方式

1. 在方法/类型声明上方加一行：`// @handbook <doc-slug>/<anchor> | <中文行为>`。
2. `python tools/handbook/build.py sync`：刷新 L3 生成区与 `manifest.json`，不会覆盖人工段落。
3. `python tools/handbook/build.py check`：锚点删除、符号改名、行号移动、生成区未刷新都会失败。
4. pre-push 与 `spec-checks` 的 `HandbookDriftTest` 都跑同一个 `check`；禁止手改 `generated:begin/end` 之间的内容。

文档只增不改，行为和源码位置的变更通过重新 `sync` 反映；会话留痕（`docs/ledger`）记录"哪次会话改了什么"，
本手册记录"现在的行为落在哪"。
