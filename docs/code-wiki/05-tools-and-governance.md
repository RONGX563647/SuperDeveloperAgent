---
type: code-wiki-tools-governance
summary: 工具面装配、插件渐进披露、环境策略矩阵、变更单与确认令牌的完整机制
---

# 工具与治理

## 1. 工具面全景

- **91 个 `@Tool` 方法**，分布在 24 个工具类中（`platform/agent-tools`）。
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
| 变更 | `ChangeTools`、`ChangeQueryTools` | 登记、确认、查询、补丁 |
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
| `agentToolkit` | 通用非生产会话 | 只读 + 写工具（含 `workspace_*` 编辑） |
| `deployToolkit` | 发布 Agent | 部署/状态/取证/受限补丁 |
| `supervisorToolkit` | 工作区调度 | 只读 + `conversation_*` / `task_*` / `workspace_*` |

> 关键：生产环境不是"提示模型别用写工具"，而是**工具根本不存在**。这是平台最重要的安全边界之一。

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
- `ProgressiveToolMiddleware`：首轮路由只做一次，之后维持已加载插件。
- `ToolSchemaBudgetTest`：把工具 schema token 预算纳入 CI，防止无节制膨胀。

### 2.1 插件列表

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
boolean canAutoApply(String env)
EnvPolicy policy(String env)
Map<String,Object> view()   // 给前端展示当前矩阵
List<String> envs()
```

拒绝时抛 `EnvironmentException(E-707)`，附带人话原因。

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
