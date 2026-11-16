---
type: reference
summary: "管理端 API 与 SSE 协议"
code_refs:
  - platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web
---
# API 索引

> **事实源**：springdoc 从控制器实时生成的 OpenAPI（`GET /v3/api-docs`），提交副本为
> `platform/agent-gateway/src/main/resources/api/openapi.json`。文档 UI 在 `GET /doc.html`
> （springdoc 自带 Swagger UI；Knife4j 4.x 与 Boot 4 不兼容）。两者默认要求登录 + `settings:read`。
> 快照生成、Agent 查询工具（`api_search` / `api_get` / `api_schema`）与 MCP 服务见
> [api-tools.md](api-tools.md)。

## 1. 通用约定

| 项 | 约定 |
|---|---|
| 前缀 | `/api/v1` |
| 认证 | `Authorization: Bearer <Sa-Token>`，登录 `POST /auth/login`，令牌有效期 30 天 |
| 错误体 | `{ "code": "E-xxx", "message": "...", "traceId": "..." }` |
| 权限 | `ADMIN/DEVOPS/VIEWER` 角色权限码；高危变更需二次密码，明文回显需单独的 `settings:reveal` 权限（不是二次密码本身） |
| 流式 | `text/event-stream`，事件类型见下 |
| 幂等 | webhook 以 commit 去重；变更确认令牌一次性 |

## 2. 接口分组

| 分组 | 代表接口 | 权威文档 |
|---|---|---|
| 认证 | `/auth/login`、`/auth/me` | Code Wiki §6 |
| 对话 | `POST /chat`（SSE） | §6 + `src/api/chat.ts` |
| 工作区 | `/workspaces/{pid}/conversations`、`/super/stream`、`/events` | §6 + `src/api/workspace.ts` |
| 运行账本 | `GET /run-ledger` | §6 |
| 流水线 | `/ci/pipelines`、`/ci/runs/{id}`、`/ci/runs/{id}/log` | §6 |
| 部署 | `/deploy/*`、`/actions/*` | §6 |
| 变更 | `/changes/{id}/confirm`、`/changes/{id}/apply` | §6、[ADR-0005](../adr/0005-security-boundary-platform.md) |
| 缺陷修复 | `POST /bug-fix/runs`、`GET /bug-fix/runs/{id}`、`GET/PUT /bug-fix/policy` | §6 |
| 设置 | `/settings/servers`、`/settings/models`、`/settings/projects/{id}/secrets`、`/settings/mcp-servers`、`/settings/skills` | §6 + [设置设计](../settings-server-model-v1.md) |
| 项目接入 | `/projects/intake/*`、`/projects/*` | §6 |
| 运维 | `/actuator/health`、`/actuator/metrics` | `application.yml` |

## 3. SSE 事件

| 流 | 事件 |
|---|---|
| `/chat`、`/workspaces/{pid}/super/stream` | `run.started` / `run.completed` / `run.result` / `text.start` / `text.delta` / `text.end` / `assistant.thinking.start` / `assistant.thinking.delta` / `assistant.thinking.end` / `tool.started` / `tool.arguments` / `tool.result.start` / `tool.result.delta` / `tool.result.data` / `tool.completed` / `confirmation.required` / `external.required` / `hint` / `error`（稳定协议 v2，见 `AgentEventMapper`） |
| `/workspaces/{pid}/events` | 会话与运行状态变更（`conversation.*`、`run.*`），前端就地更新 |

客户端约束：断线重连用指数退避；**事件只作提示**，恢复时以 REST 快照为准（见 [ADR-0006](../adr/0006-workspace-events.md)）。

## 4. 变更流程

1. 改控制器 → 重新生成 `openapi.json`（`ApiDocsSnapshotTest -Dapi.docs.update=true`）；
2. 同一个 PR 内更新接口表或 [api-tools.md](api-tools.md) 中受影响的事实；新增接口必须写 Javadoc 或 `@Operation(summary)`；
3. 有破坏性变更（删字段/改语义）→ 在 [changelog](../changelog/2026-11.md) 标注，并更新前端 `src/api/*`；
4. 涉及权限或写操作 → 检查是否绕过 `ActionService` 门禁（规约禁令 R 系列会拦截）。
