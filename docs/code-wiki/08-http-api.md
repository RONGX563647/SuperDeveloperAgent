---
type: code-wiki-http-api
summary: HTTP API 分组、认证与角色权限、统一错误体、SSE 协议与流式注意事项
---

# HTTP API

- 服务端口：`8090`（`server.port`）。
- 所有接口前缀 `/api/v1`（少数 webhook 在 `/api/v1/webhooks/*`）。
- OpenAPI：`/v3/api-docs`；Swagger UI：`/doc.html`（默认需登录 + `settings:read`，`AGENT_API_DOCS_PUBLIC=true` 可放开）。
- 统一响应：普通 JSON；对话与时间线为 `text/event-stream`。

## 1. 认证与角色

登录：`POST /api/v1/auth/login` → 返回 Bearer Token（Sa-Token，30 天）。

```http
Authorization: Bearer <token>
```

[Role](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/auth/Role.java)：

| 角色 | 权限码 |
|---|---|
| `admin` | `*` |
| `devops` | `chat`、`ci:read`、`ci:run`、`action:read`、`action:run`、`change:request`、`change:confirm`、`change:apply`、`settings:read`、`settings:reveal` |
| `viewer` | `chat`、`ci:read`、`action:read` |

- 权限来源：`SaPermissionSource` 从 `UserStore` 读取角色权限。
- 拦截：`SaTokenConfig` 注册 `SaInterceptor`，默认全路径登录校验。
- 首次启动 seed 三个账号（admin / devops / viewer），初始密码统一为 `admin-2026`（[`UserStore.DEFAULT_INITIAL_PASSWORD`](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/auth/UserStore.java)），可用 `AGENT_SEED_PASSWORDS=admin=...,devops=...,viewer=...` 覆盖；登录后到「设置 → 账号安全」重置，不能改回初始密码。

其它认证端点：`POST /auth/change-password`、`POST /auth/logout`、`POST /auth/me`。

## 2. 统一错误体

[GlobalExceptionHandler](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/GlobalExceptionHandler.java)：

```json
{
  "code": 409,
  "message": "需要人工确认",
  "traceId": "uuid",
  "errorCode": "E-602"
}
```

| 场景 | HTTP | 说明 |
|---|---|---|
| 未登录 | 401 | 会话缺失/过期 |
| 无权限 | 403 | 权限码不匹配 |
| 参数/业务校验 | 400 | `IllegalArgumentException` |
| 业务异常 | 由 `ErrorStatusMapper` 按码段映射 | 带 `errorCode` |
| 状态冲突 / 需确认 | 409 | `IllegalStateException`、`ConfirmationRequiredException`（message 含 changeId） |
| Agent 执行失败 | 500 | 带 traceId，日志记录原因 |
| 未知异常 | 500 | 泛化文案 + traceId |

SSE 请求（`Accept` 只有 `text/event-stream`）出错时返回**无 body 的状态码**，避免内容协商 500。

## 3. 接口分组

### 3.1 对话

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/chat` | SSE 对话；body 含 `projectId/env/sessionId/text/model/connectionId/attachments/skills` |
| POST | `/api/v1/workspaces/{projectId}/super/stream` | Supervisor 调度模式（SSE，`{sessionId, message, env}`） |

### 3.2 流水线与构建

基路径 `/api/v1/ci`（`PipelineController`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/pipelines` | 流水线列表 |
| GET | `/pipelines/{id}/preview` | 阶段预览 |
| POST | `/pipelines/{id}/dry-run` | 计划与风险 |
| POST | `/pipelines/{id}/run` | 触发 |
| GET | `/runs/{runId}` | 运行状态 |
| GET | `/runs/{runId}/log`、`/log/lines` | 日志（尾部/分页） |
| GET/POST | `/tags`、`/tags/push` | 发布标签 |
| GET/POST | `/deploy/images`、`/deploy/targets`、`/deploy/host/stop` | 部署辅助 |

### 3.3 部署与环境

- `/api/v1/deploy`（`DeployController`）：`GET /ports`、`GET /{projectId}/envs`、`POST /{projectId}/confirm`、`POST /{projectId}/replay`、`GET /{projectId}/recipes`、`POST /{projectId}/recipes/activate`。
  - `POST /{projectId}/replay` 除 `env/sha/confirmToken/deployTarget` 外还接收可选的 `version/tag/branch`。`version/tag/branch` 必须随重放一起下发：test 环境点主发布会走固化脚本重放（`replayEnv`），缺 `branch` 时引擎在 `requireVersionBranch` 抛「正式发布必须选择版本分支」——即"已打 tag 却提示必须选版本分支"；`deployTarget` 不传则按脚本里记录的目标执行，面板上选的「打包成镜像」会被静默丢弃。`deployTarget` 只接受 `docker/host/server`。
  - 按版本部署（复用历史镜像 tag、跳过构建）走 `POST /api/v1/ci/pipelines/{id}/run`，body 带 `deployImage/gitSha/version/tag/branch/deployTarget/confirmToken`，同样透传 `branch`；prod 还需 DeployGate 校验测试证据后签发的一次性确认令牌。
  - 部署详情元数据以**运行中的容器**为准：`DeploymentControlService.versionOf` 优先取容器 `agent.version` 标签，标签缺失或为无意义的 `latest` 时回落到容器 sha（仅在两者都没有时才用 `latest`），并把残留的 `version=latest` / 无 pid 的纯 HOST 槽（STOPPED 的 "latest" 卡）单独分开，避免与新容器并存时误判为旧版本还在运行。
- `/api/v1/workspaces/{projectId}/deployments`（`WorkspaceDeploymentController`）：`GET`、`POST /{env}/start|stop|restart`、`GET /{env}/logs`。
- `/api/v1/settings/projects`（`ProjectEnvController`）：项目环境与变量。
- `/api/v1/settings/projects/{projectId}/secrets`（`SecretController`）：密钥写入/查看（`settings:reveal` 才可看明文，写审计）。

### 3.4 变更与确认

基路径 `/api/v1/changes`（[ChangeController](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ChangeController.java)）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/{changeId}` | 变更详情 |
| GET | `/{changeId}/patch` | 补丁 |
| POST | `/{changeId}/confirm` | 人工确认 → 返回 `{confirmToken, planHash, expiresAt}` |
| POST | `/{changeId}/apply` | 携带 `X-Confirm-Token` 执行 |

### 3.5 项目接入与仓库

基路径 `/api/v1/projects`：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/intake/validate` | 校验路径/URL |
| POST | `/intake/local` | 本地接入 |
| POST | `/intake/remote` | 远程 clone 接入 |
| GET | `/intake/{projectId}/status` | 接入阶段 |
| GET | `/{id}/envs`、`/{id}/git/status`、`/{id}/git/diff`、`/{id}/git/branches` | 查询 |
| POST | `/{id}/git/sync`、`/{id}/git/checkout`、`/{id}/git/merge`、`/{id}/git/branches` | 操作 |

另有 `ProjectState`、`ProjectReadinessController`（就绪项）、`ProjectScaffoldController`（配方生成）。

### 3.6 工作区与调度

基路径 `/api/v1`（`WorkspaceController`、`WorkspaceSuperController`、`WorkspaceEventController`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/workspaces/{projectId}/conversations` | 会话列表/创建 |
| GET | `/workspaces/{projectId}/super` | 调度工作区 |
| PATCH/DELETE | `/conversations/{id}` | 重命名/归档 |
| POST | `/conversations/{id}/send` | 发送消息（异步运行） |
| GET | `/conversations/{id}/messages` / `PUT` | 历史读取/写回 |
| GET | `/conversations/{id}/timeline` | 时间线 |
| GET | `/conversations/{id}/timeline/stream` | 时间线 SSE |
| GET | `/workspaces/{projectId}/events` | 工作区事件 SSE |
| GET | `/workspaces/{projectId}/runs` | 运行列表 |

任务板相关由 `WorkspaceSuperController` 提供（`workspace_snapshot` / `workspace_plan` / `workspace_dispatch` / `task_*` 对应的 HTTP 入口）。

### 3.7 Issue 与集成

- `/api/v1/git-issues`：列表、计数、详情、创建、更新、关闭/重开、同步、评论、版本。
- `GET /api/v1/git-issues/versions?projectId&kind`：版本号候选项，返回 `{branch, suggested, tags}`。建议值由后端按类型推导（前端不自算）：`kind=BUG`（缺省）取当前版本线（如 `0.1`）已打 tag 的最大补丁号 +1；`kind=FEAT` 取该 major 下已发布的最高次版本 +1、补丁归零（若当前分支次版本未发布则用 `x.y.0`），避免建议一个已打过 tag 的版本；非版本分支下 Bug 退回最新 tag、Feat 从该 tag 往后取一个次版本。建任务时前端把 `suggested` 固定排下拉首项并标「推荐」，切换类型时仅在当前值为空或等于旧建议值时回填。
- `/api/v1/git-issue-images`：Issue 图片。
- `/api/v1/settings/git-integration`：Issue 集成设置。
- `/api/v1/webhooks/git`、`/api/v1/webhooks/gitee`：Webhook（按签名/密钥校验）。
- `/api/v1/settings/git-hosts`、`/api/v1/settings/git-hosts/oauth`、`/api/v1/settings/git-oauth-apps`、`/api/v1/gitee`：Git 托管授权与 OAuth。

### 3.8 BugFix

基路径 `/api/v1/bug-fix`：启动、恢复、取消、查询、合并/PR 关闭、列表、事件、阶段记录。

### 3.9 设置与可观测

| 基路径 | 内容 |
|---|---|
| `/api/v1/settings` | 模型服务器/连接、探测、环境发现、通用设置 |
| `/api/v1/settings/database` | 数据库热切换 |
| `/api/v1/settings/mcp-servers` | MCP 服务器 |
| `/api/v1/settings/skills` | Skill 仓库/安装 |
| `/api/v1/settings/capabilities` | 能力市场 |
| `/api/v1/settings/sandbox/images` | 工具链镜像就绪状态（按 host + image，只读，不触发拉取） |
| `/api/v1/policies` | 当前环境策略矩阵（只读） |
| `/api/v1/run-ledger` | 统一运行账本 |
| `/api/v1/usage` | token 用量（按会话/汇总） |
| `/api/v1/actions` | 标准动作 |
| `/api/v1/agent` | Agent 配置/画像 |
| `/api/v1/fs`、`/api/v1/gitee` | 目录浏览、Gitee 浏览 |
| `/api/v1/deploy/agent` | 发布 Agent 入口 |

## 4. SSE 协议

`POST /api/v1/chat` 返回 `SseEmitter`：

```text
id: 1
event: agent
data: {"type":"text.delta","version":2,"payload":{...}}

id: 2
event: agent
data: {"type":"tool.started","version":2,"payload":{"name":"ci_run",...}}
```

- `id` 递增，支持 `Last-Event-ID` 续传（尽力而为）。
- 心跳：无业务事件时每 15 秒发注释行（`agent.chat.heartbeat-seconds`）。
- 结束：`run.completed` / `run.result`，或 `event: error`。
- 事件类型见 [核心流程 §1.3](03-core-flows.md)。

流式注意事项：

- 前端用 `fetch` 解析（可带 Authorization 头），不要用原生 `EventSource`（无法加头）。
- 超时：普通 180 秒、调度 600 秒（`agent.chat.timeout-ms` / `super-timeout-ms`）。
- 并发：默认单用户 8 条（`agent.chat.max-concurrent`），`jdbc` 模式用租约支持多实例。

## 5. 兼容与版本

- API 路径带 `/v1`；响应字段以 OpenAPI 快照为准。快照落在 `platform/agent-gateway/src/main/resources/api/openapi.json`，由 [`ApiDocsSnapshotTest`](file:///workspace/platform/agent-gateway/src/test/java/top/rongx/agent/gateway/apidocs/ApiDocsSnapshotTest.java) 守新鲜度（过期快照会被隔离），[`ApiDocContractTest`](file:///workspace/platform/spec-checks/src/test/java/top/rongx/agent/arch/ApiDocContractTest.java) 校验控制器与快照一致；前端 `pnpm check:api-types` 保证 `schema.d.ts` 与快照不漂移。
- SSE 协议 `version=2`：新增事件类型只会以 `agent.<enum>` 兜底，前端忽略未知类型。
- 错误码属于对外契约，新增需登记到 `ErrorCode`，不得复用。
