---
type: code-wiki-flows
summary: 六条关键运行链路的分步说明：对话 SSE、流水线、治理确认、部署、项目接入、BugFix 与工作区调度
---

# 核心流程

## 1. 对话链路（HTTP SSE）

入口：[ChatController#chat](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ChatController.java) `POST /api/v1/chat`，`produces=text/event-stream`。

```text
1. 鉴权：Sa-Token 解析 CurrentUser
2. ProjectContextResolver.resolve(projectId, env, userId, worktree)
   - 校验 projectId / env / worktree / readOnly
   - 未指定环境默认 test；环境必须属于该项目，否则拒绝解析
3. AgentStreamLimiter.acquireOrThrow()  单用户并发闸门（内存 / jdbc 租约）
4. ChatService.stream(ctx, sessionId, text, role, ...)
5. AgentFactory.createChat(ctx, skills, attachments, model, connectionId)
   - 选择 Toolkit：prod 或只读 → readOnlyToolkit；否则 agentToolkit
   - 追踪中间件：tool 调用、token 用量、时间线
6. ReActAgent.streamEvents(text, RuntimeContext(ctx))
7. AgentEventMapper.toDto() → SSE "agent" 事件（带递增 id，支持 Last-Event-ID 续传）
8. run.completed / error 后释放闸门与 MCP 资源
```

### 1.1 模型选择顺序

`DynamicModelProvider.current()` → `AgentFactory` 支持：

```text
请求参数 model  >  会话设置模型  >  全局设置（数据库 settings 表）  >  启动配置 agent.model.model  >  内置默认
```

每次请求重新解析，设置页保存后下一轮生效，无需重启。

### 1.2 会话绑定

`ChatSessionRegistry.bindOrVerify(sessionId, ctx)` 保证同一 `sessionId` 不会被复用到别的 `(project, env, user)`，防止会话串写。

### 1.3 SSE 事件协议（稳定 wire type）

前端只依赖下列 type（`version=2`），不依赖 AgentScope 类名：

| type | 时机 |
|---|---|
| `run.started` / `run.completed` / `run.result` | 一轮开始/结束 |
| `text.start` / `text.delta` / `text.end` | 正文流 |
| `assistant.thinking.start/delta/end` | 思考流 |
| `tool.started` / `tool.arguments` / `tool.result.*` / `tool.completed` | 工具调用 |
| `model.call.started` / `model.usage` | 模型调用与用量（含实际模型名） |
| `ask.requested` | `ask_user` 选项卡片 |
| `change.created` | 登记变更单后推送确认卡片 |
| `confirmation.required` / `confirmation.result` | 通用确认 |
| `run.max_iters` / `tools.denied` / `hint` | 迭代上限、全工具拒绝、提示 |
| `error` | 异常（`{message}`） |

实现：[AgentEventMapper](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/AgentEventMapper.java)。心跳由 `SseHeartbeat` 维持，断开由 `ChatRunRecoveryAdvisor` 记录可恢复信息。

## 2. 流水线执行链路

启动方式：

- 工具：`ci_run`（`CiCommandTools`）
- HTTP：`POST /api/v1/ci/pipelines/{pipelineId}/run`
- 定时/触发：`TriggerScheduler`（同项目同环境串行）

```text
BuiltInPipelineEngine.trigger(projectId, env, pipelineId, sha, trigger)
  ├─ checkout：GitWorktreeManager 基于 sha 建/复用 worktree
  ├─ 解析 ci/pipeline.yml（或平台侧定义）→ StageDefinition[]
  ├─ 逐 stage 执行：
  │    ActionSpec → ActionRunner（DockerActionRunner / ProcessActionRunner）
  │    ├─ DockerSandbox 包装：非 root、cap-drop、pids/mem/cpu 限制、只读根
  │    ├─ 环境变量最小注入；日志脱敏（SecretRedactor）
  │    └─ 超时、取消、心跳
  ├─ 阶段产物：日志尾部、JUnit/覆盖率摘要、镜像标签 → RunHistoryStore
  ├─ 部署阶段：Deployers.deploy() → Docker/Host/Server/LocalDevHost
  └─ 结束：RunLedger 记账，SSE/工作区事件通知
```

失败后的自动修复（`AutoRepairCoordinator`）：

```text
失败 → 确定性修复（拉镜像 / 重启 Docker daemon / 清理空间）
     → 平台复核（ImagePullRepair / DockerDaemonRepair）
     → 续跑
     → 仍失败 → AgentDiagnosisRepair（诊断 Agent 只读分析，产出建议或受限补丁）
```

## 3. 治理与确认链路（L3）

变更单生命周期：

```text
1. Agent 调 change.apply / deploy_* 等写工具
2. EnvPolicyService.decide(env, level)
   ├─ DENY    → PolicyDeniedException（E-7xx，前端可读原因）
   ├─ ALLOW   → 直接执行
   └─ CONFIRM → ChangeRegistry.request() 落库，返回 changeId（pending）
3. 前端渲染确认卡片；GET /api/v1/changes/{id} 查详情与补丁
4. 人 POST /api/v1/changes/{id}/confirm（可要求密码再认证）
   → ConfirmTokenService.issue(changeId, planHash)
   → {confirmToken, changeId, planHash, expiresAt}
5. POST /api/v1/changes/{id}/apply + X-Confirm-Token
   → consume() 原子校验：未过期、未使用、planHash 一致
   → 执行 → 返回 RunView
```

关键约束：

- `changeId` / `planHash` 由 `DeployGate.changeId()` / `planHash()` 确定性生成（project+env+sha），配方变化 → hash 变化 → 旧令牌失效。
- `DeployGate.requireIfNeeded()` 在发布前校验：是否已有成功的 test 环境取证，否则 E-6xx。
- Agent 无签发令牌的工具，`confirm` 工具只能登记变更。

## 4. 部署链路

```text
POST /api/v1/workspaces/{projectId}/deployments/{env}/start 或 deploy_* 工具
  → DeployGate.requireIfNeeded(project, env, sha, token)
  → Deployers.deploy(request)（按 target 选具体部署器）
      docker  → DockerDeployer：拉镜像、滚动替换、健康检查
      host    → HostDeployer：发布产物到宿主目录并重启服务
      server  → ServerDeployer：SSH 远程执行发布脚本
      local-dev → LocalDevHostDeployer：本机进程托管
  → 健康检查（HTTP/端口/进程）→ 结果写 RunLedger
  → 失败：证据采集（容器日志、端口、进程）→ 建议修复
```

部署目标未注册时 fail-closed（E-505），不会"静默成功"。

## 5. 项目接入链路

```text
POST /api/v1/projects/intake/local   本地目录 DIRECT 接入
POST /api/v1/projects/intake/remote  远程仓库 clone（bare + worktree）
  → ProjectIntakeService.submit()
  → 异步虚拟线程执行：
       VALIDATING  校验路径/URL/分支/凭证
       LINKING     cloneBare / fetchLocal，建默认 worktree，探测结构
       RECIPE_CHECK 检查 ci 定义，缺少则 RecipeScaffolder 生成平台侧定义
       REGISTERED  写项目注册表；失败 → INTAKE_FAILED（保留原因）
  → GET /api/v1/projects/intake/{projectId} 查询阶段与消息
```

`ProjectReadiness` 汇总"能否开始对话/执行/部署"，前端据此显示阻塞项。

## 6. Bug 自动修复链路

入口：Issue 同步/Webhook 触发 `BugFixOrchestrator.start(issue)`。

```text
ANALYZE        分析 Issue，定位相关代码（只读）
REPRO_DEPLOY   部署复现环境
REPRODUCE      执行复现步骤，确认现象
FIX            生成并应用修复（受策略约束）
VERIFY_DEPLOY  部署验证环境
VERIFY         跑测试/复验
PULL_REQUEST   建分支、提交、开 PR
DONE           关闭 Issue / 通知
```

调度特征：

- 全局并发 `Semaphore(MAX_CONCURRENT_RUNS)`，项目级锁 `projectLocks`。
- 每个 run 用虚拟线程执行，`retryScheduler` 处理瞬时失败。
- `apply()` 按 `BugFixPolicy` 决定该阶段是否需要人工审核；`block()` 进入等待，`resume()` 继续。
- 事件（`BugFixEvent`）与阶段记录（`BugFixStageRun`）持久化，供前端时间线展示。
- `cancel()` 支持取消，`rerunRequested` 支持运行中重排。

## 7. 工作区调度链路（Supervisor）

面向"一个工作区多个会话/任务并发"的场景：

```text
WorkspaceController / WorkspaceSuperController（/api/v1/workspaces/**）
  → ConversationManager 管理会话（创建、绑定项目、状态）
  → WorkspaceDispatchScheduler 依据任务 DAG 派发（并行/串行、优先级）
  → WorkspaceRunExecutor 启动子 Agent 执行
  → WorkspaceEventBus 发布进度，前端 SSE 订阅
  → WorkspaceRunWatchdog 超时/卡死熔断；RunAborter 主动中止
  → WorkspaceSupervisorTools（21 个工具）供调度 Agent 操作会话与任务
```

这是与单会话 `ChatController` 并行的第二套编排入口，共用 `AgentFactory` 与工具面。
