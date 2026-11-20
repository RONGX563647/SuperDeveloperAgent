---
type: handbook-l2
level: L2
module: agent-tools
title: 工具面装配与工具调用执行
summary: 负责按环境与角色装配工具面，并把模型的 tool call 落到受控的 @Tool 方法上。
refs:
  - ../l3/runtime-agent-factory.md
  - ../l3/runtime-chat-service.md
  - ../l3/governance-env-policy.md
  - ../l3/tools-deploy-tools.md
---

# 工具面装配与工具调用执行

## 职责边界

描述模型发出的 tool call 如何落到 `@Tool` 方法、工具面如何按环境与角色裁剪、MCP 与 Skill 挂载边界、确认交互为何不在对话链路中阻塞。不负责模型推理与流水线阶段编排。

## 输入

- `RuntimeContext`：`userId`、`sessionId`、`ProjectContext`，由 `ChatService#stream` 组装。
- 会话请求：`skills`、`mcpServers`，null 表示设置页默认，空列表表示本会话全关。
- 环境与角色：`ProjectContext#env`、`ProjectContext#readOnly`。
- 工具 Schema：`Toolkit` 中已注册的 `@Tool` 描述与参数。

## 输出

- `ToolCallStartEvent`、`ToolResultTextDeltaEvent`、`ToolResultEndEvent`，由控制器转发为 SSE。
- 工具副作用：只读查询；受限写操作产出变更单或补丁；部署动作产出 runId。
- 变更卡补充事件：`change.created`、`env_binding.created`。

## 关键状态与流转

1. `HarnessAgent` 依据 `Toolkit` 暴露的 schema 选择工具，框架把 `RuntimeContext` 中的 `ProjectContext` 注入 `@Tool` 方法参数。
2. `ToolCatalog` 装配四个工具面：`readOnlyToolkit`、`agentToolkit`、`deployToolkit`、`supervisorToolkit`。
3. `AgentFactory#createChat` 先 `PolicyPort#decide(ctx.env(), ToolLevel.L2_EXECUTE_ISOLATED)`；DENY 或 `ctx.readOnly()` 时使用只读工具面，否则使用完整工具面。
4. 工具面按会话 `copy()`，避免把本会话 MCP 工具注册进共享单例。
5. 只读会话不挂 MCP：MCP 工具没有只读元数据，挂入会绕过只读工具面。
6. 非只读会话按设置页已启用集合取交集挂载 Skill 与 MCP；单服务失败只降级跳过。
7. 工具返回后控制器按 `toolCallId` 聚合结果，核对 `changeId` 或 `bindingId` 后推送确认卡事件。

确认交互：对话链路没有通用的“工具审批”回传通道，因此非生产环境使用 `PermissionMode.BYPASS`。若使用 `ACCEPT_EDITS` 或 `DEFAULT`，`readOnly=false` 的工具会停在无回传的 ASK，`request_confirmation`、`ci_run` 会永远停留在执行中。例外是变更卡 / 绑定卡：它们的确认结果会由 `ChatView.continueAfterUserAction` 回灌成一条新 user 消息（成功与失败都会回灌；流式输出中先排队），但这只覆盖这两类卡片，不是通用工具审批。真正的门禁在平台层：写工具拒绝生产、变更单人工确认、部署要 binding_id。

## 失败与降级

- 环境策略 DENY：`ActionService` 抛 `PolicyDeniedException`（E-707）。
- 破坏性 L2 动作：登记变更并抛 `ConfirmationRequiredException`，不执行。
- 工具参数非法：返回 `err:` 文本，模型可修正，不中断整轮。
- MCP 初始化或注册失败：关闭该客户端并继续，不阻断对话。
- 工具结果累积超过 64k 截断，避免异常输出撑爆内存。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| 工具面装配 | `ToolCatalog#readOnlyToolkit` / `#agentToolkit` | [runtime-agent-factory](../l3/runtime-agent-factory.md) |
| 按环境裁剪工具面 | `AgentFactory#createChat` | [runtime-agent-factory](../l3/runtime-agent-factory.md) |
| 变更与确认工具 | `ChangeTools#requestConfirmation` | [runtime-chat-service](../l3/runtime-chat-service.md) |
| 动作门禁与直接执行 | `ActionService#run` | [governance-env-policy](../l3/governance-env-policy.md) |
| 部署工具只读查询 | `DeployTools#envStatus` | [tools-deploy-tools](../l3/tools-deploy-tools.md) |

## 修改指引

- 改工具面组成：先看 `platform/agent-tools/src/main/java/top/rongx/agent/tools/ToolCatalog.java`，对应 `platform/agent-gateway/src/test/java/top/rongx/agent/gateway/ToolChainTest.java`。
- 改环境与角色裁剪、MCP 挂载：先看 `platform/agent-runtime/src/main/java/top/rongx/agent/runtime/AgentFactory.java`，对应 `ActionUsabilityTest.java`、`LiveModelToolEvalTest.java`。
- 改动作门禁：先看 `platform/agent-tools/src/main/java/top/rongx/agent/tools/ActionService.java`，对应 `platform/agent-governance/src/test/java/top/rongx/agent/governance/EnvPolicyServiceTest.java`、`DangerousActionPolicyTest.java`。
- 改部署工具：先看 `DeployTools.java`，对应 `platform/agent-tools/src/test/java/top/rongx/agent/tools/DeployToolsConfigTest.java`。
