---
type: code-wiki-domain-model
summary: 领域模型字典：核心记录、枚举、端口、错误码分段的语义与约束
---

# 领域模型

`agent-domain` 是整个平台的通用语言，**不依赖任何框架**（ArchUnit A2）。理解下列类型即可读懂大部分业务代码。

## 1. 动作与工具分级

### ActionId

流水线可执行的标准动作（[源码](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/action/ActionId.java)）：

| 值 | 语义 |
|---|---|
| `backend_build` | 后端构建 |
| `backend_test` | 后端测试 |
| `frontend_build` | 前端构建 |
| `frontend_test` | 前端测试 |
| `image_package` | 镜像打包 |
| `stack_deploy` | 栈部署 |
| `stack_rollback` | 栈回滚 |

`autoExecutable()` 表示无需人工审批即可在流水线内部自动执行；`fromWire()` 做了别名归一（例如历史别名）。

### ToolLevel

| 值 | 含义 | 典型工具 |
|---|---|---|
| `L0_READ` | 只读 | `git_status`、`ci_list`、`repo_search` |
| `L1_PROPOSE` | 产出计划/草案，不落地 | `change.request`、`patch_propose` |
| `L2_EXECUTE_ISOLATED` | 隔离环境执行 | test 环境部署、沙箱构建 |
| `L3_EXECUTE_PROD` | 生产变更 | prod 部署、回滚 |

`mutating()` = L2/L3；`requiresConfirmToken()` = L3。

## 2. 环境与策略

### EnvPolicy / PolicyDecision

[EnvPolicy](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/policy/EnvPolicy.java) 是 `(env, l0..l3, autoApplyAfterDryRun, requireBinding)` 的记录。构造时强制：

```java
if ("prod".equals(env) && l3 == ALLOW) throw ...;  // 生产 L3 永不放行
```

### RuntimeMode

| 值 | 含义 |
|---|---|
| `LOCAL_DEV` | 前后端跑在宿主机进程（dev） |
| `LOCAL_TEST` | 前后端容器化，中间件本机 Docker |
| `SERVER` | 全部服务端 |

`of(raw, env)` 空值按环境名给默认；`localProcess()` / `localDocker()` 供执行器选择路径。

### DeployTarget

`DOCKER` / `HOST` / `SERVER`，`fromWire()` 大小写不敏感。dev 本机形态由引擎内的 `LocalDevHostDeployer` 处理，不在此枚举中。

## 3. 变更与补丁

### ChangePlan

变更单是"待人类确认的写操作"载体：

| 字段 | 说明 |
|---|---|
| `changeId` | `DeployGate.changeId(project, env, sha)` 生成 |
| `kind` | 可执行动作名（`stack_deploy` / `stack_rollback` / `patch:*` / `editor_branch:*`） |
| `planHash` | 计划内容哈希，绑定确认令牌 |
| `summary` / `highRisk` | 一句话说明、是否高危（SQL/依赖升级/安全配置） |
| `imageDigest` / `frontendDistHash` | 后端镜像 digest、前端 dist 哈希 |
| `status` | `PENDING → CONFIRMED → APPLIED / FAILED` |

`ChangePlan.executable()` 只允许补丁类、编辑器授权类与 `ActionId` 动作走 `/apply`；令牌校验在 `ConfirmTokenService`。

### TagState

发布标签结果：`PUSHED`、`LOCAL_ONLY`（推送失败，带原因码 `TAG_PUSH_CREDENTIAL_MISSING`）、`FAILED`、`SNAPSHOT`、`REJECTED`。每个非成功态都带**稳定原因码 + 可执行提示**，前端直接展示。

## 4. 运行与会话

### RunState（跨引擎统一）

```text
QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED
              ↘ BLOCKED（等待人工，不算失败）
```

`terminal()`、`active()` 供列表与重启恢复使用。

### StageStatus

`PENDING / RUNNING / SUCCEEDED / FAILED / TIMEOUT / CANCELLED`，`isTerminal()` 用于收尾。

### RunKind

`CHAT / DEPLOY / WORKSPACE / BUGFIX / PIPELINE`——统一运行账本的分类维度。

### 工作区会话

| 枚举 | 值 |
|---|---|
| `ConversationKind` | `NORMAL / SUPERVISOR / BUGFIX` |
| `ConversationStatus` | `IDLE / RUNNING / NEEDS_CONFIRMATION / SUCCEEDED / FAILED / CANCELLED` |
| `RunStatus` | `QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED` |
| `TaskStatus` | `TODO / RUNNING / BLOCKED / DONE / CANCELLED` |

### BugFixStage / BugFixStatus

阶段：`ANALYZE → REPRO_DEPLOY → REPRODUCE → FIX → VERIFY_DEPLOY → VERIFY → PULL_REQUEST → DONE`。
状态覆盖 `QUEUED / RUNNING / BLOCKED / SUCCEEDED / FAILED / CANCELLED`，并有项目级互斥与重跑标记。

## 5. ProjectContext（最重要的不可变对象）

[ProjectContext](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/project/ProjectContext.java)：

```java
record ProjectContext(
    String projectId,
    String env,        // dev / test / prod
    String userId,
    String repoUrl,
    String worktree,   // 会话工作树
    boolean readOnly
)
```

约束：

- 构造时校验 `worktree` 名称，拒绝 `..`、绝对路径、分隔符，防目录逃逸。
- 由网关 `ProjectContextResolver` 创建，随后放入 AgentScope `RuntimeContext`，工具通过 `RuntimeContext` 读取，**模型无法在参数里覆盖**。
- 环境由 `ProjectContextResolver` 校验：缺省 `test`，必须属于该项目；策略层对未知环境按 `prod` 处理（fail-closed）。

## 6. 错误码字典

[ErrorCode](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/error/ErrorCode.java) 的编码规则：`E-{组}{序号}`，组由 [ErrorGroup](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/error/ErrorGroup.java) 定义。

| 段 | 组 | 代表码 |
|---|---|---|
| 1xx | BUILD | `E-101 COMPILE_FAILED`、`E-199 BUILD_INTERNAL` |
| 2xx | RECIPE | `E-201 ARGV_INVALID`、`E-202 SHELL_REJECTED`、`E-220 PIPELINE_DEFINITION_UNAVAILABLE` |
| 3xx | RUN | `E-302 TIMEOUT`、`E-303 CANCELLED`、`E-304 AGENT_EXECUTION_FAILED` |
| 4xx | ARTIFACT | `E-401 ARTIFACT_MISSING` |
| 5xx | DEPLOY | `E-505 DEPLOY_TARGET_UNCONFIGURED`、`E-507 ROLLBACK_VERSION_MISSING`、`E-508 PIPELINE_DEFINITION_UNMERGED` |
| 6xx | PROMOTION | `E-601 ENV_NOT_PROMOTED`、`E-602 CONFIRM_REQUIRED`、`E-604 HEALTH_CHECK_FAILED` |
| 7xx | ENVIRONMENT | `E-704 SECRET_UNRESOLVED`、`E-706 DRY_RUN_STALE`、`E-707 ENV_POLICY_DENIED`、`E-709 RATE_LIMITED` |
| 8xx | INTAKE | `E-801 INTAKE_FAILED` |

`ErrorKind`：`INPUT`（参数/输入）、`STATE`（状态不允许）、`INTERNAL`（内部失败）。

规则：

- 错误码必须唯一（`ErrorCodeUniquenessTest`）。
- 业务代码禁止裸抛带字面量码的异常，统一走 `ErrorCode` 常量（`ErrorCodeContractTest`）。
- `PlatformException` 携带 `ErrorCode`，由 `GlobalExceptionHandler` 转成统一 JSON。

## 7. 端口（可选实现）

`domain/port` 用接口隔离技术细节，便于测试替换。重点：

| 端口 | 语义 |
|---|---|
| `ActionResolver` / `ActionRunner` | 把 `ActionId` 解析为命令，并在沙箱/宿主执行 |
| `PipelineEngine` | 触发、查询、日志、取消、重试 |
| `Deployer` | 单一部署目标 |
| `ChangeStore` / `PatchStore` | 变更单、补丁的持久化 |
| `RecipeStore` / `PipelineDefStore` | 环境配方、平台侧流水线定义 |
| `ProjectRepository` / `ProjectRepoLocator` | 项目注册与仓库发现 |
| `ModelRegistry` | 模型清单与当前模型 |
| `EnvProvisioner` / `EnvRuntime` | 中间件供给与运行时控制 |
| `RunLedgerRepository` | 统一账本查询 |

新增实现时只需实现端口并在配置类注册，领域与运行时无需改动。
