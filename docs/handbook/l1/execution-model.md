---
type: handbook-l1
level: L1
module: platform
title: 执行模型
summary: 一条对话请求从 SSE 入口到工具执行、状态落盘与上下文裁剪的完整时序
---

# 执行模型

## 一轮对话的时序

1. 客户端 `POST /api/v1/chat`，`ChatController#chat` 建立 `SseEmitter`，
   注册超时/断开取消与心跳，随后订阅 `ChatService#stream` 返回的事件流。
2. `ChatService#stream`：空消息直接失败 → 规范化并校验 `sessionId` 绑定 →
   解析实际生效模型（非法选择不回退默认）→ 组装 `AgentSession` → 构造 `RuntimeContext` → 订阅 `streamEvents`。
3. `AgentFactory#createChat`：按环境的 L2 策略决定权限模式（生产 `DEFAULT`，非生产 `BYPASS`），
   只读用户即便在 dev/test 也只装配只读工具面；工具面 `copy()` 后挂 MCP 与 Skill，最后构建 `ReActAgent`。
4. Agent 循环由 AgentScope 的 `ReActAgent` 驱动：模型输出 tool call → 工具执行 → 结果回填 → 下一轮迭代，
   直到无工具调用或达到 `maxIters`。
5. 事件经 `ChatController#onEvent` 映射为 SSE：文本增量、工具调用/结果、错误；工具结果增量按 `toolCallId` 累积。
6. 每次模型调用结束记录 token 用量；流结束或取消时关闭本会话的 MCP 客户端。

## 关键约束（改这里的代码前必须知道）

- **对话链路没有"用户已确认"的回传通道**：非生产会话用 `BYPASS` 而不是 `ACCEPT_EDITS`，
  否则需要确认的工具会永久停在执行中；真正的门禁在平台层（写工具拒绝生产、变更单、部署令牌）。
- **只读会话不挂 MCP**：MCP 工具没有 `readOnly` 元数据，挂进来等于绕过只读工具面。
- **工具输出有两层截断**：工具侧 `agent.tools.max-output-chars` 先截，会话侧
  `ConversationContextTrimmer#trim` 在落盘前再按预算裁剪；裁剪窗口不能以 TOOL 消息开头。
- **状态按 `(userId, sessionId)` 加密落盘**：运行中的 Agent 不会被就地修改，裁剪返回新的状态对象。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| SSE 入口、心跳、取消 | `ChatController#chat` | [gateway-chat-controller](../l3/gateway-chat-controller.md) |
| 校验输入、绑定会话、驱动事件流 | `ChatService#stream` | [runtime-chat-service](../l3/runtime-chat-service.md) |
| 权限面与工具面组装 | `AgentFactory#createChat` | [runtime-agent-factory](../l3/runtime-agent-factory.md) |
| 环境策略拒绝 | `EnvPolicyService#requireNotDenied` | [governance-env-policy](../l3/governance-env-policy.md) |
| 状态加密落盘 | `EncryptedAgentStateStore#save` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 上下文裁剪 | `ConversationContextTrimmer#trim` | [state-context-trimmer](../l3/state-context-trimmer.md) |

## 旁路

- `ChatService#streamSupervisor`：主管角色，用于拆解/分派，不装配普通聊天工具面。
- `ChatService#structured`：一次性结构化输出（不走 SSE），用于内部生成 JSON 结果的场景。

## 修改指引

- 改主循环行为：`ChatService`、`AgentFactory`；对应测试在 `platform/agent-runtime/src/test`。
- 改工具执行/权限：`agent-tools` 与 `agent-governance`，注意只读角色与生产策略的既有测试。
- 改事件映射：`ChatController#onEvent`，前端 SSE 消费在 `platform/web/src/api/chat.ts`。
