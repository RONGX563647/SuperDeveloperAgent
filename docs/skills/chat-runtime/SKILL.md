---
name: chat-runtime
description: 对话与 Agent 运行时：SSE 事件流、会话注册、角色 Worker、时间线、对话恢复
covers: [ChatService, ChatController, ChatSessionRegistry, AgentFactory, ConversationManager, ConversationTimelineRecorder]
when_to_read: 对话没响应/SSE 断流/会话恢复/角色 Worker/时间线/并发闸门/对话超时
budget: 110
---

# 对话与运行时（chat-runtime）

## 关键文件

| 文件 | 角色 |
|---|---|
| `platform/agent-runtime/.../ChatService.java` | 对话主流程：校验输入 → 建会话 → 事件流 |
| `platform/agent-gateway/.../web/ChatController.java` | `POST /api/v1/chat` 返回 `SseEmitter`（协议见 code-wiki 08 §4） |
| `platform/agent-runtime/.../ChatSessionRegistry.java` | 会话注册与恢复（重启后的对话续接） |
| `platform/agent-runtime/.../AgentFactory.java` | Agent 工厂；`createWorker(ctx, role)` 角色→（工具面/提示/权限）映射 |
| `platform/agent-runtime/.../workspace/` | 工作区调度：ConversationManager、时间线、任务 |

## 关键事实

- SSE 是唯一推送通道：事件类型/断线重连语义在 code-wiki 08 §4，前端归约器在
  `web/src/state/`（`09-frontend.md` §5）——动协议两边必须同改。
- 并发闸门：`agent.chat.limiter`（memory=进程内信号量 / jdbc=SQLite 租约多实例）；
  `timeout-ms` 是单条 SSE 流的最长生命周期（默认 180s，调度入口要等整张 DAG）。
- WorkerRole（PRODUCT/DEV/TEST/OPS，0.2 线引入）：`createDeploy` 是 OPS 特例，
  角色决定工具面——**防回退有 spec-checks 的 `SupervisorToolkitWiringTest` 守着**。
- 事件 Fast Path（Gitee issue/PR → 建任务）零 LLM，白名单外事件直接 `ignored`；
  去重靠 `source_ref`/`trigger_type`（迁移 `V9__worker_role_and_trigger`）。

## 怎么验证

- `ChatServiceTest`、`ConversationManagerTest`、`AgentFactoryTest`、`GiteeWebhookControllerTest`
- SSE 前端侧：`platform/web/scripts/interaction-check.mjs`（需后端 + dev server）

## 找历史

`python tools/docs/find.py --impact ChatService`；`docs/views/modules/agent-runtime.md`。
