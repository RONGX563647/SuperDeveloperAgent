---
type: code-wiki-frontend
summary: Vue3 前端：目录结构、路由与登录守卫、API 层、SSE 客户端、事件归约器、状态与组件划分
---

# 前端架构

技术栈：Vue 3.5、Vite 7、Element Plus、vue-router 4、`@unovis/vue` 图表、TypeScript + `vue-tsc`。包管理 `pnpm@9.15.4`。状态管理仍是 `stores/app.ts` 的 `reactive` 单例（**不引 Pinia**），会话级常驻状态在 `state/*`；API 类型由 openapi-typescript 从后端 OpenAPI 快照生成（`gen:api-types`，CI 的 `check:api-types` 做一致性校验）。

## 1. 目录结构

```text
platform/web/src
├── main.ts / App.vue
├── router.ts                 # 路由 + 登录守卫 + 401 统一处理
├── layouts/
│   ├── AppLayout.vue         # 侧栏 + 页头 + 内容区
│   └── components/           # AppAside、ContextSwitcher、PageHeader、navDrawer
├── views/                    # 7 个页面
│   ├── ChatView.vue          # 对话
│   ├── WorkspaceHomeView.vue # 工作区（调度 Agent + 会话列表）
│   ├── PipelineView.vue      # 流水线
│   ├── ProjectListView.vue   # 项目列表
│   ├── ProjectIntakeView.vue # 接入项目
│   ├── SettingsView.vue      # 设置
│   └── LoginView.vue
├── components/
│   ├── chat/                 # 消息、工具卡片、确认卡片、选项卡片、时间线、思考块…
│   ├── pipeline/             # 阶段时间线、历史、日志、触发条、环境状态带
│   ├── project/              # 项目树、文件预览、就绪项、可拖拽分栏
│   ├── settings/             # 模型、服务器、数据库、MCP、Skill、密钥、用量…
│   └── workspace/            # 部署矩阵
├── composables/              # useAgentChat、useWorkspaceEvents、useRunTracker…
├── state/                    # agentEventReducer、sessionChat、workspaceChat、sessionHistory
├── stores/app.ts             # 轻量全局 store（reactive，无 Pinia）
├── api/                      # 各域 API + SSE + 生成类型
└── utils/                    # 状态文案、流水线标签、Markdown、DeepSeek 计价
```

## 2. 路由

[router.ts](file:///workspace/platform/web/src/router.ts)，全部页面路由级懒加载：

| 路径 | 名称 | 说明 |
|---|---|---|
| `/login` | login | 公开页 |
| `/` | → `/chat` | 重定向 |
| `/chat` | chat | 对话 |
| `/workspace/:projectId` | workspace | 工作区调度 |
| `/pipeline` | pipeline | 流水线 |
| `/projects` | project-list | 项目列表 |
| `/projects/intake` | project-intake | 接入项目 |
| `/settings` | settings | 设置 |
| `/:pathMatch(.*)*` | → `/chat` | 未知路径兜底 |

守卫逻辑：

```text
public 页 → 放行
无 token → /login?redirect=...
有 token 但无身份 → POST /auth/me；失败清 token 回登录
```

任意 API 返回 401：`setUnauthorizedHandler` 统一清身份并跳登录（保留 `redirect`）。标签页标题随路由更新。

## 3. API 层

[api/http.ts](file:///workspace/platform/web/src/api/http.ts) 是统一 fetch 封装：

- 自动附加 `Authorization: Bearer <token>`。
- 统一解析错误体 `{code, message, traceId, errorCode}`。
- 401 触发全局登出。

按域拆分：

| 文件 | 覆盖 |
|---|---|
| `auth.ts` | 登录、登出、改密、当前用户、401 钩子 |
| `chat.ts` | 对话、会话 |
| `sse.ts` | SSE 解码与流式请求 |
| `pipeline.ts` | 流水线、运行、日志、标签 |
| `deploy.ts` | 部署、环境、端口 |
| `change.ts` | 变更详情、确认、应用 |
| `project.ts` | 项目、接入、Git 操作 |
| `workspace.ts` | 会话、时间线、事件、部署矩阵 |
| `issues.ts` | Issue CRUD、评论、同步、版本建议（`kind` 口径，后端推导） |
| `bugfix.ts` | BugFix 运行 |
| `settings.ts` | 模型、服务器、数据库、MCP、Skill、密钥 |
| `action.ts` / `agent.ts` | 标准动作、Agent 配置 |
| `generated/schema.d.ts` | 由 OpenAPI 生成，`check:api-types` 保证不漂移 |

## 4. SSE 客户端

[api/sse.ts](file:///workspace/platform/web/src/api/sse.ts) 使用 `fetch + ReadableStream`，而不是 `EventSource`（需要 POST 与自定义头）。

要点：

- `SseFrameDecoder`：跨 chunk 分帧，支持 `event/id/data/retry`，单帧上限 4 MiB。
- 指数退避重连（500ms → 8s），4xx（除 408/429）不重试。
- `event:error` 帧直接抛错。
- 支持 `Last-Event-ID` 与 `afterSequence` 续传。
- 导出 `streamChatOnce`（POST 单流）与 `streamEvents`（GET 续传）。

## 5. 事件归约器（核心）

[state/agentEventReducer.ts](file:///workspace/platform/web/src/state/agentEventReducer.ts) 把 SSE 事件归约为 UI 投影 `AgentProjection`：

```ts
reduceAgentEvent(current: AgentProjection, rawEvent: AgentEvent): AgentProjection
```

处理的事件（兼容新旧命名）：

| 事件 | 作用 |
|---|---|
| `text.delta` / `assistant.text.delta` / `message.delta` | 追加正文 |
| `assistant.thinking.*` | 思考块 |
| `tool.started` / `tool.arguments` / `tool.completed` | 工具卡片状态与入参/结果 |
| `tool.result.delta` | 工具结果增量 |
| `change.created` / `change.confirmed` / `change.applied` | 变更卡片与状态 |
| `env_binding.created` / `env_binding.confirmed` | 环境绑定卡片 |
| `ask.requested` | 选项卡片 |
| `run.status` / `run.completed` / `run.failed` / `run.cancelled` | 运行状态 |
| `model.usage` | token 用量（含模型名） |
| `error` | 错误提示 |

设计：纯函数、无副作用，`agentEventReducer.test.ts` 单测覆盖；UI 只消费投影，不直接解析原始字段。

## 6. 状态管理

无 Pinia，采用两层：

| 层 | 文件 | 作用 |
|---|---|---|
| 全局 | `stores/app.ts`（`reactive`） | 身份、当前项目/环境、权限模式、会话列表、当前会话；localStorage 持久化 |
| 会话级 | `state/sessionChat.ts`、`state/workspaceChat.ts`、`state/sessionHistory.ts` | 单会话消息、投影、工具活动、历史 |

`composables/useAgentChat.ts` 负责一次对话的完整生命周期：发送、流式接收、心跳静默检测（60s）、用量累计、停止、结束收尾。

## 7. 组件划分

### 对话

- `MessageItem.vue`：消息容器（正文/思考/工具）。
- `ToolCallCard.vue`：工具调用卡片（参数、状态、结果）。
- `ThinkingBlock.vue`：思考折叠块。
- `ApprovalRequestCard.vue`：变更确认（出示 planHash、有效期、确认按钮）。
- `AskUserCard.vue`：`ask_user` 选项卡片。
- `ChatTraceRail.vue` / `ChatTraceView.vue`：右侧轨迹。
- `ChatStatusStrip.vue` / `EnvironmentStatusBar.vue`：状态条与环境。
- `ConversationTimeline.vue`：时间线。
- `ChatIssuesView.vue`：任务看板（版本号选择见下）。
- `MarkdownView.vue`：Markdown 渲染。

### 任务看板与版本号选择（ChatIssuesView）

- 版本候选来自 `GET /api/v1/git-issues/versions?projectId&kind`：`kind=BUG` 建议当前版本线最大补丁 +1、`kind=FEAT` 建议最高次版本 +1（后端推导，前端不自算，避免与打 tag 的版本对不上）。
- `suggested` 固定排下拉第一项并打「推荐」徽标；`el-select` 可搜索、可手输（`allow-create`）。切换任务类型重拉建议值，仅当当前值为空或等于旧建议值时回填，用户手选的值不动。
- 版本筛选的纯逻辑在 `utils/taskVersionFilter.ts`，由零依赖单测锁行为。

### 部署矩阵（DeploymentMatrix，工作区「环境」视图）

- 环境列 × 版本卡片（运行状态、target/server/ports/health、相对时间、「访问↗」、启停按钮）+ 详情抽屉（启停/重启、容器或进程日志跟随、10s 轮询）。
- 「最近保留的镜像」面板展示镜像账本保留策略内的版本，每张卡可按 gitSha 一键重新触发 test 环境构建（不打 tag、覆盖默认部署槽）；部署地址指向应用根路径（前端页面），不再跳 `/actuator/health` 的 JSON。
- 结构回归测试 `DeploymentMatrix.test.ts` 锁死环境网格与镜像面板是两个独立 `v-if`——曾因 `v-else` 与网格错配互斥，导致页面空白（空卡片 bug）。

### 流水线

`TriggerBar`、`StageTimeline`、`RunLog`、`HistoryList`、`EnvStateBand`、`EnvSummary`。

生产「直接部署」入口 `PipelineView.promoteProdVersion`：发布对象只来自**测试环境验证过的版本**（镜像账本候选），先按该版本提交查测试证据并申请一次性令牌（`deployConfirm`），确认后走 `deployImageVersion`（指定镜像 tag 跳过构建 + `deployTarget=server`，生产固定远程）；已有的 git 标签只作展示，不重打 tag。

### 项目

`ProjectTree`、`TreeNodeRow`、`TabbedFiles`、`FilePreview`、`ProjectOverview`、`ProjectReadiness`、`ResizableSplitter`、`useProjectTree.ts`。

### 设置

`ModelPanel`、`ServerPanel`、`DatabasePanel`（热切换）、`McpPanel`、`SkillPanel`、`SecretPanel`、`AccountPanel`、`EnvTemplatePanel`、`ProjectEnvPanel`、`RepoPanel`、`GitOauthAppsPanel`、`UsagePanel`、`SandboxImagePanel`（工具链镜像就绪/退避）。

`SandboxImagePanel` 的展示逻辑单独放在 `components/settings/imageReadiness.ts`（状态标签、退避倒计时、来源文案、失败计数），因此能脱离 Vue 单测——**`retryInSeconds = 0` 只代表“现在可以重试”，不代表已就绪**，这条判断是纯函数测试锁住的。

界面回归脚本在 `platform/web/scripts/*-check.mjs`（`login-check`、`layout-check`、`interaction-check`、`pipeline-ux-check`、`sandbox-images-check` 等），句式统一 `node scripts/<name>.mjs [base] [截图路径]`，需要后端与 dev server 同时在跑。

## 8. 开发与构建

```bash
cd platform/web
pnpm install
pnpm dev            # Vite 开发服务器（默认 5173，代理 /api 到 8090）
pnpm build          # vue-tsc -b && vite build → dist/
pnpm typecheck      # 仅类型检查
pnpm test           # 三个 .test.ts（node --experimental-strip-types）
pnpm gen:api-types  # 从 OpenAPI 快照生成 schema.d.ts
pnpm check:api-types # 校验 schema.d.ts 与 OpenAPI 一致（CI 同款，漂移即失败）
pnpm dev:mock       # 生成 mock 后以 VITE_MOCK=1 启动
```

> **部署后白屏（TDZ）回归**：Vite 的 `manualChunks` 曾按 element-plus / vue / 其余拆成多个 vendor chunk，`element-plus` 与 `@vue/runtime-core`、`@vueuse` 等存在循环引用；浏览器按 ES module 拓扑执行跨 chunk 循环依赖时踩到暂时性死区（`Cannot access '$' before initialization`），Vue 挂不上导致**构建产物白屏**——dev 模式不复现，HTML 与各资源请求全是 200，极易误判成"部署没生效"。现把全部 `node_modules` 依赖收敛到**单一 vendor chunk**，业务代码仍按动态 import 独立分包；规则是 [`utils/chunkSplit.ts`](file:///workspace/platform/web/src/utils/chunkSplit.ts) 的纯函数 `manualChunkOf`，由 `chunkSplit.test.ts` 零依赖回归锁死。

构建产物 `dist/` 由网关 `StaticWebConfig` 托管（单端口部署），生产镜像见 [Dockerfile](file:///workspace/Dockerfile)。

## 9. 约定

- 页面只调用 `api/*`，不拼 URL。
- 所有金额/用量展示走 `utils/deepseekPricing.ts`，避免 double 误差。
- 状态文案集中 `utils/status.ts`，不散落中文字面量。
- 事件类型只认 `AgentEventMapper` 的稳定 wire type；未知类型忽略。
- 组件内不直接读 localStorage，统一走 `stores/app.ts`。
