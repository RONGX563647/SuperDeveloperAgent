---
type: code-wiki-tools-governance
summary: 工具面装配、插件渐进披露、环境策略矩阵、变更单与确认令牌的完整机制
---

# 工具与治理

## 1. 工具面全景

- **90 个 `@Tool` 方法**，分布在 24 个工具类中（`platform/agent-tools`）。
- 工具名由 AgentScope 从方法名派生，插件目录里用的是稳定工具名。
- 所有工具通过构造器注入依赖；`agent-tools` 禁止 `ProcessBuilder`（A7），执行必须走 executor 的端口。

### 1.1 工具类清单

| 域 | 类 | 说明 |
|---|---|---|
| 代码/仓库 | `RepoQueryTools`、`GitIssueQueryTools`、`GitIssueCommandTools` | 状态、diff、提交、文件读写、Issue 增删改查 |
| 流水线 | `CiQueryTools`、`CiCommandTools` | 定义、dry-run、触发、状态、日志、取消 |
| 部署 | `DeployTools` | 环境状态、部署、配方、回滚、取证 |
| 环境 | `EnvQueryTools`、`EnvCommandTools` | 变量、端口、中间件、运行时、日志 |
| 动作 | `ActionQueryTools`、`ActionCommandTools` | 标准动作列表/计划/执行 |
| 变更 | `ChangeTools`、`ChangeStatusTool` | 登记、确认、状态查询（`change_status` 唯一实现在 ChangeStatusTool，两面共用同一 bean）、补丁 |
| 编辑 | `EditorTools`、`PatchTools`、`DockerfileTools` | 会话工作树编辑、受限补丁、Dockerfile |
| 项目/配置 | `ProjectTools`、`ConfigTools`、`RuntimeTools` | 项目信息、配置解析、运行状态 |
| 交互 | `AskUserTools` | `ask_user` 选项卡片 |
| 调度 | `WorkspaceSupervisorTools` | 21 个会话/任务/DAG 工具 |
| 运维 | `OpsAgentTools`、`CapabilityMarketTools` | 运维 Agent、能力市场 |
| 文档/发现 | `ApiDocTools`、`ToolCatalogTools` | 平台 API 检索、工具搜索/加载 |

### 1.2 四套 Toolkit

由 [ToolCatalog](file:///workspace/platform/agent-tools/src/main/java/top/rongx/agent/tools/ToolCatalog.java) 装配：

| Bean | 场景 | 内容 |
|---|---|---|
| `readOnlyToolkit` | prod 会话、viewer 角色、supervisor 基础 | 只读工具；写工具不在 schema |
| `agentToolkit`（Primary） | 通用非生产会话 | 只读 + 写工具（含 `workspace_*` 编辑） |
| `deployToolkit` | 发布 Agent | 部署/状态/取证/受限补丁 |
| `supervisorToolkit` | 工作区调度 | 只读 + `conversation_*` / `task_*` / `workspace_*` |

> 关键：生产环境不是"提示模型别用写工具"，而是**工具根本不存在**。这是平台最重要的安全边界之一。

### 1.3 角色 Worker 的工具面裁剪（0.2 线）

[AgentFactory.createWorker(ctx, role)](file:///workspace/platform/agent-runtime/src/main/java/top/rongx/agent/runtime/AgentFactory.java) 把 `WorkerRole`（PRODUCT/DEV/TEST/OPS）映射到上面的 Toolkit，**复用既有装配、不扩大任何角色的写权限**：

| 角色 | 工具面 | 说明 |
|---|---|---|
| `OPS` | `deployToolkit`（`createDeploy` 特例） | 发布入口与生产审批规则保持原样：dev/test 权限 BYPASS，生产 DEFAULT 直接拒绝 |
| `PRODUCT` | `supervisorToolkit` | 只写任务卡/工单（管理面 + 只读面），无任何执行类写工具 |
| `DEV` / `TEST` | `agentToolkit` + 角色提示词 | 全量工具面，提示词只描述职责边界，不承担安全职责 |

角色只决定三件事：**工具面、系统提示、权限模式**——权限仍由环境与平台门禁把关。守护：`SupervisorToolkitWiringTest`（调度/PRODUCT 零写工具防回退）与 `ToolkitAssemblyTest`（agentToolkit 必须是 readOnlyToolkit 超集）。

> 现状口径：裁剪已接线，但会话创建路径目前只写 `chat`/`supervisor`，角色 Worker 属"已接线、待启用"。

## 2. 插件渐进披露（Progressive Disclosure）

90+ 工具的完整 schema 每轮都进上下文会浪费 token。方案：

```text
系统提示首轮只注入「插件索引」（10 个插件，英文短描述）
  CORE 常驻 8 个发现类工具：
    project_info, list_envs,
    tool_search, tool_get, tool_load, tool_plugins,
    skill_load, api_search

模型发现需要某项能力：
  tool_search("部署") → tool_load("deploy") → 该插件工具进入本轮 Toolkit
```

实现：

- [ToolPlugins](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/tool/ToolPlugins.java)：静态目录（名称稳定、顺序固定，索引文案与缓存前缀依赖它）。
- `ToolPluginIndex`：按实际装配的 Toolkit 过滤，避免索引里出现不存在的工具。
- `ProgressiveToolMiddleware`：首轮路由只做一次，之后维持已加载插件；**首轮自动预载 ≤2 个插件**（`MAX_AUTO_PLUGINS=2`），激活态写会话状态、不再抖动，保住 KV 前缀缓存。
- 输出预算：单次工具输出默认 8000 字符（`agent.tools.max-output-chars`），`agent.tools.output-budgets` 按工具覆盖（`git_diff=16000`、`app_logs=12000` 等）。
- 会话上下文折叠：旧历史按 `agent.context.summary-chars=4000` 折叠摘要。
- `ToolSchemaBudgetTest`：把工具 schema token 预算纳入 CI，防止无节制膨胀。
- `TokenBaselineTest`：量化基线——真实工具面 + 评测指令下，按需披露使 inputTokens 下降 ≥40%，防回涨。

### 2.1 token 用量归因（插件分项）

渐进披露的可观测闭环（0.2 线，迁移 `V8`）：

```text
ProgressiveToolMiddleware（裁剪后按真实下发 schema 计算）
  → 每个可见工具 name+description 字符数按插件分桶，core 兜底桶；全量可见也记录
  → ToolAttribution 放入 RuntimeContext
  → ChatService 在 ModelCallEndEvent 交给 TokenUsageRecorder
  → token_usage 落 activated_plugins / plugin_schema_chars 两列（可空，老记录 NULL）
    + 双日志通道 logs/token-usage.log（命中率）、logs/token-attribution.log（分项）
  → 查询 GET /api/v1/usage/session/{sessionId}（明细含归因）、GET /api/v1/usage/summary（聚合）
```

口径：schema 字符数是 token 的确定性代理（约 4 字符/token，与 `TokenBaselineTest` 同口径），不是真实计量；skill 正文按需返回、不进常驻 schema，不参与归因；统计失败只 warn 不影响对话。全局"按插件汇总"端点目前没有，插件分项只在会话明细与日志级可见。

### 2.2 token 量化基线（三条）

| 基线 | 位置 | 断言 |
|---|---|---|
| 结构性下降 | `TokenBaselineTest`（spec-checks） | 12 条固定评测指令复现首轮路由，按需 schema 较全量下降 ≥40%，首轮 ≤2200 字符、全量 ≤4500 字符 |
| 真实命中率 | `TokenHitRateBaselineTest`（agent-infra） | 只读打开真实统一库 `token_usage`，`cached/input ≥60%`；无数据跳过、不自造数字 |
| schema 预算 | `ToolSchemaBudgetTest` | 单工具描述 ≤40 ASCII 字符、全量 ≤4000 字符、CORE 恰 8 个、`@Tool` name 全局唯一 |

### 2.3 插件列表

| 插件 | 标题 | 代表工具 |
|---|---|---|
| `repo` | Code & commits | `git_status`、`git_diff`、`read_file`、`grep` |
| `ci` | Build pipeline | `ci_list_pipelines`、`ci_dry_run`、`ci_run`、`wait_run` |
| `deploy` | Deploy & release | `deploy_env_status`、`deploy_run`、`release_trace` |
| `env` | Env & config | `env_summary`、`env_ensure`、`port_check` |
| `issue` | Issues & tasks | `list_git_issues`、`create_git_issue`、`sync_git_issue` |
| `action` | Actions | `list_actions`、`plan_action`、`run_action` |
| `change` | Changes & patches | `request_confirmation`、`change_status`、`workspace_write` |
| `capability` | Capability market | `search_capabilities`、`install_capability` |
| `api` | API docs | `api_search`、`api_get`、`api_schema` |
| `dispatch` | Workspace dispatch | `workspace_dispatch`、`conversation_*`、`task_*` |

## 3. 环境策略矩阵

[EnvPolicyService](file:///workspace/platform/agent-governance/src/main/java/top/rongx/agent/governance/EnvPolicyService.java) 实现 `PolicyPort`，是"同一动作在不同环境不同裁决"的唯一来源。

| 环境 | L0 | L1 | L2 | L3 | dry-run 后自动 apply | 需绑定 |
|---|---|---|---|---|---|---|
| dev | ALLOW | ALLOW | ALLOW | DENY | 是 | 是 |
| test | ALLOW | ALLOW | ALLOW | CONFIRM | 是 | 是 |
| prod | ALLOW | ALLOW | DENY | CONFIRM | 否 | 是 |
| 未知 | 按 prod（fail-closed） | | | | | |

API：

```java
PolicyDecision decide(String env, ToolLevel level)
void requireNotDenied(ProjectContext ctx, ToolLevel level, String operation)
void requireAllowed(ProjectContext ctx, ToolLevel level, String operation, String confirmedChangeId)
boolean canAutoApply(String env)
EnvPolicy policy(String env)
Map<String,Object> view()   // 给前端展示当前矩阵
List<String> envs()
```

拒绝时抛 `EnvironmentException(E-707)`，附带人话原因。`requireAllowed` 是 fail-closed 版统一门禁（CONFIRM 场景的唯一解释者）：核验变更单存在、属当前项目、环境一致、状态 ∈ {CONFIRMED, APPLIED}、kind 与操作匹配，五条缺一即拒。

## 4. 变更单（ChangePlan）

[ChangeRegistry](file:///workspace/platform/agent-governance/src/main/java/top/rongx/agent/governance/ChangeRegistry.java) 实现 `ChangeStore`，存储于统一库：

| 方法 | 用途 |
|---|---|
| `request(...)` | 创建变更单（随机 id） |
| `registerWithHash(project, env, kind, summary, planHash, ...)` | 用确定性 id + 计划哈希登记 |
| `find(changeId)` / `findLatest(project, kindPrefix)` | 查询 |
| `updateStatus(changeId, status)` | 状态流转 |
| `purgeBefore(cutoff)` | 定时清理 |

状态：`PENDING → CONFIRMED → APPLIED / FAILED`。只有补丁类、编辑器授权类与 `ActionId` 动作可被 `/apply` 执行，其余为留痕单。

关联：
- [DeployGate](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/deploy/DeployGate.java) 用 `changeId(project, env, sha)` 与 `planHash(project, env, sha)` 生成确定性标识，保证"同一交付物只能确认一次、配方变化令牌失效"。
- `requireIfNeeded(...)` 在发布前校验 test 取证与令牌，失败抛 `E-6xx`。

## 5. 确认令牌（一次性、短时效）

[ConfirmTokenService](file:///workspace/platform/agent-governance/src/main/java/top/rongx/agent/governance/ConfirmTokenService.java)：

```java
public static final Duration TTL = Duration.ofMinutes(5);

ConfirmToken issue(String userId, String changeId, String planHash);
ConfirmToken consume(String tokenValue, String changeId, String planHash);
int purgeExpired();
```

安全属性：

1. **只能人签发**：唯一签发入口是 `POST /api/v1/changes/{id}/confirm`（可要求密码二次认证）。Agent 没有对应工具。
2. **绑定计划**：`consume` 同时校验 `changeId + planHash`，防止"确认 A 执行 B"。
3. **一次性**：消费是原子操作，重复使用失败。
4. **短时效**：5 分钟，过期由定时任务清理。

0.2 线未改动核心机制（TTL/绑定/一次性不变）。`POST /changes/{id}/apply` 按 kind 分流：`editor_branch:*` 确认即授权、`patch:*` 提交补丁到新分支、其余走 `ActionId` 动作并二次核对 `planHash`；高危单 confirm/apply 都写 `audit_event` 审计。

## 6. 受限补丁

`PatchRegistry` + `PatchTools`：模型可以提出补丁，但补丁先登记为 `CodePatch`，应用/丢弃由人类控制；会话工作树编辑（`workspace_*`）限制在 `ProjectContext.worktree` 内，并以 diff 形式呈现。

## 7. 其它安全机制

| 机制 | 说明 |
|---|---|
| 凭据脱敏 | `SecretRedactor` 在日志/工具结果中替换密钥 |
| 沙箱隔离 | `DockerSandbox`：非 root、cap-drop、pids/mem/cpu 限制、只读根 |
| 目录逃逸防护 | `ProjectContext` 构造校验 worktree 名 |
| 输入上限 | Agent 输入长度、附件大小、并发流闸门 |
| 速率限制 | `RATE_LIMITED (E-709)`，流式请求按用户限流 |
| 配置扫描 | `ConfigCredentialScanTest` 阻止明文密钥入库 |
| 宿主工具排除 | `HostToolExclusionTest` 禁止 AgentScope 宿主 shell/写文件工具进入任何工具面（沙箱分层守卫） |
| 只读会话不挂 MCP | MCP 工具没有只读元数据，挂进来等于绕过只读工具面（AgentFactory 强制） |
