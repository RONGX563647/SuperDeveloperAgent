---
type: code-wiki-modules
summary: 九个模块的职责边界、关键类与函数、对外端口，以及模块间的调用关系
---

# 模块详解

每个模块给出：职责、包结构、关键类（含关键方法）、对外暴露的端口。

## 1. agent-domain（领域层）

**定位**：整个系统的"词汇表"和端口定义，零框架依赖，可单独测试。

包结构：`domain/{action,artifact,bugfix,change,conversation,deploy,env,error,gitissue,patch,pipeline,policy,port,process,project,release,run,runtime,scaffold,settings,tool}`。

### 关键类型

| 类型 | 作用 | 关键成员 |
|---|---|---|
| [ActionId](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/action/ActionId.java) | 动作词汇表 | `backend_build/backend_test/frontend_build/frontend_test/image_package/stack_deploy/stack_rollback`；`autoExecutable()`、`fromWire()` |
| [ToolLevel](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/tool/ToolLevel.java) | 工具分级 | `L0_READ / L1_PROPOSE / L2_EXECUTE_ISOLATED / L3_EXECUTE_PROD`；`mutating()`、`requiresConfirmToken()` |
| [ProjectContext](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/project/ProjectContext.java) | 会话级不可变上下文 | `projectId/env/userId/repoUrl/worktree/readOnly`；构造时校验 worktree 名防目录逃逸 |
| [ChangePlan](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/change/ChangePlan.java) | 变更单 | `changeId/kind/planHash/status`；`executable()` 判断能否走 `/apply` |
| [ErrorCode](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/error/ErrorCode.java) | 对外错误码字典 | `E-1xx` 构建、`E-2xx` 配方/参数、`E-3xx` 运行时、`E-4xx` 产物、`E-5xx` 部署、`E-6xx` 晋升/门禁、`E-7xx` 环境/策略、`E-8xx` 接入 |
| [BugFixStage](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/bugfix/BugFixStage.java) | Bug 修复状态机 | `ANALYZE → REPRO_DEPLOY → REPRODUCE → FIX → VERIFY_DEPLOY → VERIFY → PULL_REQUEST → DONE` |
| [ToolPlugins](file:///workspace/platform/agent-domain/src/main/java/top/rongx/agent/domain/tool/ToolPlugins.java) | 工具插件目录 | `CORE` 8 个常驻工具 + 10 个插件 |

### 端口（`domain/port`）

跨层契约都在这里，实现位于 infra/executor/governance：

| 端口 | 实现方 | 用途 |
|---|---|---|
| `ActionResolver` / `ActionRunner` | `RecipeActionResolver` / `DockerActionRunner`、`ProcessActionRunner` | 动作解析与执行 |
| `PipelineEngine` | `BuiltInPipelineEngine` | 流水线触发、状态、日志、取消、重试 |
| `Deployer` | `DockerDeployer`、`HostDeployer`、`ServerDeployer`、`LocalDevHostDeployer` | 部署目标 |
| `ChangeStore` / `PatchStore` | `ChangeRegistry` / `PatchRegistry` | 变更与补丁登记 |
| `ProjectRepository` / `ProjectRepoLocator` | `CompositeProjectRepository` / `DefaultRepoLocator` | 项目与仓库定位 |
| `RecipeStore` / `PipelineDefStore` | `FileRecipeStore` / `FilePipelineDefStore` | 环境配方、平台侧流水线定义 |
| `ModelRegistry` | `SettingsModelRegistry` | 当前可用模型 |
| `PolicyPort` | `EnvPolicyService` | 环境策略 |
| `EnvProvisioner` / `EnvRuntime` | `DockerEnvProvisioner` / `DockerEnvRuntime` | 中间件供给与运行时 |
| `RunLedgerRepository` | `DbRunLedgerRepository` | 统一运行账本 |

## 2. agent-infra（基础设施层）

**定位**：把 domain 的端口落到具体技术（Git、JPA、SQLite、文件、加密）。

### 关键类

| 类 | 职责 | 关键方法 |
|---|---|---|
| [ProjectIntakeService](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/git/ProjectIntakeService.java) | 项目接入状态机（本地 DIRECT / 远程 clone） | `validate()`、`submit()`、`status()`、`recipeCheck()`；同项目并发只允许一个 intake |
| [GitWorktreeManager](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/git/GitWorktreeManager.java) | bare 仓库 + worktree 管理、凭据注入 | `cloneRemote()`、`bareInit()`、`linkLocal()`、`addWorktree()`、`fetchLocal()`、`fetchRemote()` |
| `GitRepoInspector` | 仓库结构探测（Java/Node/前端） | 供配方生成与接入报告使用 |
| `RecipeScaffolder` | 按仓库结构生成 `ci` 定义 | 产出平台侧 pipeline 定义 |
| [SecretBox](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/security/SecretBox.java) | AES-256-GCM 加解密 | `encrypt()` / `decrypt()`；主密钥 `AGENT_SECURITY_MASTER_KEY` |
| `EncryptedAgentStateStore` | Agent 会话状态加密落盘 | `save()` / `get()` / `getList()` / `delete()`；目录 700、文件 600；不作上下文裁剪（压缩交给 harness CompactionMiddleware） |
| [PlatformDb](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/persistence/PlatformDb.java) | 统一库入口 | 装配数据源与事务 |
| `PersistenceConfig` | JPA 实体/仓储扫描 | 扫描 `infra`、`governance`、`gateway` 三包 |
| `SqliteWalCheckpointer` / `SqliteToTargetCopier` | WAL 检查点、SQLite→PG 迁移 | 启动与热切换时使用 |
| `FileRecipeStore` / `FilePipelineDefStore` | 环境配方与平台定义的文件存储 | 读写 `<project>/recipes/<env>/vN`、平台定义目录 |
| `SessionWorktreeEditor` | 会话级工作树编辑 | open/write/read/list/delete/diff/commit |

持久化细节见 [持久化与数据布局](07-persistence.md)。

## 3. agent-governance（治理层）

| 类 | 职责 | 关键方法 |
|---|---|---|
| [EnvPolicyService](file:///workspace/platform/agent-governance/src/main/java/top/rongx/agent/governance/EnvPolicyService.java) | 环境 × 级别策略矩阵 | `decide()`、`requireNotDenied()`、`canAutoApply()`、`view()` |
| [ChangeRegistry](file:///workspace/platform/agent-governance/src/main/java/top/rongx/agent/governance/ChangeRegistry.java) | 变更单持久化 | `request()`、`registerWithHash()`、`find()`、`updateStatus()`、`purgeBefore()` |
| [ConfirmTokenService](file:///workspace/platform/agent-governance/src/main/java/top/rongx/agent/governance/ConfirmTokenService.java) | 一次性令牌 | `issue()`、`consume()`、`purgeExpired()`；TTL 5 分钟，原子消费 |
| `PatchRegistry` | 受限补丁登记 | 补丁与编辑记录持久化 |
| `GovernanceDb` / `GovernancePersistenceConfig` | 治理库连接与事务 | 与主库共享 `platform.db` |

**策略矩阵**（默认）：

| 环境 | L0 | L1 | L2 | L3 | dry-run 后自动 apply | 需要绑定 |
|---|---|---|---|---|---|---|
| dev | ALLOW | ALLOW | ALLOW | DENY | 是 | 是 |
| test | ALLOW | ALLOW | ALLOW | CONFIRM | 是 | 是 |
| prod | ALLOW | ALLOW | DENY | CONFIRM | 否 | 是 |
| 未知 | 按 prod | | | | | |

## 4. agent-executor（执行面）

| 类 | 职责 |
|---|---|
| [BuiltInPipelineEngine](file:///workspace/platform/agent-executor/src/main/java/top/rongx/agent/executor/BuiltInPipelineEngine.java) | 流水线编排：checkout → build/test → image → deploy → health；看门狗、续跑、日志分页 |
| `AbstractActionRunner` / `DockerActionRunner` / `ProcessActionRunner` | 动作执行：沙箱内 / 宿主 |
| [DockerSandbox](file:///workspace/platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerSandbox.java) | 把命令包装成受限 `docker run`（非 root、cap-drop、资源上限、只读根） |
| `DockerCli` | Docker CLI 抽象（可指向 podman） |
| [Deployers](file:///workspace/platform/agent-executor/src/main/java/top/rongx/agent/executor/Deployers.java) | 部署器注册表；未命中 fail-closed（E-505） |
| `DockerDeployer` / `HostDeployer` / `ServerDeployer` / `LocalDevHostDeployer` | 容器替换 / HOST 发布 / SSH 远程 / dev 本机托管 |
| `DockerEnvProvisioner` / `DockerEnvRuntime` | 中间件容器按需创建、启停、发现 |
| `RunHistoryStore` / `ImageVersionStore` | 运行历史、镜像版本账本 |
| `AutoRepairCoordinator` / `ImagePullRepair` / `DockerDaemonRepair` / `AgentDiagnosisRepair` | 失败自动修复闭环 |
| `TriggerScheduler` | 同项目同环境串行触发 |
| `PipelineDefinitionResolver` / `PipelineDefinitionInspectorImpl` | 流水线定义解析与检查 |
| `PomVersionSync` / `ReleaseVersion` | 版本与发布标签 |

详见 [执行与部署](06-execution-deploy.md)。

## 5. agent-tools（工具层）

**定位**：模型能调用的所有能力都在这里，共 **91 个 `@Tool` 方法 / 24 个工具类**。

| 分类 | 类（工具数） |
|---|---|
| 只读查询 | `CiQueryTools`(7)、`RepoQueryTools`(6)、`EnvQueryTools`(3)、`RuntimeTools`(3)、`ActionQueryTools`(2)、`GitIssueQueryTools`(2)、`ChangeQueryTools`(1)、`ProjectTools`(2)、`ConfigTools`(1)、`ApiDocTools`(3)、`ToolCatalogTools`(5) |
| 写/执行 | `CiCommandTools`(3)、`ActionCommandTools`(1)、`DeployTools`(5)、`DockerfileTools`(1)、`EnvCommandTools`(4)、`GitIssueCommandTools`(6)、`ChangeTools`(3)、`PatchTools`(1)、`EditorTools`(7)、`OpsAgentTools`(1)、`CapabilityMarketTools`(2) |
| 交互/调度 | `AskUserTools`(1，`ask_user`)、`WorkspaceSupervisorTools`(21) |
| 编排/缓存 | [ActionService](file:///workspace/platform/agent-tools/src/main/java/top/rongx/agent/tools/ActionService.java)、`CachingAgentTool`、`ToolResultCache`、`SecretRedactor` |

四套 Toolkit 在 [ToolCatalog](file:///workspace/platform/agent-tools/src/main/java/top/rongx/agent/tools/ToolCatalog.java) 装配：

| Bean | 使用者 | 内容 |
|---|---|---|
| `readOnlyToolkit` | 生产会话、只读用户、supervisor 基础面 | 只含只读工具，写工具不在 schema |
| `agentToolkit`（Primary） | 非生产通用会话 | 只读 + 全部写工具 |
| `deployToolkit` | 发布 Agent | 环境状态 / 触发 / 固化 / 取证 / 受限补丁 |
| `supervisorToolkit` | 工作区调度 Agent | 只读 + 会话/任务/DAG 管理 |

工具与治理细节见 [工具与治理](05-tools-and-governance.md)。

## 6. agent-runtime（运行时）

| 类 | 职责 | 关键方法 |
|---|---|---|
| [AgentFactory](file:///workspace/platform/agent-runtime/src/main/java/top/rongx/agent/runtime/AgentFactory.java) | 按上下文构造 Agent | `createChat()`、`createDeploy()`、`createSupervisor()`、`systemPrompt()` |
| [ChatService](file:///workspace/platform/agent-runtime/src/main/java/top/rongx/agent/runtime/ChatService.java) | 把输入变成事件流 | `stream()`（4 个重载）、`streamSupervisor()`、`structured()` |
| `AgentSession` | Agent + 可关闭资源 | `close()` 关闭本轮 MCP/临时资源 |
| `DynamicModelProvider` | 每次请求解析当前模型（设置页热切换） | `current()`、`activeConfig()` |
| `ChatSessionRegistry` | `sessionId` 与 `(project, env, user)` 绑定校验 | `bindOrVerify()` |
| `ProgressiveToolMiddleware` | 工具渐进披露：核心工具常驻，插件按需加载 | 首轮路由只做一次 |
| `AskUserMiddleware` | `ask_user` 入参 → `ask.requested` 事件 | |
| `TokenUsageRecorder` / `ConversationTimelineRecorder` | token 用量、服务端时间线 | |
| `workspace/*` | 工作区调度：`ConversationManager`、`WorkspaceDispatchScheduler`、`WorkspaceRunExecutor`、`WorkspaceEventBus`、`WorkspaceRunWatchdog`、`RunAborter` | DAG 并行派发、超时熔断、事件订阅 |

## 7. agent-gateway（接入层）

| 子包 | 内容 |
|---|---|
| `gateway` | [AgentApplication](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/AgentApplication.java)：`.env` 加载、数据库后端配置预注入、Boot 启动 |
| `gateway/web` | 40 个 Controller（对话、流水线、部署、环境、设置、项目、Issue、工作区…） |
| `gateway/auth` | Sa-Token 配置、登录、角色与权限码、`CurrentUser`、`UserStore` |
| `gateway/deploy` | `DeployGate`、`ChatRunRecoveryAdvisor` |
| `gateway/bugfix` | Bug 自动修复编排器、7 个阶段处理器、结构化输出、PR、通知 |
| `gateway/gitissue`、`gateway/gitee` | Issue 同步、Gitee OAuth/PAT、Webhook |
| `gateway/settings` | 数据库热切换、模型探测、环境发现、本机服务管理 |
| `gateway/config` | 构建新鲜度检查、治理清理任务、Jackson 配置 |
| `gateway/persistence` | 用户、Gitee 账号、流租约实体 |

## 8. spec-checks（规约检查）

仅 `src/test`，随 `mvn verify` 执行：

| 测试类 | 检查 |
|---|---|
| `ArchitectureRulesTest` | A1–A9 架构规则 |
| `ForbiddenPatternScanTest` | R01–R13 禁令（double 金额、`Runtime.exec`、`new Thread`…） |
| `ErrorCodeContractTest` / `ErrorCodeUniquenessTest` | 错误码唯一、禁止字面量 |
| `MigrationGuardTest` | Flyway 迁移文件不可变 |
| `HandbookDriftTest` | Handbook 锚点与行号漂移 |
| `ProcessArchiveTest` | 会话归档完整性 |
| `ToolSchemaBudgetTest` | 工具 schema token 预算 |
| `ConfigCredentialScanTest` | 配置/代码中的明文凭证 |
| `ApiDocContractTest` | OpenAPI 快照与控制器一致 |

## 9. web（前端）

Vue3 + Vite + Element Plus，7 个路由页面、14 个 API 文件、5 个 composable。详见 [前端架构](09-frontend.md)。
