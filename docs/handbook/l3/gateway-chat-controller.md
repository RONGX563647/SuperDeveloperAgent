---
type: handbook-l3
level: L3
module: agent-gateway
title: SSE 对话入口
summary: 建立 SSE、注册取消与心跳，把 Agent 事件流转成客户端事件
---

# ChatController SSE 入口

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| SSE 对话入口与事件下发 | `ChatController#chat` | `platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ChatController.java:132-239` | agent-gateway |
<!-- generated:end -->

## 行为拆解

1. 解析项目上下文与模型选择，建立 `SseEmitter`。
2. 注册 `onTimeout` / `onError` / `onCompletion`：任一触发都取消上游订阅，避免 Agent 继续空跑。
3. 启动心跳，在模型思考或工具执行期间保活，防止代理按空闲超时断流。
4. 订阅 `ChatService#stream`，交给 `onEvent` 映射为文本增量、工具调用/结果与错误事件。
5. 工具结果是流式增量：按 `toolCallId` 累积，结束后再判断是否包含变更单号或绑定 id。

## 状态与副作用

- 每次请求维护 `seq`（事件序号）、`failed` 标记、`toolNames/toolResults` 累积表。
- 连接断开后不再向 emitter 写入。

## 失败与降级

- 流内异常：`sendError` 输出可读错误并结束 emitter，同时取消上游。
- 超时：取消上游并结束；心跳线程随订阅一起停止。

## 相关测试

`platform/agent-gateway/src/test/java/top/rongx/agent/gateway/web/` 下的控制器测试
（如 `ChatRunRecoveryAdvisorFormatTest`）。
