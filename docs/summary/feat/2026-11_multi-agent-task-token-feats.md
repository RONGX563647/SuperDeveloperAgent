# FEAT 项分析与实施方案（任务编码 / 多 Agent / 自动执行 / token 优化）

> 基线：`0.1.0-snapshot`（2026-11-17）。本文只做现状分析与方案设计，不包含代码改动。

## 1. 范围

| 优先级 | FEAT | 目标 |
|---|---|---|
| P1 | 任务编码 + 版本选择 | 新建任务标题带可复用编码；任务绑定版本，默认测试环境当前版本 |
| P2 | 多 Agent 协作 | 调度 Agent 与子 Agent 稳定协作；通过 A2A 分发到本机 Trae / WorkBuddy |
| P2 | 工作区任务派发自动执行 | 工具补全、token 可控、可提前中止、有效果验证 |
| P2 | token 爆炸治理 | 工具渐进加载/插件化、提示词与 skill 压缩、KV cache 命中率可观测 |

## 2. 现状基线

### 2.1 工作区任务

- `WorkspaceTask` 只有 `id / projectId / conversationId / goal / expectedOutput / status / priority / 时间戳`，
  **没有任务编码，也没有版本/环境字段**（`platform/agent-domain/.../conversation/WorkspaceTask.java`）。
- `TaskView` 与之同构；`POST /workspaces/{projectId}/tasks` 只接收 `goal / expectedOutput / priority / conversationId`。
- 任务列表里出现的 `task-46122095b6` 是任务 id（或误填进 goal 的内容），不是可读编码。
- 已有任务编码能力只覆盖 Git Issue：`GitIssue.code` 形如 `FEAT-1 / BUG-3`（提交 `2b02714`）。
  **工作区任务编码是独立缺口**，两者不要共用序列。

### 2.2 多 Agent 协作

- 已具备：`workspace_dispatch`（DAG、`depends_on`、并行度、超时、熔断）与
  `dispatch_issue`（派发到新会话，可指定 env/worktree），见 `WorkspaceSupervisorTools`。
- 已修复：子会话运行结束后会落会话时间线，派发去重按会话隔离（批次 1，`eea143c`）。
- 缺口：
  - 没有外部 Agent 端点抽象，无法分发到本机 Trae / WorkBuddy；
  - 调度 Agent 既规划又执行，缺少"只规划+验收"的边界；
  - DAG 汇总把子 Agent 全量输出带回主上下文，直接推高 token。

### 2.3 派发自动执行

- 能创建运行、取消运行、回写状态（`WorkspaceRunExecutor` + `WorkspaceEventBus`）。
- 缺：任务态工具（改状态、挂产物、请求确认）、token 预算、自动中止判据、验收结果字段。
  没有这些工具，Agent 只能"说完成了"，无法推进状态机。

### 2.4 token 控制

- 已具备：`ProgressiveToolMiddleware` 工具渐进披露——只下发核心工具 + 已激活插件，
  首轮按意图预加载最多 2 个插件，其余通过 `tool_load` 激活；工具清单在会话内保持稳定以命中 KV 前缀缓存。
- 已有 `ToolSchemaBudgetTest` 约束工具 schema 体积。
- 缺口：
  - 写类工具（git 写、派发、部署）仍常驻核心面；
  - skill 正文一次性注入；
  - 没有缓存命中率指标，预算只测 schema、不测真实请求。

## 3. FEAT-1（P1）任务编码 + 版本选择

### 3.1 数据模型

`WorkspaceTask` 增加两个字段（`code` 放最前，保持可读）：

```java
public record WorkspaceTask(
        String id,
        String code,             // 项目内自增：TASK-1 / TASK-2，创建后不可变
        String projectId,
        String conversationId,
        String goal,
        String expectedOutput,
        TaskStatus status,
        int priority,
        String releaseVersion,   // 快照版本，如 0.1.2；空 = 未绑定
        boolean followTestVersion,// true = 始终跟随测试环境当前版本
        long createdAt,
        long updatedAt) { }
```

- `code` 由 `ConversationManager.createTask` 统一分配（按项目取最大序号 +1），不在前端拼接；
  旧数据读取时 `code` 回退为 `id`，列表展示不出现空编码。
- `releaseVersion` 创建时写入**快照值**，避免任务执行期间版本漂移；
  `followTestVersion=true` 时执行前再解析一次测试环境当前版本。

### 3.2 版本来源

- 复用 `DeploymentControlService.matrix(projectId)`：取 `env=test` 的 `current` 作为默认版本；
  该接口已能汇总 HOST / Docker 产物并标记当前版本。
- 新建任务请求增加 `releaseVersion`、`followTestVersion`；不传则服务端取默认值。
- 派发与发布时把 `taskCode + version` 透传进运行参数，运行记录可反查任务。

### 3.3 展示与复用

- 任务列表标题格式：`TASK-1 · <goal 摘要>`；任务详情展示编码徽标与版本。
- 复用面：
  - git 提交信息前缀 `TASK-1: ...`；
  - 归档目录与 summary 文件名可带编码；
  - `docs/process` 串联任务 → 运行 → 提交。
- 与 Git Issue 编码的关系：`TASK-x`（工作区任务）与 `FEAT-x/BUG-x`（Git 工单）各自独立，
  在任务详情里互相关联，不做合并编号。

### 3.4 验收

1. 新建任务后列表显示 `TASK-1 · ...`，刷新与换浏览器后编码不变。
2. 新建任务弹窗的版本默认等于 test 环境当前版本，可改；改后任务详情与派发运行记录一致。
3. 该任务产生的提交信息带 `TASK-1`。
4. 老任务（无 code/version）可正常展示与执行。

## 4. FEAT-2（P2）多 Agent 协作与 A2A

分三层落地，避免把协议、编排、执行混在一个工具里。

### 4.1 会话与事件（已完成的部分）

子会话已能落持久时间线（`eea143c`）。后续只需在会话上补 `parentConversationId` 与 `taskCode`，
让侧栏能按任务聚合主子会话；不改变现有运行模型。

### 4.2 A2A 协议层

- 新增 `AgentEndpoint` 抽象与注册表：`local`（本地子 Agent）、`trae`（本机 Trae）、
  `workbuddy`（本机 WorkBuddy）、`http`（通用 A2A）。
- 协议：JSON-RPC 风格 `submit(task) -> taskRef`、`result(taskRef)`、`cancel(taskRef)`、`health()`；
  端点启动时注册能力卡（支持的技能/模型/并发度）。
- 分发策略：优先匹配能力卡；不可达或超时降级为本 `local`，并在结果里标记 `degraded=true`。
- 模型只看到 `agent_dispatch(target, prompt, artifacts?)`，**URL、鉴权、协议细节不进入 prompt**。

### 4.3 编排层

- 调度 Agent 只做「拆解 → 派发 → 验收」，不执行业务写操作。
- DAG 汇总只回结构化结果：`{taskCode, status, summary, changedFiles, artifactRefs}`，
  不回子 Agent 全文；需要细节时模型再按引用读取。
- 并行度与超时沿用 `workspace_dispatch` 现有参数与服务端上限。

### 4.4 验收

1. 一次派发 2 个并行子任务，侧栏可按 `TASK-x` 看到 2 条子会话及轨迹。
2. 本机 Trae/WorkBuddy 未启动时自动降级为本地子 Agent，任务不失败。
3. 主对话只增加一条摘要卡片，不出现子 Agent 全文。

## 5. FEAT-3（P2）派发自动执行闭环

### 5.1 状态机

`TODO → PLANNING → RUNNING → WAITING_CONFIRM → DONE / FAILED / CANCELLED`

状态只允许由工具或运行结束事件推进，禁止模型直接改库。

### 5.2 工具补全（最小集合）

| 工具 | 作用 |
|---|---|
| `update_task_status` | 推进状态机，写 `reason` |
| `attach_artifact` | 挂构建产物、日志、diff 引用 |
| `request_confirm` | 进入 `WAITING_CONFIRM`，等待人工 |
| `task_cancel` | 主动中止并记录判据 |
| `verify_task` | 写入验收结果（健康检查/测试/关键文件） |

### 5.3 预算与中止

- 每个任务配置 `maxTokensPerTask`（默认按环境），用量从模型 usage 累计。
- 到 80% 预算时注入"收尾指令"：停止探索，输出已完成项与未完成项；到 100% 自动 `CANCELLED`。
- 自动中止判据（任一命中）：
  - 连续 N 轮没有新的工具调用；
  - 同一命令/同一文件修改重复 K 次；
  - 预算耗尽；
  - 运行被人工取消。
- 中止原因写入运行账本与任务事件，前端任务卡可见。

### 5.4 验收

- 无人工干预时任务能到达 `DONE` / `FAILED` / `CANCELLED` 之一，不停在 `RUNNING`。
- 每个 `DONE` 必须带 `verify_task` 结果；没有验收结果不允许置为 `DONE`。
- 主会话 token 增量不超过配置上限，且可在轨迹页看到用量。

## 6. FEAT-4（P2）token 爆炸专项

### 6.1 工具面（沿用渐进披露，继续拆组）

- 核心面只保留只读与基础工具；写类工具拆成插件组：
  `git-write`、`task`、`deploy`、`mcp:<service>`。
- 首轮只按意图激活最多 2 组（现有策略），其余靠 `tool_load`。
- system prompt 注入「插件目录摘要」（组名 + 一句话 + 触发词），用几百 token 替代上万 token 的 schema。
- 工具顺序在会话内保持稳定，避免破坏 KV 前缀缓存。

### 6.2 skill 与提示词压缩

- skill 只注入 `name + 一句话 + 何时使用`；正文通过 `read_skill` 按需读取。
- 提示词分段：稳定段（身份、规约、工具使用规则）在前；易变段（环境、任务、版本）在后，
  减少前缀变化。
- MCP 工具按 server 分组，schema 同样按需加载。

### 6.3 缓存与观测

- 每轮记录 `inputTokens / cachedTokens`，派生「缓存命中率」。

### 6.4 验收

- 同一任务（相同代码与模型）`inputTokens` 下降 ≥ 40%。
- `cachedTokens / inputTokens` ≥ 60%。
- `ToolSchemaBudgetTest` 保持通过，并新增"真实请求 token"回归基线。

## 7. 实施顺序与依赖

| 顺序 | 内容 | 理由 |
|---|---|---|
| 1 | FEAT-1 编码 + 版本 | P1，改动集中，立刻可复用 |
| 2 | FEAT-4 观测 + 压缩 | FEAT-3 需要预算与命中率数据 |
| 3 | FEAT-3 执行闭环 | 依赖任务状态机与预算 |
| 4 | FEAT-2 A2A | 依赖结构化回传与任务编码 |

## 8. 风险与待确认

1. **编码格式**：`TASK-1` 是否满足评审习惯？若希望与工单统一，需要另行定义 `TASK` 分类。
2. **版本跟随**：任务创建后默认锁定版本快照；"跟随测试环境"是显式开关，避免静默漂移。
3. **A2A 端点**：本机 Trae/WorkBuddy 的可调用形态（本地 HTTP / CLI / MCP）需确认后再定适配器。
4. **预算默认值**：需要按模型上下文长度与典型任务给出默认 `maxTokensPerTask`，建议先观测一周再定。
5. **与现有并行改动**：`spec-checks` 当前可能因未合并的并行工作为红，落地前先确认主干绿灯。
