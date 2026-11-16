---
type: handbook-l2
level: L2
module: agent-runtime
title: 一轮对话的 Agent 执行链路
summary: 负责把一次 HTTP 对话请求编排成 Agent 事件流，并管理会话、模型与资源释放。
refs:
  - ../l3/runtime-chat-service.md
  - ../l3/runtime-agent-factory.md
  - ../l3/gateway-chat-controller.md
  - ../l3/state-agent-state-store.md
  - ../l3/state-context-trimmer.md
---

# 一轮对话的 Agent 执行链路

## 职责边界

负责把一次 HTTP 对话请求编排成 Agent 事件流：认证、上下文解析、会话绑定、模型解析、Agent 组装、事件下发与资源释放。链路不依赖 ThreadLocal，工具所需的项目上下文经 `RuntimeContext` 显式传递；同一会话的上下文由 `AgentStateStore` 按用户与会话落盘。不负责工具内部实现、环境策略细节、会话状态加密格式与流水线执行。

## 输入

- `ProjectContext`：项目、环境、用户、工作树，由 `ProjectContextResolver` 解析，不来自模型。
- 请求字段：`sessionId`、`message`、`skills`、`mcpServers`、`modelConnectionId`、`modelName`。
- 身份：`CurrentUser#requireUserId`，来自 Sa-Token 会话。
- 模型：`ModelRegistry#resolve` 与 `DynamicModelProvider#activeConfig`。

## 输出

- SSE `agent` 事件：文本增量、工具开始/结果、用量与错误；事件结构由 `AgentEventMapper#toDto` 稳定化。
- 补充事件 `change.created`、`env_binding.created`：仅当工具结果中的编号能在 `ChangeRegistry` 或 `EnvironmentStore` 核对通过。
- 副作用：会话元数据落库、token 用量记录、本会话 MCP 客户端关闭。用量写入失败只记录告警，不影响事件流。

## 关键状态与流转

1. `ChatController#chat` 完成认证、上下文解析、模型合法性校验、会话元数据 `ConversationManager#ensureFromChat`、并发许可 `AgentStreamLimiter#acquireOrThrow`，创建 SSE 发射器。
2. `ChatService#stream` 校验空消息，生成或复用 `sessionId`，`ChatSessionRegistry#bindOrVerify` 拒绝跨项目/环境复用。
3. 模型解析失败立即报错，不回退默认模型。
4. `AgentFactory#createChat` 按环境与只读角色选择工具面并构建 `ReActAgent`；`deploy` 角色走 `createDeploy`。
5. 组装 `RuntimeContext`，显式放入 `ProjectContext`，调用 `streamEvents`。
6. 每个 `ModelCallEndEvent` 经 `TokenUsageRecorder#record` 记账；`doFinally` 调用 `AgentSession#close` 关闭 MCP。
7. SSE 断开、超时或异常统一触发上游 `Disposable#dispose` 与许可释放。

旁路：

- `ChatService#streamSupervisor`：`AgentFactory#createSupervisor`，只装配管理工具与只读工具。
- `ChatService#structured`：以 `ReActAgent#call(text, structuredType, runtime)` 返回强类型 `Msg`。

## 失败与降级

- 空消息、非法模型、会话串项目：直接异常，不进入 Agent。
- 并发超限：429，不构建 Agent、不拉起 MCP。
- 单个 MCP 挂载失败：记录告警并跳过，其余能力继续。
- SSE 超时或客户端断开：取消订阅并释放资源，避免 Agent 与子进程泄漏。
- 错误事件只返回截断后的简短文案，不暴露内部细节。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| SSE 入口与事件下发 | `ChatController#chat` | [gateway-chat-controller](../l3/gateway-chat-controller.md) |
| 一轮对话主流程 | `ChatService#stream` | [runtime-chat-service](../l3/runtime-chat-service.md) |
| Agent 与工具面组装 | `AgentFactory#createChat` | [runtime-agent-factory](../l3/runtime-agent-factory.md) |
| 会话状态落盘 | `EncryptedAgentStateStore#save` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 上下文裁剪 | `ConversationContextTrimmer#trim` | [state-context-trimmer](../l3/state-context-trimmer.md) |

## 修改指引

- 改入口与事件协议：先看 `platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ChatController.java`、`AgentEventMapper.java`，对应 `src/test/java/top/rongx/agent/gateway/web/AgentEventMapperTest.java`、`AgentStreamLimiterTest.java`、`ProjectContextResolverTest.java`。
- 改编排：先看 `platform/agent-runtime/src/main/java/top/rongx/agent/runtime/ChatService.java`、`ChatSessionRegistry.java`，对应 `ChatSessionRegistryTest.java`。
- 改 Agent 与模型：先看 `AgentFactory.java`、`DynamicModelProvider.java`，对应 `DynamicModelProviderTest.java`、`AgentPropertiesTest.java`。
- 改会话持久化与裁剪：先看 `EncryptedAgentStateStore.java`、`ConversationContextTrimmer.java`，对应 `EncryptedAgentStateStoreTest.java`、`ConversationContextTrimmerTest.java`。
