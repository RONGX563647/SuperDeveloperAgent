# API 索引

> **事实源**：控制器代码（`agent-gateway/src/main/java/top/rongx/agent/gateway/web/*Controller.java`）+ [Code Wiki §6](../Code-Wiki.md) 的接口表。
> 本平台**未引入 springdoc/OpenAPI**，刻意避免「生成物」与「控制器」双源不一致；如需对外发布，再按控制器手工收敛一版 `openapi.yaml` 并纳入本目录。

## 1. 通用约定

| 项 | 约定 |
|---|---|
| 前缀 | `/api/v1` |
| 认证 | `Authorization: Bearer <Sa-Token>`，登录 `POST /auth/login`，令牌有效期 30 天 |
| 错误体 | `{ "code": "E-xxx", "message": "...", "traceId": "..." }` |
| 权限 | 角色权限码校验；高危操作需二级密码（`settings:reveal` 等） |
| 流式 | `text/event-stream`，事件类型见下 |
| 幂等 | webhook 以 commit 去重；变更确认令牌一次性 |

## 2. 接口分组

| 分组 | 代表接口 | 权威文档 |
|---|---|---|
| 认证 | `/auth/login`、`/auth/me` | Code Wiki §6 |
| 对话 | `POST /chat`（SSE） | §6 + `src/api/chat.ts` |
| 工作区 | `/workspaces/{pid}/conversations`、`/super/stream`、`/events` | §6 + `src/api/workspace.ts` |
| 运行账本 | `GET /run-ledger` | §6 |
| 流水线 | `/ci/pipelines`、`/ci/runs/{id}`、`/ci/runs/{id}/logs` | §6 |
| 部署 | `/deploy/*`、`/actions/*` | §6 |
| 变更 | `/changes/{id}/confirm`、`/changes/{id}/apply` | §6、[ADR-0005](../adr/0005-安全边界放在平台层.md) |
| 缺陷修复 | `/bugfix/issues/{id}/fix`、`/bugfix/runs/{id}` | §6 |
| 设置 | `/settings/servers`、`/settings/models`、`/settings/secrets` | §6 + [设置设计](../设置-服务器与模型连接-v1.md) |
| 项目接入 | `/intake/*`、`/projects/*` | §6 |
| 运维 | `/actuator/health`、`/actuator/metrics` | `application.yml` |

## 3. SSE 事件

| 流 | 事件 |
|---|---|
| `/chat`、`/workspaces/{pid}/super/stream` | `turn_start` / `text` / `thinking` / `tool_call` / `tool_result` / `approval_required` / `turn_end` / `error` |
| `/workspaces/{pid}/events` | 会话与运行状态变更（`conversation.*`、`run.*`），前端就地更新 |

客户端约束：断线重连用指数退避；**事件只作提示**，恢复时以 REST 快照为准（见 [ADR-0006](../adr/0006-工作区事件驱动与降级.md)）。

## 4. 变更流程

1. 改控制器 → 同步 Code Wiki §6 表格（同一 PR）；
2. 有破坏性变更（删字段/改语义）→ 在 [changelog](../changelog/2026-11.md) 标注，并更新前端 `src/api/*`；
3. 涉及权限或写操作 → 检查是否绕过 `ActionService` 门禁（规约禁令 R 系列会拦截）。
