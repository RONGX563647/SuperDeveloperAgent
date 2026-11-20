---
type: handbook-l3
level: L3
module: agent-runtime
title: 聊天主流程
summary: 一轮对话的校验、会话绑定、Agent 组装与事件流
---

# ChatService 聊天主流程

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 聊天主流程：校验输入 → 绑定会话 → 组装 Agent → 事件流 | `ChatService#stream` | `platform/agent-runtime/src/main/java/top/rongx/agent/runtime/ChatService.java:81-110` | agent-runtime |
<!-- generated:end -->

## 行为拆解

1. 空消息在进入 Agent 前直接失败，避免无意义的模型调用。
2. `sessionId` 缺失时生成新值，`ChatSessionRegistry#bindOrVerify` 校验它没有被绑定到别的项目/环境。
3. 解析实际生效的模型；非法选择在这里失败，不回退到默认模型。
4. `role=deploy` 走部署 Agent，其余走 `AgentFactory#createChat`。
5. 事件流由 `HarnessAgent#streamEvents` 驱动，`doFinally` 负责关闭本会话的 MCP 客户端。

## 状态与副作用

- 会话绑定写入 `ChatSessionRegistry`（跨项目复用会被拒绝）。
- 每个 `ModelCallEndEvent` 记一条 token 用量（会话累计 + 日志）。
- 不在本类直接写状态存储；状态落盘由 `EncryptedAgentStateStore` 负责。

## 失败与降级

- 输入为空、模型非法：返回 `Flux.error`，不进入 Agent 循环。
- 客户端取消或上游异常：`doFinally` 仍会关闭 MCP 资源，避免连接泄漏。

## 相关测试

`platform/agent-runtime/src/test/java/top/rongx/agent/runtime/` 下的
`ChatSessionRegistryTest`、`DynamicModelProviderTest`。
