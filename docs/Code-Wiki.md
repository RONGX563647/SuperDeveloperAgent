# Code Wiki：研发运维智能体平台（agentscope-ruoyi）

> 基线：2026-11 当前主干代码（`platform/` 7 个运行时模块 + `spec-checks` 校验模块 + `web/` 前端 + `specs/` 3 份规格）。
> 本文档面向新成员与代码评审，覆盖：整体架构、模块职责、关键类与函数、依赖关系、HTTP API、运行方式与质量门禁。
> 配套规约：《项目规约-v1.0》《PRD-DevOps-Agent平台-v1.0》《规约-流水线执行器-v1.0》（均在 `docs/`）。
> 环境子系统决策：《环境模板与中间件自动注入 v1/v2》（`docs/环境变量与环境模板设计-v1.md` 仅存档、`-v2.md` 为现行决策）。
> 使用文档：《流水线工作台-使用指南-v1》（`docs/流水线工作台-使用指南-v1.md`，面向使用者的工作台操作说明）。
> 功能规格：`specs/dev-local-deploy`（流水线体验 + dev 本机部署）、`specs/env-inject-health`（注入自检与中间件健康）、`specs/env-runtime-consistency`（环境一致性）。

---

## 目录

1. [项目总览](#1-项目总览)
2. [仓库结构](#2-仓库结构)
3. [整体架构](#3-整体架构)
4. [后端模块详解](#4-后端模块详解)
5. [核心机制](#5-核心机制)
6. [HTTP API 一览](#6-http-api-一览)
7. [模型工具（@Tool）一览](#7-模型工具tool一览)
8. [前端架构](#8-前端架构)
9. [依赖关系](#9-依赖关系)
10. [配置与环境变量](#10-配置与环境变量)
11. [构建与运行](#11-构建与运行)
12. [测试与质量门禁](#12-测试与质量门禁)
13. [已知边界与留白](#13-已知边界与留白)

---

## 1. 项目总览

**定位**：内网部署的独立 Agent 平台，面向开发者与运维，提供「读 Issue 改 Bug」「AI 自动化运维」「自动化部署」三类能力。

**设计哲学（方案 D）**：**AI 只做决策与建议，不持有任何写凭证**。
- 模型只能从固定词汇表（`ActionId`）中选择动作，**绝不拼接 shell 命令**；
- 命令由人写在项目仓库的配方文件（`ci/actions.yml` 等）里，平台负责解析、审计、隔离、编排、门禁；
- 所有改变环境的行为必须过：人工确认（门禁）→ 来源@commit（审计）→ Docker 沙箱（隔离）。

**技术栈**：

| 层 | 技术 | 版本 |
|---|---|---|
| 语言/运行时 | JDK | 21（虚拟线程已开启） |
| Agent 框架 | AgentScope Java | 2.0.0（`agentscope-core` + `agentscope-extensions-model-openai`） |
| 后端框架 | Spring Boot | 4.0.4 |
| 会话认证 | Sa-Token（`sa-token-spring-boot4-starter`） | BOM 管理 |
| 持久化 | SQLite（`org.xerial:sqlite-jdbc` 3.46.1.3）+ YAML 文件 | 单机零运维 |
| SSH | JSch（执行面远程部署） | BOM 管理 |
| 架构守门 | ArchUnit 1.3.0 + JUnit 5.11.3 | 随 `mvn verify` 执行 |
| 前端 | Vue 3 + Vite + TypeScript（strict）+ Element Plus + vue-router | 见 `platform/web/package.json` |
| 模型接入 | OpenAI 兼容端点（默认 DashScope，可切 DeepSeek / vLLM） | 设置页可热切换 |

**坐标**：Maven groupId `top.rongx`，Java 根包 `top.rongx.agent`，版本 `0.1.0-SNAPSHOT`。

---

## 2. 仓库结构

```
agentscope-ruoyi/
├── README.md              项目入口（阅读顺序、快速开始、提交规范）
├── docs/                  设计与规约文档（27 份 Markdown + 2 份汇报稿，含本文）
├── specs/                 功能规格与任务清单（dev 本机部署 / 环境注入健康 / 环境一致性）
├── platform/              平台代码（本文档主体）
│   ├── pom.xml            父 POM（模块与依赖管理）
│   ├── agent-domain/      领域模型 + 端口（零框架依赖）
│   ├── agent-infra/       端口适配实现（配方/git/SQLite/加密）
│   ├── agent-governance/  确认令牌 + 变更登记
│   ├── agent-executor/    执行面（动作执行器/流水线引擎/沙箱/部署器）
│   ├── agent-tools/       @Tool 工具 + 动作编排服务
│   ├── agent-runtime/     Agent 工厂 + 模型装配 + 对话服务
│   ├── agent-gateway/     Spring Boot 入口 + HTTP/SSE + 认证
│   ├── spec-checks/       规约自动化检查（仅 src/test）
│   ├── web/               Vue3 前端
│   ├── data/workspaces/   项目工作区（运行时生成：配方、历史、SQLite、HOST 部署状态、dev 进程日志）
│   └── .env               本地凭证（已 gitignore，绝不入库；启动时自动加载）
├── .workflow/build.yml    Gitee Go CI（本质：cd platform && mvn -B verify）
└── _research/             市场调研与源码能力盘点
```

---

## 3. 整体架构

### 3.1 分层与依赖方向

六边形（端口-适配器）架构，依赖严格单向，由 `spec-checks` 的 ArchUnit 规则 **A1** 在编译期强制：

```
                     HTTP / SSE / Webhook
                            │
                   ┌────────▼─────────┐
                   │  agent-gateway   │  Controller / Sa-Token / 事件映射
                   └────────┬─────────┘
                   ┌────────▼─────────┐
                   │  agent-runtime   │  AgentFactory / ChatService / 模型装配
                   └───┬──────────┬───┘
              ┌────────▼───┐  ┌───▼──────────────┐
              │ agent-tools│  │ agent-governance │  工具与门禁编排 / 确认令牌
              └────────┬───┘  └───┬──────────────┘
                       │ 仅依赖 domain（A1：tools/governance/executor/infra 只能访问 Domain）
              ┌────────▼──────────▼──┐      ┌──────────────────┐
              │     agent-domain     │◄─────│   agent-infra    │ 配方解析/git/SQLite 适配
              │ 实体/枚举/端口，零框架 │      └──────────────────┘
              └────────▲─────────────┘
              ┌────────┴─────────────┐
              │    agent-executor    │ 进程/容器执行、流水线引擎、部署器（无 LLM，A3）
              └──────────────────────┘
```

要点：
- **domain 不依赖任何框架**（A2：禁 Spring/AgentScope/JPA 包）；
- **执行面是确定性代码**（A3：executor 禁依赖 `io.agentscope`/langchain4j/spring-ai）；
- **Docker/运行时客户端只能在执行面**（A4：决策面禁 docker-java/fabric8）；
- 端口接口（`ActionRunner`/`ActionResolver`/`PipelineEngine`/`Deployer` 等）定义在 domain，实现分布在 executor/infra，运行时由 Spring 注入——上层只持有端口引用。

> 注：`agent-tools` 的 pom 虽声明了 `agent-executor`（供运行期装配注入实现），但 tools 源码不 import 任何 executor 类型——A1 规则（`consideringOnlyDependenciesInLayers`）保证 tools 层代码只访问 domain 端口。

### 3.2 请求主流程（一图流）

```
浏览器(Vue3) ──POST /api/v1/chat (Bearer token)──► ChatController(SSE)
    └► ChatService ─► AgentFactory 构造 ReActAgent（AgentScope）
         └► 模型选择工具（list_actions / plan_action / run_action / deploy_run / request_confirmation…）
              └► ActionService（统一门禁：plan → assertHostSafe → bindParams）
                   └► ActionRunner 端口（Process/Docker 实现，executor 模块）
                        └► ProcessExecutor 内核（argv 直exec、超时预算、日志脱敏）
    ◄── SSE 事件流（AgentEventMapper 转前端 DTO）──┘
一键部署：POST /deploy/{projectId}/agent (SSE) ──► 同一发布 Agent 闭环（仅 dev/test）
L3 高危：request_confirmation → 人 POST /changes/{id}/confirm 拿一次性令牌 → POST /changes/{id}/apply
补丁闭环：propose_patch → 变更单(patch:*) → 人复核 GET /changes/{id}/patch → confirm/apply → patch/<projectId> 分支 → 带新 sha 重跑
流水线：POST /ci/pipelines/{id}/run（或 retry）或 Gitee webhook → BuiltInPipelineEngine → TriggerScheduler（项目串行）
```

---

## 4. 后端模块详解

### 4.1 agent-domain —— 领域模型与端口（62 类，零框架）

包 `top.rongx.agent.domain.*`，按业务概念分包（禁 util 包，A6）。

| 包 | 关键类 | 职责 |
|---|---|---|
| `action` | `ActionId` | **动作词汇表**（方案 D 核心）：`backend_build`/`backend_test`/`frontend_build`/`frontend_test`/`image_package`（L2 隔离执行）、`stack_deploy`/`stack_rollback`（L3 生产），共 7 个。`autoExecutable()` 判定 L3 必须走变更确认；`fromWire()` 容错解析 |
| | `ActionSpec` | 解析后的动作规格：`steps` 为 `List<List<String>>` argv 数组（非 shell 字符串），确定性、可复现、可审计 |
| | `ActionSource` | 配方来源（Kind: CI_YML/MAKEFILE/TASKFILE/DEFAULT + ref + **commit**），证据链一环 |
| | `ActionResult` | 动作执行结果 = 结构化结果 + 脱敏日志尾部 |
| `artifact` | `ArtifactRef` | 产出物引用（路径 + sha256 digest） |
| `change` | `ChangePlan` / `ChangeStatus` / `PlanHasher` | 变更计划（审批对象）、状态、**计划哈希**（规约 §4.10 第三道守卫，防"确认 A 执行 B"） |
| `deploy` | `DeployRecipe` | 「某环境跑通」后固化的可复现脚本（版本化 vN，可重放/回滚） |
| | `DeployRequest` / `DeployResult` / `DeployTarget` | 平台部署器的输入/输出/目标（HOST/DOCKER/SERVER）；`DeployRequest` 携带 `env`（注入变量 Map）与 `network`（Docker 网络），`toString` 只输出 keySet 防值泄漏 |
| | `EnvironmentPromotion` | **环境晋升规则**：上服务器前必须有测试机成功案例（E-601/E-602） |
| | `RecipeRenderer` | 把 agent 走过的动作序列渲染成可复现流水线定义 |
| `port` | `ActionResolver` | 配方解析端口（infra 实现） |
| | `ActionRunner` | 动作执行端口（executor 实现），含 `isolated()` 能力声明 |
| | `PipelineEngine` | 流水线引擎端口：trigger/status/history/cancel/`preview`（触发前阶段预览，`StagePreview` 含命令级渲染 command/script/shell/scriptSha/builtin，可指定 `gitSha` 按历史提交预览）/`retryStage`（从失败阶段续跑）/`stopLocalDev`/`artifactFile`（产物文件下载）/`deleteHistory`/`runMeta`/`logFrom`（增量日志），内部 record `RunMeta` |
| | `CodePatcher` / `PatchStore` | 受限补丁端口（2026-11 新增）：`apply(CodePatch)` 校验 baseSha 后提交到独立分支（不碰用户当前分支）；`PatchStore` 待确认补丁存储（save/find/remove） |
| | `RepoInspector` | 仓库只读检视端口（2026-11 新增）：`status`（分支/HEAD/领先落后/改动聚合/最近提交）、`commits`、`diffStat`（from..to 提交列表+stat）；未接入返回空快照不抛错 |
| | `Deployer` | 部署器端口（方案 C：平台拥有部署语义） |
| | `SshAccess`（在 settings 包） | SSH 访问端口：连通性探测、远程命令、文件上传 |
| | `ChangeStore` / `RecipeStore` / `PipelineDefStore` / `PipelineDefAuthor` | 变更单/环境脚本/流水线定义存储；DefAuthor=agent 兜底生成器；`RecipeStore.invalidate(projectId,env)` 让当前固化脚本失效（定义重新生成后不再误用旧脚本） |
| | `ProjectRepository` / `ProjectRepoLocator` / `ProjectScaffolder` | 项目仓储、仓库定位（git 在哪/工作树放哪/怎么同步）、脚手架端口 |
| | `ModelRegistry` | 运行时模型注册表：返回"当前生效的模型连接" |
| `patch` | `CodePatch` | 受限代码补丁（record，2026-11 新增）：edits（path+content）、baseSha、branch（`patch/<projectId>`）、diff/added/removed；**只存意图不存令牌**，确认令牌仍只能由人经 HTTP 接口换取 |
| `project` | `Project` / `ProjectContext` / `ProjectEnv` | 托管项目（多项目托管 v4 的作用域边界）、会话级项目上下文（新增 `worktree`，空=主仓库）、环境 |
| `run` | `StageResult` / `StageStatus` | stage 执行结果与状态（执行器契约 §1.3） |
| | `RunRecoveryAdvisor` | 失败恢复建议端口（2026-11 新增）：`advise(projectId, env, runId, stage)` 返回 `Advice`（summary 根因一句话 / actions 候选动作 / patchable 能否用补丁修 / hint 下一步提示）；实现不得改仓库、不产令牌、不绕过门禁 |
| `runtime` | `RuntimeInspector` | 应用运行时取证端口（2026-11 新增）：`inspect(projectId, env)` 返回 `RuntimeInfo`（target/instanceId/running/healthy/restartCount/imageId/imageDigest/startedAt/hostPort/containerPort/healthUrl/network/logAvailable/logSource），回答"现在真正在跑的是什么、日志在哪" |
| `scaffold` | `ScaffoldPlan` | 脚手架产物：从仓库结构推导出的流水线脚本（预览/落盘） |
| `settings` | `ModelConnection` / `DeployServer` / `DeployServerRepository` / `ModelConnectionRepository` / `SshAccess` | 设置页管理的模型连接与部署服务器（替代 application.yml 硬编码） |
| | `ProjectEnvVar` / `ProjectEnvVarRepository` | 旧版 per-project 环境变量模型与仓储（保留兼容；新链路见 `env` 包） |
| `env` | `EnvironmentStore` | **环境子系统端口**：模板（templates/template/saveTemplate）、中间件资源（resources/saveResource/deleteResource/recordHealth）、模板变量（envVars/saveEnvVar/deleteEnvVar）、项目覆盖（overrides/saveOverride/deleteOverride）、有效视图（`effective(projectId,env)`）、Agent 绑定（saveBinding/binding/confirmBinding）、端口租约（lease/leases/saveLease）、单变量查询（`envVarValue`）、资源变量同步（`syncResourceVars`）、**本机服务租约**（serviceLeases/allServiceLeases/saveServiceLease/deleteServiceLease，2026-11 新增） |
| | `EffectiveEnvironment` | 部署时真正注入的合并结果（record）：vars 掩码展示、values 明文仅执行面可见、resources、templateCount/overrideCount、network/defaultPort/healthPath |
| | `EnvTemplate` / `EnvVariable` / `EnvResource` / `PortLease` / `EnvBinding` | dev/test/prod 模板（新增 `runtimeMode`（HOST_LOCAL/DOCKER/SERVER，空=沿用 target，非法值构造期拒绝）与 `namespace`（中间件命名隔离段，空=项目 ID）、`serverId`）；变量（source=RESOURCE/TEMPLATE/OVERRIDE，敏感值掩码）；中间件资源（source=LOCAL_DOCKER/REMOTE_DOCKER/MANUAL/COMPOSE，含健康检查结果与 `containerPort`/`internalHost` 容器网地址）；宿主端口租约；Agent 环境映射绑定（status=DRAFT/CONFIRMED/EXPIRED，TTL 24h） |
| | `EnvBindingPort` | 绑定端口：`draft`（生成草案）/ `requireForRun`（触发前校验，E-703） |
| | `StackAdapter` | 资源 → 默认变量映射（`DB_*`/`REDIS_*`/`RABBITMQ_*`/`MINIO_*`）：`variables(resource, projectId, env, defaultDb)` 返回 `StackVars`（vars + 宿主 host/port + 容器网 containerHost/containerPort），dev 场景同时给两种地址供 `ProjectEnvFiles` 按运行时选 |
| | `EnvProvisioner` | 中间件按需供给端口（2026-11 新增）：`ensure`（缺失时按需创建项目隔离容器并写回覆盖变量，必填密钥缺失返回 keys 拒绝编造）/ `start` / `stop`；失败 **E-702**；实现不得依赖 executor/gateway（A1） |
| | `EnvRuntime` | 应用运行时控制端口（2026-11 新增）：`ensureRunning`（test/prod 部署前确保 Docker 网络存在，幂等）/ `stopPrevious`（替换式发布停旧容器） |
| | `RuntimeMode` | 部署运行时枚举（HOST_LOCAL/DOCKER/SERVER，wire 与 target 互转；dev=本机直跑、test=容器、prod=服务器） |
| | `ServiceRuntime` | 本机进程服务运行时端口（2026-11 新增，dev 专用）：`start`/`stop`/`restart`/`running`/`logs`，返回 `ServiceState`（RUNNING/STOPPED/FAILED/UNKNOWN + pid + 端口 + 错误） |
| | `ServiceLease` | 本机服务端口租约（record，2026-11 新增）：项目+环境+服务（backend/frontend）固定占用宿主端口，防"重启后端口漂移、文档与配置对不上" |
| | `ProjectEnvFiles` | 项目环境文件受管写入（2026-11 新增）：`write`（部署前写 `.env`/`application-local-dev.yml`，只动受管标记块、幂等）/ `syncFromOverrides` / `managedKeys`；敏感值不落文件（由进程环境注入），`ENV_FILE_DISABLED` 可整体关闭 |
| `tool` | `ToolLevel` | 工具能力分级（规约 §1.2）：L0 只读 / L1 计划 / L2 隔离执行 / L3 生产 |

### 4.2 agent-infra —— 端口适配实现（21 类，仅依赖 domain）

| 包 | 关键类 | 职责 |
|---|---|---|
| `recipe` | `RecipeActionResolver` | **配方解析（方案 D 核心）**：按优先级 `ci/actions.yml → Taskfile.yml → Makefile → 内置默认` 解析出 `ActionSpec`（含每动作 `timeout`，缺省回退沙箱超时）；内置默认已覆盖 7 个动作（含 `frontend_test`）；`resolveCommit` 读 `.git/HEAD` 补审计链 |
| `git` | `GitWorktreeManager` | git 托管底层：**bare 对象库 + N 个 worktree**（v2 接入设计） |
| | `ProjectIntakeService` | **项目接入服务（v2）**：本地收编 / 远程 clone + 配方自检 + 状态机；接入时初始化 dev 环境记录（迁移旧 `project_env_vars` 数据进 `project_env_overrides`） |
| | `GitRepoInspector` | `RepoInspector` 实现（2026-11 新增）：复用 `GitWorktreeManager`/`ProjectRepoLocator`，DIRECT 读用户目录、MANAGED 读平台 bare；与「工作区」页同源 |
| | `GitConfig` | git 相关装配 |
| `persistence` | `InMemoryProjectRepository` | 预置项目源（application.yml `agent.projects.items`，只读；**当前 yml 已移除预置清单**，项目统一走接入） |
| | `IntakeProjectStore` | 接入项目的 **SQLite 存储**（重启不丢）；启动时把旧版 dev 环境数据迁移进 `project_env_overrides` |
| | `CompositeProjectRepository` | 两源合并（配置预置 ∪ 接入）对外唯一视图 |
| | `ProjectStoreConfig` / `ProjectsProperties` / `DefaultRepoLocator` | 仓储装配 / yml 绑定 / 直接模式仓库定位器 |
| `pipeline` | `FilePipelineDefStore` | 平台侧流水线定义落盘：`<workspaceRoot>/<projectId>/ci/{pipeline.yml,actions.yml}`（**不写用户仓库**） |
| | `FileRecipeStore` | 环境级固化脚本存储：`<workspace>/<projectId>/recipes/<env>/vN/{pipeline.yml,actions.yml,meta.yml}`；`invalidate` 只删 `current` 指针（历史版本保留，可人工 activate 回退） |
| `env` | `SqliteEnvironmentStore` | `EnvironmentStore` 端口的 SQLite 实现（库文件 `<workspace>/_platform/projects.db`）：表 `env_templates`（含 runtime_mode/namespace/server_id）/`env_resources`（含 container_port/internal_host）/`env_vars`/`project_env_overrides`/`env_bindings`/`deploy_port_leases`/**`project_service_leases`**（本机服务端口租约，主键 project+env+service）；启动 seed dev(host)/test(docker)/prod(server) 三套模板；旧表 `project_env_vars` 数据迁移进 `project_env_overrides`（不删旧表）；变量值 AES-GCM 加密、敏感项只回掩码 `****abcd`；env 名限 dev/test/prod |
| | `LocalProcessServiceRuntime` | `ServiceRuntime` 实现（2026-11 新增，dev 专用）：固定两种命令——backend `java -jar target/*.jar --server.port=<port>`（无 jar 退化 `mvnw spring-boot:run`）、frontend `npm run dev -- --port <port> --strictPort`；变量来自有效环境直接注入进程，前端另写受管 `.env.local`（只含非敏感 API 地址）；pid/日志落 `<workspace>/_platform/local/<projectId>/`（backend.log / frontend.log，`app_logs` dev 读同一份） |
| `patch` | `WorkspaceCodePatcher` | `CodePatcher` 实现（2026-11 新增）：MANAGED 在项目 worktree、DIRECT 在用户目录（分支仍由人管理，只提交不 checkout）；三道校验——baseSha 与 HEAD 一致（防覆盖并行改动）、路径白名单（`ci/**`/Jenkinsfile/构建配置/测试代码，禁业务代码与配置文件）、补丁非空；只提交到 `patch/<projectId>` 分支，永不 push |
| `scaffold` | `RecipeScaffolder` | **流水线脚手架**：识别工程根（仓库根 → `backend/` → 一层子目录，兼容 RuoYi 式"仓库根+工程目录"布局与 Java 目录内 UI 子目录）；Dockerfile 内已编译（RUN 含 mvn/gradle/npm）则不重复生成构建 stage；按 Dockerfile EXPOSE/HEALTHCHECK 推断端口与健康路径；`application*.yml/properties` 优先于猜默认值；扫描 `.env.example`/`docker/.env.example` 的 KEY 名（只读名不读值）写入 `deploy.env-required`；生成平台侧 `ci/` 脚本。**生成结果必须"开箱可跑"**：stage 只引用真实存在的动作，部署目标必须与仓库能力匹配（E-508），不覆盖已有文件（除非 overwrite） |
| `security` | `SecretBox` | 平台凭据加解密（**AES-256-GCM**）：Gitee token / SSH 密码 / 模型 API Key / 环境变量值共用 |
| `settings` | `SqliteModelConnectionStore` / `SqliteDeployServerStore` | 模型连接/服务器配置 SQLite 存储，密钥加密落库、接口只回掩码 |
| | `SqliteProjectEnvVarStore` | 旧表 `project_env_vars` 上的 `ProjectEnvVarRepository` 实现（兼容旧链路） |

### 4.3 agent-governance —— 变更确认守门（6 类，仅依赖 domain）

| 类 | 职责 |
|---|---|
| `ConfirmTokenService` | 确认令牌服务（PRD §4.10 四道守卫中的第 2、3 道）：`issue` 绑定 `userId+changeId+planHash`、5 分钟 TTL；`consume` 六重校验（存在/未用/未过期/changeId 一致/planHash 一致/标记已用） |
| `ConfirmToken` | 一次性确认令牌（record） |
| `ChangeRegistry` | 变更单存储（内存实现，`ChangeStore` 端口） |
| `PatchRegistry` | 待确认补丁存储（内存实现 `PatchStore`，2026-11 新增）：只保存与查询，签发确认令牌仍只能由登录用户经 `/changes/{id}/confirm` 完成 |
| `EnvPolicyService` | `PolicyPort` 实现（2026-11 新增）：dev/test/prod × L0–L3 默认矩阵（dev L3 DENY、test L3 CONFIRM、prod L2 DENY/L3 CONFIRM，prod L3 禁止 ALLOW），未知环境按 prod fail-closed；`requireNotDenied` 抛 E-707 |
| `ActionGate` | 历史空壳，已废弃（勿引用） |

### 4.4 agent-executor —— 执行面（23 类，仅依赖 domain，无 LLM）

**动作执行器**：

| 类 | 职责 |
|---|---|
| `AbstractActionRunner` | 执行骨架：超时预算、成功/失败判定、产物 digest、日志尾部；`validateArgv` 拒绝 shell 元字符依赖（argv 直 exec，不经 shell） |
| `ProcessActionRunner` | 宿主进程模式（`isolated()=false`） |
| `DockerActionRunner` | 容器内执行模式（`isolated()=true`，生产用） |
| `DockerSandbox` | 把业务命令包成 `docker run`：`--entrypoint=` 清空入口、`--user 1000:1000`、`--cap-drop ALL --security-opt no-new-privileges`、`--memory/--cpus/--pids-limit`、网络三档（bridge/build-net/none）、工作区挂 `/ws`；`withImage` 支持 stage 级专属工具链镜像；**依赖缓存卷**（`cache-volume-prefix`，按项目隔离挂 Maven `~/.m2` 与 npm cache，避免冷下载撞 10 分钟构建超时） |
| `ProcessExecutor` | 单条命令执行内核（宿主/容器共用）：`redact` 日志脱敏（Bearer/token/secret/password/api_key/64位hex/PEM 私钥块；64 位 hex 规则用负向后顾保留 `sha256:`/`sha512:` 镜像指纹）、`tail` 输出截断 |
| `SandboxConfig` | 执行模式装配（auto/docker/process 探测） |

**流水线引擎**：

| 类 | 职责 |
|---|---|
| `BuiltInPipelineEngine` | `PipelineEngine` 端口的真实实现：trigger→checkout 指定 sha→加载定义→逐 stage（分解为 `ActionSpec` 复用同一套 Runner 与门禁）→失败跑 on-failure→成功记 `lastGood` 镜像 tag；`verify` 自动裁掉 deploy/health stage；`preview` 返回同执行期的阶段裁剪并**渲染到命令级**（每个 stage 给出实际 argv/脚本内容与 scriptSha，支持按 `gitSha` 预览历史提交）；**只有含 deploy/stack_deploy 的成功运行**才更新 `lastGood` 并固化 recipe（纯 verify 不再固化脚本）；启动 `restoreHistory` 恢复历史与回滚锚点，非终态运行标记 CANCELLED；`retryStage` 复用保留现场从失败阶段续跑；`deleteHistory`/`artifactFile`（jar/dist 产物下载）；deploy stage 走环境注入链路（有效变量→E-701 中间件预检→E-509 必填预检→端口租约→env-snapshot 产物，见 §5.11）；**dev 本机直跑**（`LocalDevHostDeployer`+`ServiceRuntime`，E-601~E-604 本机语义）、**test/prod 部署前中间件按需供给**（`EnvProvisioner.ensure`，E-702）与 Docker 网络确保（`EnvRuntime.ensureRunning`）；失败时经 `RunRecoveryAdvisor` 产出恢复建议（见 §5.12） |
| `PipelineDefinition` / `PipelineDefinitionResolver` | 流水线定义（来自仓库 `ci/pipeline.yml`，随代码版本化；stage 只引用动作名不写命令）；`DeploySpec` 新增 `envRequired`（解析 `deploy.env-required`，支持字符串列表或 `- name: X`，非法名报错）与 **`services`（`ServiceSpec` 多容器部署：name/image/workdir/dockerfile/port/healthPath/envRequired，至少一个服务、名字不重复、单服务可省略 name）**；定义解析：决定这次用哪份定义（仓库版/平台侧固化 recipe 版） |
| `PipelineConfig` | 装配：把执行器组合成真正的流水线（Spring 组合根） |
| `TriggerScheduler` | 触发调度：**同项目串行**（防争抢工作区/缓存），不同项目并行（4 线程池） |
| `RunHistoryStore` | 构建历史持久化：每次运行一个 YAML（`_history/<runId>.yml`），重启可读回；条目含 stageCount 与各阶段耗时之和 durationMs（列表直接显示"这次跑了多久"）；支持删除单条历史；落盘失败不阻断构建 |

**部署器（方案 C：平台拥有部署语义）**：

| 类 | 职责 |
|---|---|
| `Deployers` | 部署器注册表：按 `DeployTarget` 选择实现，未命中 fail-closed（E-505） |
| `HostDeployer` | 本机直接运行部署（HOST）：`pb.environment()` 注入 `request.env()`；状态目录跨运行稳定（`_platform/deploy/<project>-<env>/`），含 `releases/<sha>/`、`current`/`previous` 回滚指针、`app.pid` 与 **`app.log`（进程 stdout/stderr 追加，供 `app_logs` 读取）**；有前端 dist 时复制到 `releases/<sha>/web` 并通过 `AGENT_WEB_DIST` 由后端同端口静态托管 |
| `DockerDeployer` | 本机容器替换式发布（DOCKER）：`docker run` 追加 `--network` 与按 TreeMap 排序的 `-e KEY=VALUE`；首次部署无旧容器时友好跳过；失败摘要先 `sanitize`（抹掉本次注入的 env 值）再 redact |
| `ServerDeployer` | 远程服务器部署（SERVER）：镜像经 ssh/scp 推到远端 docker 运行；远端端口预检（`ss -ltn` + `docker ps` 只读探测，冲突从期望端口续找 200 个，耗尽 E-704，不杀未知进程）；`-e` 值单引号转义注入、`--network` 透传；失败摘要 sanitize |
| `LocalDevHostDeployer` | **dev 本机直跑部署器**（2026-11 新增，规约 R02 已豁免登记）：不构建镜像、不整仓打包，产物就绪后拉起后端（jar/`spring-boot:run`）与**前端 dist**（平台生成 `static-server.cjs` 静态托管，`/prod-api`、`/dev-api` 反代本机后端），端口走服务租约（`ServiceLease`），健康检查用 HTTP 探活；停旧进程按 pid 文件+端口反查（只杀平台记录过的进程）；失败产 E-601~E-604 语义的本机诊断（工作区缺失/构建失败/端口占用/健康检查不过）。**dev 固定本机**：`deployTarget=docker` 会被忽略（见 §5.5），任何调用方都无法把 dev 变成 Docker |
| `StaticServerScript` | 前端静态托管脚本生成器（2026-11 新增）：生成 `static-server.cjs`（Node 原生、无第三方依赖），dist 就绪后按服务租约端口起静态服务，命中文件直出、未命中回 `index.html`（SPA 路由），并反代 `/prod-api`、`/dev-api` |
| `DockerEnvProvisioner` | `EnvProvisioner` 实现（2026-11 新增）：按资源类型跑官方镜像容器（mysql/redis/rabbitmq/minio…），**项目隔离命名**（namespace 默认项目 ID）、健康检查、密码随机生成（敏感值只回掩码）；必填密钥类变量缺失返回 keys 拒绝编造；失败 E-702 |
| `DockerEnvRuntime` | `EnvRuntime` 实现（2026-11 新增）：`ensureRunning` 幂等确保 Docker 网络存在；`stopPrevious` 替换式发布停旧容器 |
| `AppRuntimeInspector` | `RuntimeInspector` 实现（2026-11 新增）：按环境运行时分流——test/prod 用 `docker inspect` 读实例事实（状态/重启数/镜像 digest/端口映射/健康），dev 读平台进程日志尾部；只回答事实，不判失败原因 |
| `JschSsh` / `SshExec` | 纯 Java SSH 实现（JSch）：连通性探测、远程命令、文件上传；`SshExec` 实现 domain 的 `SshAccess` 端口（探测 docker 版本用 `{{.Server.Version}}` 模板并兜底 `docker info`） |

### 4.5 agent-tools —— 工具层（15 类）

| 类 | 职责 |
|---|---|
| `ActionService` | **动作编排服务**：工具与 HTTP 共用同一套门禁（plan → `assertHostSafe` → bindParams → run → specHash），避免两条路径行为不一致；`specHash` 把 `action+source.ref+commit+commands` 一起哈希——配方提交变了令牌即失效；`assertHostSafe`：非隔离执行遇 MAKEFILE/TASKFILE 来源或 L3 动作直接拒绝（fail-closed） |
| `ActionQueryTools` | 只读工具：`list_actions`、`plan_action` |
| `ActionCommandTools` | 写工具（L2 仅测试环境）：`run_action` |
| `CiQueryTools` | 流水线只读（L0）：`ci_list_pipelines`、`ci_status`、`read_run_log`（日志尾部，已脱敏）、`wait_run`（阻塞等终态，替代高频轮询）、`ci_history`、`release_trace`（按 sha 串提交→构建→配方→容器链路，缺失环节显式列 missing） |
| `CiCommandTools` | 流水线写（L2）：`ci_run`（内部先 `dryRun`，未通过 E-708）、`ci_cancel`；预检 `ci_dry_run`（L0 只读） |
| `RepoQueryTools` | 仓库只读取证（L0，2026-11 新增，经 `RepoInspector` 端口）：`git_status`、`grep`（正则检索，跳过构建目录/二进制）、`git_commits`、`git_diff`（from..to 提交+stat）、`list_files`、`read_file`（限仓库内文本，约 8000 字符截断） |
| `RuntimeTools` | 运行时取证（L0，2026-11 新增，经 `RuntimeInspector` 端口）：`env_runtime_status`（应用容器/进程事实：状态/重启数/镜像 digest/端口/网络）、`app_logs`（test/prod 读 `docker logs` 时间窗；HOST 部署读状态目录持久化的 `app.log`；dev 读平台托管进程日志尾部，均已脱敏）、`port_check`（宿主端口占用与归属） |
| `ChangeTools` | `request_confirmation`（L1）：只产出 changeId+planHash，**模型不能自己确认** |
| `PatchTools` | `propose_patch`（L1，2026-11 新增）：受限补丁——路径白名单（`ci/**`/Jenkinsfile/pom.xml/build.gradle/测试代码）、禁敏感文件、只登记变更单返回 changeId，**永不写仓库/产令牌**；应用须人经 `/changes/{id}/confirm → apply` |
| `DeployTools` | 部署 agent 专用：`deploy_env_status`、`deploy_run`、`deploy_save_recipe`——「把某个环境跑通并固化」；`deploy_run` 必须携带人工确认过的 `binding_id`（由 `env_mapping_draft` 生成，经 `EnvBindingPort.requireForRun` 校验，E-703） |
| `EnvTools` | 环境工具：`env_summary`（L0 只读：变量数/网络/端口/中间件健康，**不返回值**）、`env_var_probe`（L0 只读：变量指向的 host:port 属于本项目中间件/共享实例/外部地址，**不返回变量值**）、`env_mapping_draft`（L2 写：生成 DRAFT 绑定草案，prod 拒绝，须人工确认后才能 `deploy_run`）、`env_ensure`（L2 写：确保当前环境中间件容器存在并运行，缺失按需创建并写回覆盖变量，经 `EnvProvisioner`，E-702） |
| `ProjectTools` | 项目只读（L0）：`project_info`、`list_envs` |
| `ConfigTools` | 配置解析（L0，2026-11 新增）：`config_resolve`——项目环境优先，其次仓库 `.env` 声明，最后全量扫描 README/docs/compose/application；缺失或候选冲突返回 `NEED_USER_INPUT` 摘要，**要求 Agent 停止执行并等待用户回答**；密钥类声明值一律 `****` |
| `ConfigResolver` | 配置解析服务（非 @Tool）：优先级与全量扫描的唯一实现，工具与 `GET /projects/{id}/config/resolve` 共用；扫描候选带 `path:line` 与 envHint，不自动写环境 |
| `ToolCatalog` | 工具装配：三套无状态 `Toolkit` 按环境二选一（避免运行时改共享 Toolkit 激活集——会话状态 `activatedGroups` 会覆盖激活集，新会话写工具会整体消失）——`readOnlyToolkit`（生产会话：ProjectTools/CiQueryTools/ActionQueryTools/RepoQueryTools/RuntimeTools/ConfigTools，**不含 EnvTools 写能力**）、`agentToolkit`（@Primary，非生产：只读组+环境组全量含写，含 PatchTools）、`deployToolkit`（发布 Agent：ProjectTools/CiQueryTools/ActionQueryTools/RepoQueryTools/EnvTools/DeployTools/RuntimeTools/ChangeTools/ActionCommandTools/PatchTools/ConfigTools） |

### 4.6 agent-runtime —— Agent 装配与对话（7 类）

| 类 | 职责 |
|---|---|
| `AgentFactory` | 按 `ProjectContext` 构造 ReActAgent（注入 Toolkit、系统提示、RuntimeContext 传递项目上下文——模型无法伪造 projectId）。**权限模式（2026-11 修订）**：生产 `DEFAULT`（写必 ASK）、非生产 `BYPASS`（ACCEPT_EDITS 会让写工具停在无回传通道的 ASK 上；真正门禁在平台层：写工具拒绝生产、变更单人工确认、部署要 binding_id）；**不启用 meta 工具**（会话状态会覆盖工具组，新会话下写工具会整体消失）；会话状态经 `JsonFileAgentStateStore` 落 `data/agent-state`（按 userId+sessionId 续聊，重启不丢）；`systemPrompt` 改 public 供轨迹视图只读展示；发布 Agent 提示词闭环更新（先 `env_summary`、触发后 `wait_run` 等终态、根因在仓库配置用 `propose_patch`） |
| `ChatService` | 对话服务：一次用户输入 → AgentScope 事件流 |
| `ModelConfig` | 模型装配（OpenAI 兼容端点，默认 DashScope） |
| `AgentProperties` | `agent.model.*` 配置绑定（max-iters=8、temperature=0.2） |
| `DynamicModelProvider` | **动态模型提供者**：设置页保存后无需重启即可切换模型 |
| `SettingsModelRegistry` | `ModelRegistry` 实现：设置页（SQLite）配置优先，application.yml 兜底 |
| `AgentPipelineDefAuthor` | 流水线定义的 agent 兜底：规则脚手架识别不出技术栈时，请模型按同一契约给候选定义（仍走人工预览落盘） |

### 4.7 agent-gateway —— HTTP 边界与安全（37 类，Boot 入口）

| 包 | 关键类 | 职责 |
|---|---|---|
| 根 | `AgentApplication` | Spring Boot 入口（`agent-gateway-0.1.0-SNAPSHOT-exec.jar`，classifier=exec 以保留普通 jar 供 ArchUnit 读取） |
| `web` | `ChatController` | `POST /api/v1/chat`（SSE）；虚拟线程承载会话，身份来自 Sa-Token 会话；请求体 `role` 参数已移除（发布 Agent 走独立入口），新增 `worktree`（会话上下文提示） |
| | `AgentProfileController` | `GET /api/v1/agent/profile?projectId&env`（2026-11 新增）：返回当前生效的系统提示词与工具面，供轨迹视图只读核对"Agent 现在被怎么约束" |
| | `AgentEventMapper` | AgentScope 事件 → 前端 DTO（骨架期用反射映射）；`ModelCallEndEvent` 的 payload 补当前模型名（前端据此本地算 DeepSeek 费用，仅展示用途） |
| | `ActionController` | 动作 HTTP 接口（不经模型也能用）：list / plan / run / request；注释明确"不再接受请求头伪造身份" |
| | `ChangeController` | **整个平台唯一签发确认令牌的地方**：`GET /{id}`（确认卡片数据）、`GET /{id}/patch`（补丁 diff 供确认卡片复核，2026-11 新增）、`POST /{id}/confirm`、`POST /{id}/apply`；apply 对补丁类变更（kind=`patch:<id>`）走 `CodePatcher` 提交到 `patch/<projectId>` 分支并返回新 commit sha（可直接用于重跑），其余仍走 `ActionService.apply` |
| | `PipelineController` | 流水线运行查询与触发：pipelines/preview（触发前**命令级预览**，可带 `gitSha` 按历史提交渲染）/**POST `pipelines/{id}/dry-run`（只读预检）**/run/status/log（offset 增量续拉）/cancel/history（含 durationMs/stageCount）/**DELETE history/{runId}（删除单条）**/**POST runs/{runId}/retry（从失败阶段续跑）**/**GET runs/{runId}/artifacts/{index}/file（jar/dist 产物下载）**/**POST deploy/host/stop（停平台托管的本机前后端）**/deploy targets；**run 先 dry-run，未通过 E-708；带 `dryRunHash` 且与当前不一致抛 E-706**；run 可带 `bindingId`（Agent 代触发时校验绑定），trigger params 记录 `actor`（`ui:<user>` 或 `agent`）、`confirmedBy`、`dryRunHash`/`dryRunFingerprint` |
| | `DeployAgentController` | `POST /deploy/{projectId}/agent`（SSE，2026-11 新增）：一键自动部署——发布 Agent 自主探索闭环（env_summary→env_mapping_draft→人确认绑定→deploy_run→wait_run→read_run_log→propose_patch→deploy_save_recipe）；仅放行 dev/test，生产直接拒绝；复用 ChatController 的 SSE 映射与虚拟线程模式 |
| | `DeployController` | 环境视角部署：envs / confirm（上服务器一次人工确认）/ replay / recipes / activate / ports（端口租约查询，排障与审计） |
| | `ProjectIntakeController` | 项目接入与 git 管理（v2）：intake validate/local/remote/status、git status/diff/sync/remotes/commits/**checkout（切分支/工作树）**、worktrees、列表/删除/**GET /{id}/envs（项目环境列表）** |
| | `ProjectScaffoldController` | 脚手架：GET 预览 / POST 落盘流水线定义；覆盖保存（overwrite）时联动 `RecipeStore.invalidate` 失效 dev/test/prod 当前固化脚本（响应含 `resetRecipes`） |
| | `SettingsController` | 设置：服务器 CRUD+测试+默认、模型连接 CRUD+测试+默认+options；默认服务器可直接删除（自动提升下一台启用服务器为默认）；编辑态"测试"沿用库中密钥并把结果写回该行 |
| | `EnvironmentController` | **环境模板/资源/变量/覆盖/绑定**（`/settings/envs*`、`/settings/projects/{id}/overrides`、`/effective`、`/env-binding/*`，权限 settings:read/write）：模板 CRUD、中间件发现（本机/远程 Docker 只读探测，候选暂存内存、密码不出后端）与导入、手工登记、健康检查、**资源 start/stop（经 `EnvProvisioner`，E-702）**、模板变量、项目覆盖、有效视图、绑定草案/确认/查询、**`GET /projects/{id}/env-check`（部署前体检：缺失变量/未引用资源/健康汇总）**、**明文回显 `GET /envs/{env}/vars/{key}/reveal` 与 `GET /projects/{id}/effective/reveal`（单独授权 `settings:reveal`，管理员与运维可看，每次回显写审计日志）**、**本机 dev 服务管理 `GET/POST /projects/{id}/services[/{service}/start|stop|restart]` 与 `…/services/{service}/logs`（委托 `LocalServiceManager`）** |
| | `ProjectEnvController` | `/settings/projects/{id}/envs`：项目环境变量读写（已统一写 `project_env_overrides`，经 `EnvironmentStore`；value 留空=保持原值；视图带 `valueKind`/`secretRef`） |
| | `SecretController` | **项目密钥库（2026-11 新增）**：`GET/POST /settings/projects/{id}/secrets`、`POST …/{name}/rotate`、`DELETE …/{name}`（权限 settings:read/write）；只回名称/版本/`****`，明文只写不读 |
| | `ConfigController` | **配置解析（2026-11 新增）**：`GET /projects/{id}/config/resolve?env&keys`，与 `config_resolve` 工具共用 `ConfigResolver`；返回 `READY` / `NEED_USER_INPUT`、resolved/missing/conflicts/candidates/summary |
| | `PolicyController` | **分环境策略只读视图（2026-11 新增）**：`GET /api/v1/policies` 返回 dev/test/prod × L0–L3 的 ALLOW/CONFIRM/DENY 矩阵 |
| | `GiteeBrowserController` | Gitee OAuth（start/callback，state 防 CSRF）+ PAT 绑定（AES-GCM 加密落库，接口永不回明文）+ 仓库列表；上游鉴权失败回 400/502（401 专指本平台会话失效，避免前端误清登录态） |
| | `GiteeWebhookController` | push 事件自动触发 verify 流水线；Token/HMAC-SHA256 校验（未配置密钥即拒）；同 commit 幂等去重 |
| | `FsBrowserController` | 本地目录浏览（供前端选文件夹）：ls / probe / roots |
| | `ProjectContextResolver` | 请求参数 projectId → `ProjectContext`（服务端绑定） |
| | `GlobalExceptionHandler` | 统一错误响应 `{code, message, traceId}` |
| `auth` | `AuthController` | 登录/登出/me（唯一签发会话令牌处） |
| | `SaTokenConfig` | Sa-Token 装配与路由保护 |
| | `CurrentUser` / `Role` / `UserStore` / `SaPermissionSource` | 当前身份出口 / 角色最小集合（DEVOPS 新增 `settings:read`/`settings:reveal`，可查明文并留审计） / 用户仓库（内存版）/ 权限码数据源 |
| `security` | `PasswordVerifier` / `SessionPasswordVerifier` | 高危变更二级密码校验（早期 `FailClosedPasswordVerifier` 已被会话版替代） |
| `deploy` | `DeployGate` | **服务器操作双重前置**（两条入口共用，防"换个 URL 绕过"）：测试环境成功案例 + 一次人工确认 |
| | `ChatRunRecoveryAdvisor` | `RunRecoveryAdvisor` 实现（2026-11 新增）：按失败阶段类型 + 日志尾部关键词规则化判定根因（编译错/缺依赖/端口占用/健康检查不过/镜像缺失…），给出候选动作与"可否用 propose_patch 修"的提示；纯建议不改仓库、不产令牌 |
| `gitee` | `GiteeAccountStore` | Gitee 账号绑定存储（token AES-256-GCM 加密落 SQLite） |
| `settings` | `ModelProbe` | 模型连通性探测：调 `/chat/completions`（max_tokens=1） |
| | `EnvBindingService` | `EnvBindingPort` 实现：draft 扫描仓库 `.env.example`/`docker/.env.example` 的 KEY（只读 key 不读值）产出映射草案（keys/missing/warnings E-701/effectiveHash）；confirm TTL 24h；`requireForRun` 校验项目/环境/已确认/未过期/变量未变更（E-703） |
| | `EnvironmentDiscoveryService` | 中间件只读发现：本机 `docker ps/inspect` 或远程 SSH `docker inspect`，识别 mysql/redis/rabbitmq/minio/postgres/mongo；无共享网络时 host=127.0.0.1 或服务器 IP + 宿主映射端口；**统一中间件引用判定**（2026-11）：按有效变量指向的 host:port 反查资源归属（本项目/共享实例/外部），dev 端点改用宿主发布端口 |
| | `LocalServiceManager` | 本机 dev 服务编排（2026-11 新增）：委托 `ServiceRuntime`（`LocalProcessServiceRuntime`）做 start/stop/restart/logs，端口经 `ServiceLease` 租约，状态供 EnvironmentController services 端点与前端状态条 |
| `config` | `Jackson2Config` | **显式定义 Jackson 2 ObjectMapper**——Boot 4 默认 Jackson 3，但 AgentScope 事件按 Jackson 2 注解序列化（启动期踩坑修复） |
| `web` | `StaticWebConfig` | **可选前端静态托管**（2026-11 新增）：HOST 部署注入 `AGENT_WEB_DIST` 后，同一端口提供 SPA——`/` 指向 `index.html`、未知无扩展名路径 fallback 到入口页、`api/` 与 `actuator/` 不兜底；dist 未就绪时不注册任何规则 |

### 4.8 spec-checks —— 规约自动化检查（仅 src/test，4 个测试类）

`mvn verify` 执行，任一违规直接构建失败——架构不是文档约定，而是编译期门禁。详见 §12。

---

## 5. 核心机制

### 5.1 动作契约（模型只选动作，不写命令）
1. 模型调用 `list_actions` 看到词汇表（`ActionId` 7 个动作）；
2. `plan_action` → `RecipeActionResolver` 从项目工作区解析配方（优先级 `ci/actions.yml → Taskfile.yml → Makefile → 内置默认`），产出 `ActionSpec`（argv 数组 + 来源 + commit + 超时 + 产物声明）；
3. `run_action` → `ActionService` 门禁 → `ActionRunner`（Docker 优先）→ `ProcessExecutor` 执行。命令**不经 shell**，`; & |` 只是数据。

### 5.2 审计链（来源@commit）
`ActionSource(Kind, ref, commit)` 记录配方出处；`ActionService.specHash` 将 `action + ref + commit + commands` 一起哈希。**配方所在提交变了 → planHash 变 → 已签发确认令牌失效**。

### 5.3 L3 高危闭环（四道守卫）
```
模型 request_confirmation ──► ChangeRegistry 登记 ChangePlan（含 planHash）
人（浏览器）POST /changes/{id}/confirm ──► ConfirmTokenService.issue（绑 userId+changeId+planHash，5min，高危需二级密码）
人 POST /changes/{id}/apply（带 X-Confirm-Token）──► consume 六重校验 ──► ActionService.apply 执行
```
关键约束：**没有 confirm 工具**——确认只能由人在 HTTP 接口完成；展示字段而非自然语言摘要。

### 5.4 沙箱与 fail-closed
- `DockerSandbox.wrap`：非 root、cap-drop ALL、no-new-privileges、资源限额、网络三档；
- `ActionService.assertHostSafe`：无 Docker 回退宿主时，不可审计来源（Makefile/Taskfile）或 L3 动作**一律拒绝**；
- 部署器未命中目标、webhook 未配密钥、二级密码未接入——全部 fail-closed 拒绝而非放行。

### 5.5 流水线编排（BuiltInPipelineEngine）
`trigger(projectId, pipelineId, gitSha)` → `TriggerScheduler`（项目级串行）→ checkout 指定 sha → `PipelineDefinitionResolver` 选定义（仓库 `ci/pipeline.yml` / 平台固化 recipe vN）→ 逐 stage 分解为 `ActionSpec` 执行（stage 可声明专属 `image`/`workdir`，支持 monorepo 双栈）→ 失败执行 `on-failure`（回滚）→ 成功记 `lastGood` → `RunHistoryStore` 落 YAML（重启恢复；恢复时非终态运行标记 CANCELLED）。

要点（2026-11 修订）：
- `preview(projectId, pipelineId, env, gitSha)` 与真实执行共用同一裁剪逻辑：verify 裁掉 deploy/health/rollback/stack_deploy，无 Dockerfile 再裁 image_package——"运行前看到什么，跑的就是什么"；预览**渲染到命令级**（每个 stage 给出实际 argv/脚本内容/scriptSha，可指定历史提交 sha 预览当时的定义）；
- **只有含 deploy/stack_deploy 的成功运行**才更新 `lastGood` 并固化 recipe：纯 verify 成功不再把脚本固化成"发布证据"；
- 重新生成脚手架定义（覆盖保存）会 `RecipeStore.invalidate` 当前环境脚本，历史版本仍可 activate 回退；
- **失败续跑**：`retryStage(runId, stageId)` 复用保留的构建现场只重跑失败阶段及之后；产物文件（jar/dist）可按 `GET /ci/runs/{runId}/artifacts/{index}/file` 下载；历史可单条删除；
- **dev 本机直跑**：deploy stage 在 dev 环境走 `LocalDevHostDeployer`（进程 + 服务租约 + HTTP 探活，E-601~E-604 本机诊断），test/prod 保持容器/服务器部署；**dev 固定本机**——`deployTarget=docker` 会被忽略并在日志说明，任何调用方（UI 误选 / 历史重跑 / Agent 触发）都无法把 dev 变成 Docker；HOST 部署的进程日志持久化到 `_platform/deploy/<project>-<env>/app.log`，有前端 dist 时通过 `AGENT_WEB_DIST` 由后端同端口静态托管；
- **部署前环境供给**：test/prod deploy 前 `EnvProvisioner.ensure` 按需拉起缺失中间件（E-702）、`EnvRuntime.ensureRunning` 确保网络；dev 部署前 `ProjectEnvFiles.write` 写受管 `.env`/`application-local-dev.yml`（敏感值不落文件）。

### 5.6 项目接入（v2：bare + worktree）
`ProjectIntakeService`：本地目录收编（DIRECT 不复制仓库）或远程 clone（Gitee token 优先用户绑定、平台兜底）→ `GitWorktreeManager` 建 bare 对象库 + 按需 worktree → 配方自检 → `RecipeScaffolder` 按仓库结构**在平台侧**生成 `ci/` 定义（不写用户仓库；规则识别失败时 `AgentPipelineDefAuthor` 让模型给候选）。接入项目落 SQLite（`IntakeProjectStore`），与 yml 预置项目经 `CompositeProjectRepository` 合并。

### 5.7 环境固化与发布（方案 C）
Agent 用 `deploy_run` 在测试环境把流程跑通 → `deploy_save_recipe` 固化为 `recipes/<env>/vN/`（可重放/回滚/activate 为当前定义）→ 上服务器需 `DeployGate` 双重前置：**该环境已有测试机成功案例（EnvironmentPromotion）+ 一次人工确认**（E-601/E-602），由 `ServerDeployer` 经 SSH 推送镜像并 docker 运行（远端端口预检，冲突续找，耗尽 E-704）。

**Agent 触发的第三道前置（2026-11 新增）**：`deploy_run` 必须携带 `binding_id`——先 `env_mapping_draft` 生成映射草案，再由人在设置页确认（TTL 24h）；未确认/过期/变量已变更一律 E-703 拒绝（见 §5.11）。

**一键自动部署入口（2026-11 新增）**：`POST /deploy/{projectId}/agent`（`DeployAgentController`，SSE）驱动发布 Agent 走完整闭环（见 §5.13）；仅放行 dev/test，生产直接拒绝，门禁仍由确认卡片把住。

### 5.8 模型配置热切换
`SettingsController` 管理 `ModelConnection`（API Key AES-GCM 加密落 SQLite，只回掩码）→ `SettingsModelRegistry`（设置优先、yml 兜底）→ `DynamicModelProvider` 使切换**无需重启**；`ModelProbe` 提供"测试连接"。

### 5.9 认证与授权
Sa-Token（`Authorization: Bearer <token>`，30 天会话）：`AuthController` 登录签发 → 路由拦截保护 `/api/v1/**`（登录页除外）→ `SaPermissionSource` 按 `Role` 给权限码 → 各 Controller 经 `CurrentUser` 取身份。已替代早期 `X-User-Id` 请求头方案。

### 5.10 日志脱敏
`ProcessExecutor.redact` 过滤 Bearer、赋值型 token/secret/password/api_key、64 位 hex、PEM 私钥块（64 位 hex 规则用负向后顾保留 `sha256:` 镜像指纹）；`BuiltInPipelineEngine` 日志尾部与 `RunState.snapshot` 均走脱敏——凭证不进证据链。部署链路额外两层：`DockerDeployer.sanitize` 在失败摘要中按本次注入的 env 值全局抹除（`KEY=***`，值长度≥6）；`ServerDeployer` 远端 `-e` 参数单引号转义，值含空格/引号不被 shell 拆开。

### 5.11 环境模板、项目覆盖与部署注入（env 子系统，2026-11 新增）
决策文档：`docs/环境变量与环境模板设计-v2.md`（v1 仅存档）。核心思想：**变量按环境配一次、项目只写差异、部署只注入合并结果、Agent 只能"建议映射"不能"擅自使用"**。

1. **三层数据**（`EnvironmentStore` 端口，SQLite 实现 `SqliteEnvironmentStore`）：
   - 环境模板（dev/test/prod）：网络、默认部署目标/端口、健康路径、**runtimeMode（HOST_LOCAL/DOCKER/SERVER）与 namespace（中间件命名隔离段，默认项目 ID）**、模板变量、中间件资源；
   - 中间件资源：来源=本机 Docker 发现 / 远程 SSH 发现 / 手工登记 / Compose；发现与健康检测只读，但**缺失时平台可按需创建并启停**（`EnvProvisioner.ensure/start/stop`，项目隔离命名、密码随机生成，失败 E-702）；`StackAdapter` 按资源类型生成默认变量映射（`DB_*`/`REDIS_*`/`RABBITMQ_*`/`MINIO_*`，同时给宿主与容器网两种地址）；
   - 项目覆盖：同名 key 覆盖模板值，项目专属 key 标"仅本项目"。
2. **有效视图**：`effective(projectId, env)` 合并三层；对外（设置页/接口）变量一律掩码，明文只到执行面；所有值 AES-GCM 加密落库。**明文回显单独授权**：`settings:reveal`（管理员+运维）经 `/vars/{key}/reveal`、`/effective/reveal` 查看，每次写审计日志；`GET /projects/{id}/env-check` 提供部署前体检（缺失变量/未引用资源/健康汇总，"只认被项目变量引用的资源"）。
3. **部署注入链路**（`BuiltInPipelineEngine` deploy stage）：
   `effective` 取变量 → **E-701 中间件预检**（资源已登记且最近检查"未运行"→ 拒绝启动容器；从未检查仅告警）→ **E-509 必填预检**（`deploy.env-required` 与有效变量求差，缺失不启动容器）→ **端口租约**（沿用既有租约；冲突从期望端口 +1 起 200 个内找本机空闲端口；SERVER 不做本机探测）→ `DeployRequest` 带 env+network 注入 → 部署后若实际端口≠租约端口则回写（远端重分配场景）。
4. **env-snapshot 产物**：每次部署记 `ArtifactRef(kind="env-snapshot", ref="env:<test>", digest=对 key+sha256(value) 的排序摘要)`，meta 含变量数/模板数/覆盖数/敏感数/网络/租约端口/bindingId/confirmedBy/各 key 哈希——**不落任何明文值**，可审计"这次注入的是哪一版映射"。
5. **健康检查增强**：health stage URL 端口替换为租约端口；docker 目标每轮重试前 `docker inspect` 探测 exited/dead 即提前失败；失败产出容器日志摘要（优先 ERROR 行 ≤3 条，截 300 字符）并清理失败容器（E-401 摘要含退出码）。
6. **Agent 绑定闭环**：`env_mapping_draft`（扫描仓库 `.env.example` 的 KEY，与有效变量比对出 missing/告警，写 DRAFT，不含值）→ 人在「设置 → 项目环境」两步确认（CONFIRMED，TTL 24h）→ `deploy_run` 携带 `binding_id`，`EnvBindingService.requireForRun` 五重校验（存在/属于本项目该环境/已确认/未过期/变量摘要未变，否则 **E-703**）。prod 环境拒绝 Agent 草案触发。
7. **dev 本机服务运行时（2026-11 新增）**：dev 环境 `runtimeMode=HOST_LOCAL`——平台把项目前后端拉起来/停掉/探活（`ServiceRuntime`/`LocalProcessServiceRuntime`）：命令固定两种（backend `java -jar` 或退化 `mvnw spring-boot:run`、frontend `npm run dev -- --port --strictPort`），不接受任意拼接；端口按 `ServiceLease` 项目+环境+服务固定租约（落 `project_service_leases` 表，防重启漂移）；有效变量直接注入进程环境，前端另写受管 `.env.local`（只含非敏感 API 地址），`ProjectEnvFiles` 写 `.env`/`application-local-dev.yml` 只动受管标记块；pid/日志落 `_platform/local/<projectId>/`，停旧进程按 pid 文件+端口反查，只杀平台记录过的进程；随时可交还给 IDE（`POST /ci/deploy/host/stop`）。

### 5.12 受限代码补丁（Agent 修仓库的唯一入口，2026-11 新增）
失败根因在仓库配置时，Agent 用 `propose_patch`（L1）提交补丁：
1. `PatchTools` 校验路径白名单（`ci/**`、Jenkinsfile、`**/pom.xml`、`**/build.gradle(.kts)`、`**/src/test/**`）、拒绝敏感文件、拒绝生产环境、拒绝未接入项目；生成统一 baseSha 的 `CodePatch`（diff/added/removed 现算），经 `ChangeRegistry` 登记 kind=`patch:<patchId>` 的变更单并 `PatchRegistry` 存储——**工具本身永不写仓库、不产令牌**；
2. 人经 `GET /changes/{id}/patch` 复核 diff → `POST /changes/{id}/confirm` 拿一次性令牌 → `POST /changes/{id}/apply`；
3. `WorkspaceCodePatcher.apply` 三道校验（baseSha==HEAD 防覆盖并行改动、路径白名单复核、补丁非空）后提交到 `patch/<projectId>` 独立分支（不碰用户当前分支、永不 push），返回新 commit sha 可直接重跑流水线。

### 5.13 失败恢复与一键自动部署（2026-11 新增）
- 流水线失败时 `BuiltInPipelineEngine` 调 `RunRecoveryAdvisor`（gateway 实现 `ChatRunRecoveryAdvisor`）：按阶段类型+日志关键词规则化产出 Advice（根因一句话/候选动作/是否可 propose_patch 修/下一步提示），随运行状态返回给前端与 Agent；
- `POST /deploy/{projectId}/agent`（`DeployAgentController`，SSE）驱动发布 Agent 闭环：`env_summary`→`env_mapping_draft`（无绑定先请人确认）→`deploy_run`（带 binding_id）→`wait_run` 等终态→失败 `read_run_log` 定位→根因在配置则 `propose_patch`→跑通后 `deploy_save_recipe` 固化；仅 dev/test，生产拒绝。

### 5.14 配置事实层、密钥引用与分环境门控（2026-11 新增）
设计文档：`docs/环境配置事实层与分环境门控-方案-v1.md`。目标：Agent 不再猜配置、密钥不落明文、不同环境给不同自由度。

1. **配置解析优先级**（`ConfigResolver`，工具与 HTTP 共用）：
   项目环境 `effective(projectId, env)` → 仓库 `.env`/`.env.example` 声明（只读 key + 非敏感值）→ 全量扫描 README/docs/compose/application（候选带 `path:line`、envHint，最多 300 条/40 组）。结果分 `resolved / missing / conflicts / candidates`；缺失或候选冲突返回 `NEED_USER_INPUT` 摘要，`config_resolve` 与系统提示要求 **Agent 停止执行、把摘要交给用户并等待回答**，禁止猜测；扫描候选不自动写环境。密钥类声明值一律 `****`。
2. **密钥引用**（`SecretEntry`/`SecretRefs`/`EnvironmentStore`）：
   SQLite `secrets` 表按 `project_id+env+name` 存 AES-GCM 密文与版本；环境变量支持整值 `secret://name` 与嵌入 `${secret:name}`。`effective()` 在服务端解析，接口与工具只看得到 `secret://name` 或 `****`；缺失引用进入 `missingSecrets`，部署预检 **E-704 fail-closed**（不把占位符原样注入）。轮换只增版本（v+1），`digestValues()` 以 `ref@version` 参与 env-snapshot，明文不进摘要。管理入口：`/settings/projects/{id}/secrets`（列表只回名称/版本/掩码）。
3. **分环境策略**（domain `PolicyPort` + governance `EnvPolicyService`）：
   默认矩阵 dev(L0–L2 ALLOW, L3 DENY) / test(L0–L2 ALLOW, L3 CONFIRM) / prod(L0–L1 ALLOW, L2 DENY, L3 CONFIRM)，prod L3 构造期即拒绝 ALLOW；未知环境按 prod 处理。`ActionService`、`CiCommandTools`、`AgentFactory` 均改为经 `PolicyPort` 判定，替换硬编码 `isProduction()`；拒绝抛 `PolicyDeniedException`（E-707）。只读视图 `GET /policies`。
4. **dry-run 阶段门控**（`PipelineEngine.dryRun`）：
   只读预检复用 `preview`，额外检查 `missingSecrets`、`deploy.env-required` 缺失（E-509）、可执行阶段为空、image/docker 类型所需 Docker 可用性（5s 超时），产出 `ok/blockers/warnings/stages + fingerprint + reportHash`，零 outward 变更。`POST /ci/pipelines/{id}/dry-run` 与工具 `ci_dry_run` 调用；`run` 与 `ci_run` 执行前先 dry-run，未通过 **E-708**，传入的 `dryRunHash` 与当前不一致 **E-706**（代码/配方/环境/密钥版本变化即失效）。指纹与报告哈希随 trigger params 进入证据链。
5. **前端**：设置页新增「密钥库」（增删/轮换）；项目环境表单支持「直接填值 / 引用密钥」，并提供「配置解析」弹窗展示 resolved/missing/conflicts/candidates；流水线运行卡新增「预检」按钮与报告面板（阻塞项/警告/哈希）。

---

## 6. HTTP API 一览

前缀 `/api/v1`；除 `/auth/login` 外均需 Bearer 会话。错误统一 `{code, message, traceId}`。

| Controller | 方法与路径 | 作用 |
|---|---|---|
| AuthController | POST `/auth/login` `/auth/logout` `/auth/me` | 登录/登出/当前身份 |
| ChatController | POST `/chat`（SSE） | 对话，流式 Agent 事件（body：projectId/env/sessionId/message/worktree） |
| AgentProfileController | GET `/agent/profile?projectId&env` | 当前生效系统提示词与工具面（轨迹视图只读核对） |
| ActionController | GET `/actions`、GET `/actions/{action}/plan` | 看动作目录 / 看执行计划（命令原文） |
| | POST `/actions/{action}/run`、POST `/actions/{action}/request` | L2 直接执行 / L3 登记变更 |
| ChangeController | GET `/changes/{id}`、GET `/changes/{id}/patch`、POST `/changes/{id}/confirm`、POST `/changes/{id}/apply` | 确认卡片 / 补丁 diff 复核 / 签发一次性令牌 / 带令牌执行（补丁类提交到 `patch/<projectId>` 分支） |
| PipelineController | GET `/ci/pipelines`、GET `/ci/pipelines/{id}/preview` | 流水线列表 / 运行前**命令级预览**（verify 裁部署、无 Dockerfile 裁镜像；可带 `gitSha` 按历史提交渲染） |
| | POST `/ci/pipelines/{id}/run` | 触发（gitSha/env/部署目标；可带 `bindingId` 表示 Agent 代触发，须已人工确认） |
| | GET `/ci/runs/{runId}`、GET `/ci/runs/{runId}/log`、POST `/ci/runs/{runId}/cancel`、POST `/ci/runs/{runId}/retry` | 状态 / 增量日志（offset）/ 取消 / **从失败阶段续跑（stageId）** |
| | GET `/ci/runs/{runId}/artifacts/{index}/file` | **产物文件下载（jar/dist，限本次运行归档目录）** |
| | GET `/ci/history`、DELETE `/ci/history/{runId}`、GET `/ci/deploy/targets`、POST `/ci/deploy/host/stop` | 构建历史（含 durationMs）/ 删除单条 / 可选部署目标（含不可用原因）/ 停平台托管的本机前后端 |
| DeployController | GET `/deploy/{projectId}/envs`、GET `…/recipes`、POST `…/recipes/activate` | 环境跑通状态 / 固化脚本版本 / 激活 |
| | POST `/deploy/{projectId}/confirm`、POST `/deploy/{projectId}/replay` | 上服务器人工确认 / 重放固化脚本 |
| | GET `/deploy/ports?projectId&env` | 端口租约列表（实际宿主端口，排障/审计） |
| DeployAgentController | POST `/deploy/{projectId}/agent`（SSE） | 一键自动部署：发布 Agent 自主探索闭环（仅 dev/test，生产拒绝） |
| ProjectIntakeController | POST `/projects/intake/validate` `/intake/local` `/intake/remote`、GET `/intake/{id}/status` | 接入校验 / 本地收编 / 远程 clone / 状态机 |
| | GET `/projects`、GET `/projects/{id}/envs`、DELETE `/projects/{id}` | 项目列表（预置∪接入）/ 项目环境列表 / 移除 |
| | GET `/projects/{id}/git/{status,diff,remotes,commits}`、POST `…/git/sync`、POST `…/git/checkout` | git 查看、同步与分支/工作树切换 |
| | GET `/projects/{id}/worktrees`、DELETE `…/worktrees/{name}` | worktree 管理 |
| ProjectScaffoldController | GET/POST `/projects/{id}/scaffold` | 脚手架预览 / 落盘 |
| SettingsController | GET/POST/PUT/DELETE `/settings/servers[/{id}]`、POST `…/default` `…/test` | 部署服务器 CRUD、设默认、连通测试（编辑态留空密钥沿用库值，结果写回该行） |
| | GET/POST/PUT/DELETE `/settings/models[/{id}]`、POST `…/default` `…/test`、GET `…/options` | 模型连接 CRUD、设默认、探测 |
| EnvironmentController | GET `/settings/envs[/{env}]`、PUT `/settings/envs/{env}` | 环境模板列表/详情/保存 |
| | POST `/settings/envs/{env}/discover`、`…/import`、POST/DELETE `/settings/envs/{env}/resources[/{id}]`、POST `…/resources/{id}/check`、POST `…/resources/{id}/start|stop` | 中间件发现（候选暂存内存，密码不出后端）/ 导入 / 登记删除 / 健康检查 / **资源启停（E-702）** |
| | GET/PUT `/settings/envs/{env}/vars`、DELETE `…/vars/{key}`、GET `…/vars/{key}/reveal` | 模板变量（敏感值只回掩码；**reveal 需 `settings:reveal`，写审计**） |
| | GET/PUT `/settings/projects/{projectId}/overrides?env`、DELETE `…/overrides/{key}` | 项目覆盖 |
| | GET `/settings/projects/{projectId}/effective`、GET `…/effective/reveal`、GET `…/env-check` | 有效视图（部署真正注入的合并结果）/ 明文有效视图（审计）/ **部署前体检（缺失变量/引用健康汇总）** |
| | GET `/settings/projects/{projectId}/services`、GET `…/services/{service}/logs`、POST `…/services/{service}/start|stop|restart` | **本机 dev 前后端服务管理（LocalServiceManager，2026-11 新增）** |
| | POST `/settings/projects/{projectId}/env-binding/draft`、POST `…/env-binding/{bindingId}/confirm`、GET `…/env-binding/{bindingId}` | Agent 绑定草案 / 人工确认 / 查询 |
| ProjectEnvController | GET/PUT `/settings/projects/{projectId}/envs?env`、DELETE `…/envs/{key}` | 项目环境变量（统一写 project_env_overrides；value 空=保持原值） |
| SecretController | GET/POST `/settings/projects/{projectId}/secrets?env`、POST `…/{name}/rotate`、DELETE `…/{name}` | **项目密钥库：只写不读，引用写法 `secret://name`；轮换版本 +1** |
| ConfigController | GET `/projects/{projectId}/config/resolve?env&keys` | **配置解析：项目环境优先 → 仓库声明 → 全量扫描；`NEED_USER_INPUT` 时需用户回答** |
| PolicyController | GET `/policies` | **分环境策略矩阵（dev/test/prod × L0–L3）** |
| PipelineController | POST `/ci/pipelines/{pipelineId}/dry-run` | **部署预检（只读，零变更）：blockers/warnings/reportHash/fingerprint/stages** |
| GiteeBrowserController | GET `/gitee/oauth/start` `/oauth/callback`、POST/DELETE/GET `/gitee/bind`、GET `/bind/status`、GET `/repos` | OAuth 授权、PAT 绑定（永不回明文）、仓库浏览 |
| GiteeWebhookController | POST `/webhooks/gitee/push`、GET `/webhooks/gitee/history` | push 自动触发 verify（Token/HMAC + 幂等） |
| FsBrowserController | GET `/fs/ls` `/fs/probe` `/fs/roots` | 服务端目录浏览（受 shared-root 边界约束） |
| Actuator | GET `/actuator/health` | 健康检查（8090） |

> ⚠️ 前端 `web/src/api/project.ts` 调用了 `GET /api/v1/fs/read`，当前后端 `FsBrowserController` 未实现该端点（文件预览的已知缺口）。

---

## 7. 模型工具（@Tool）一览 —— 33 个方法 / 12 个工具类

AgentScope `Toolkit` 注册（`ToolCatalog` 装配三套无状态单例，按环境二选一：`readOnlyToolkit` 生产会话 / `agentToolkit` 非生产全量 / `deployToolkit` 发布 Agent；`agent-tools` 模块共 15 类，其中 12 个是 @Tool 类，另含 `ToolCatalog`、`ActionService` 与 `ConfigResolver`）：

| 工具 | 级别 | 所在类 | 作用 |
|---|---|---|---|
| `project_info` / `list_envs` | L0 只读 | ProjectTools | 项目与环境信息 |
| `ci_list_pipelines` / `ci_status` / `ci_history` | L0 只读 | CiQueryTools | 流水线只读视图 |
| `read_run_log` | L0 只读 | CiQueryTools | 读某次运行日志尾部（已脱敏） |
| `wait_run` | L0 只读 | CiQueryTools | 阻塞等运行到终态（替代高频轮询，触发后判断结果的正确方式） |
| `release_trace` | L0 只读 | CiQueryTools | 按 sha 串「提交→构建→配方→容器」链路，缺失环节显式列 missing |
| `git_status` / `git_commits` / `git_diff` | L0 只读 | RepoQueryTools | 分支/HEAD/领先落后/改动聚合、提交历史、from..to 提交+stat（经 `RepoInspector`，与「工作区」页同源） |
| `grep` / `list_files` / `read_file` | L0 只读 | RepoQueryTools | 正则检索（跳过构建目录/二进制）/ 文件树 / 读文本（限仓库内约 8000 字符截断） |
| `env_runtime_status` / `app_logs` / `port_check` | L0 只读 | RuntimeTools | 运行时取证（经 `RuntimeInspector`：容器/进程事实；`app_logs` 依次读 docker logs / HOST `app.log` / dev 进程日志；端口占用归属） |
| `list_actions` / `plan_action` | L0/L1 | ActionQueryTools | 动作目录 / 执行计划 |
| `run_action` | L2 | ActionCommandTools | 执行隔离动作（仅测试环境） |
| `ci_run` / `ci_cancel` | L2 | CiCommandTools | 流水线触发/取消（`ci_run` 内部先 dry-run，未通过 E-708） |
| `ci_dry_run` | L0 只读 | CiCommandTools | **部署预检：返回 ok/blockers/warnings/reportHash/fingerprint/stages，不产生任何变更** |
| `config_resolve` | L0 只读 | ConfigTools | **配置解析：项目环境优先 + 全量扫描候选；`NEED_USER_INPUT` 必须停止并等待用户回答** |
| `deploy_env_status` / `deploy_save_recipe` | L2 | DeployTools | 环境跑通与固化 |
| `deploy_run` | L2 | DeployTools | 触发运行（仅 dev/test；**必须带人工确认过的 `binding_id`**） |
| `env_summary` | L0 只读 | EnvTools | 环境摘要：变量数/网络/端口/中间件健康（**不返回变量值**） |
| `env_var_probe` | L0 只读 | EnvTools | 变量指向的 host:port 属于本项目中间件/共享实例/外部地址（**不返回值**） |
| `env_mapping_draft` | L2 | EnvTools | 生成环境映射草案（DRAFT，不含值；须人确认后 `deploy_run` 才能用） |
| `env_ensure` | L2 | EnvTools | 确保当前环境中间件容器存在并运行（缺失按需创建并写回覆盖变量；密钥缺失拒绝编造，E-702） |
| `propose_patch` | L1 | PatchTools | 受限补丁提案：白名单路径（`ci/**`/Jenkinsfile/构建配置/测试代码）、生成变更单返回 changeId，**永不写仓库/产令牌** |
| `request_confirmation` | L1 | ChangeTools | 登记变更（**无 confirm 工具**，确认只能走 HTTP） |

---

## 8. 前端架构（platform/web）

Vue 3 + Vite + TypeScript strict + Element Plus；`npm run build` 走 vue-tsc 严格检查。

### 8.1 路由与页面（src/router.ts）

| 路由 | 视图 | 职责 |
|---|---|---|
| `/login` | `LoginView.vue` | 登录（公开页） |
| `/` → `/chat` | `ChatView.vue` | 对话（SSE 流式、工具调用卡片、审批卡片、**流式思考块、轨迹视图、DeepSeek 费用统计、本机环境状态条**） |
| `/pipeline` | `PipelineView.vue` | 流水线工作台（触发、**命令级预览、单步重试、产物下载**、历史（可删除/耗时）、stage 时间线、日志） |
| `/projects` | `ProjectListView.vue` | 项目列表 |
| `/projects/intake` | `ProjectIntakeView.vue` | 项目接入向导（本地目录/Gitee 仓库选择） |
| `/projects/:id` | `ProjectDetailView.vue` | 项目详情（文件树 + 预览） |
| `/settings` | `SettingsView.vue` | 设置（服务器/模型/环境模板/项目环境/**密钥库**/Gitee 仓库分区；支持 `?section=` 深链） |

路由守卫：无 token → `/login`；有 token 未加载身份 → 调 `/auth/me` 校验（失效清 token）；任意接口返回 401 经 `setUnauthorizedHandler` 统一收口（清身份 + 跳登录并带 redirect）。

### 8.2 分层

| 目录 | 内容 |
|---|---|
| `src/api/` | `http.ts`（统一 fetch + Bearer）、`auth.ts`、`chat.ts`、`agent.ts`（`GET /agent/profile`，2026-11 新增）、`sse.ts`（fetch+ReadableStream 解析 SSE，支持 POST 单流与 GET 续传流、`Last-Event-ID`/`afterSequence`、4xx 不重试）、`action.ts`、`change.ts`（含 `getChangePatch` 补丁复核）、`pipeline.ts`（含 `retryStage`/`artifactFilePath`/`deleteHistory`/`stopLocalDeploy`/**`dryRunPipeline`**）、`deploy.ts`、`project.ts`（含 `projectEnvs`/`gitCheckout`/`fsRead`）、`settings.ts`（含资源 start/stop、services 本机服务管理、reveal 明文回显、env-check、**密钥库 CRUD/轮换、`resolveProjectConfig`、`listPolicies`**） |
| `src/views/` | 7 个页面（见上表） |
| `src/components/` | `chat/`（ConversationTimeline、MessageItem、ToolCallCard、ApprovalRequestCard、MarkdownView、**ThinkingBlock 流式思考块（默认折叠、完成后摘要"思考 N 步"）**、**ChatStatusStrip 底部状态条（连接/模型/费用/工具数）**、**ChatTraceRail 轨迹侧栏（按轮折叠）**、**ChatTraceView 轨迹视图（系统提示词+工具面+逐轮步骤，数据仅本地会话）**、**EnvironmentStatusBar 对话页环境状态条（中间件/应用/端口/最近运行，只读）**）、`pipeline/`（TriggerBar 运行卡（提交+命令级阶段预览+运行+**预检/dry-run 报告**）、StageTimeline、RunLog、HistoryList（环境筛选/删除/耗时）、EnvStateBand 环境胶囊切换条、EnvSummary 运行前环境摘要（变量/引用健康/体检结论，只读不返回值））、`project/`（ProjectTree、ProjectOverview、TreeNodeRow、useProjectTree、FilePreview、TabbedFiles、ResizableSplitter）、`settings/`（ModelPanel、ServerPanel、RepoPanel、EnvTemplatePanel 环境模板（模板/发现/登记/健康检查/启停/模板变量/reveal）、ProjectEnvPanel 项目环境（覆盖/有效视图/端口租约/本机服务/Agent 绑定两步确认/**密钥引用与配置解析**）、**SecretPanel 项目密钥库（增删/轮换，只回名称与版本）**）、`DirPickerDialog.vue`、`GiteeRepoPicker.vue`。**ActionPanel 已删除**（工作台不再展示动作面板） |
| `src/composables/` | `useAgentChat.ts`（对话事件消费）、`useRunTracker.ts`（运行轮询 + 日志 offset 增量拉取）、`useFollowScroll.ts` |
| `src/state/` | `agentEventReducer.ts`（SSE 事件 → 消息状态归约，含同名 `.test.ts` 单测）、`sessionHistory.ts`（**会话时间线按 projectId+env 存 localStorage，每会话上限 200 条，重启不丢**） |
| `src/stores/` | `app.ts`（登录身份、**会话权限模式 readonly/confirm/auto（纯前端确认策略，服务端门禁不受影响）**等全局状态；无 Pinia，轻量 store 模块） |
| `src/layouts/` | `AppLayout.vue` + `AppAside`/`PageHeader`（外壳） |
| `src/utils/` | `markdown.ts`、`pipelineLabels.ts`、`projectId.ts`、`status.ts`、`deepseekPricing.ts`（**DeepSeek 官方价目本地算费用：按模型档位/缓存命中/思考模式计价，仅展示用途，后端 usage 原样透传**） |
| `src/types.ts` | 与后端 DTO 对应的核心类型（ActionView、RunView、StageResult、ChangeView、身份、**TraceTurn/TraceStep 轨迹轮次**等） |

### 8.3 Vite 配置（vite.config.ts）

端口 `5173`；`/api` 代理到 `http://127.0.0.1:8090`；SSE 响应设 `cache-control: no-cache`。默认绑 `::1`，需 IPv4 用 `npm run dev -- --host 127.0.0.1`。

### 8.4 回归脚本（web/scripts/*.mjs，需本地 dev server）

| 脚本 | 验证点 |
|---|---|
| `layout-check.mjs` | 页面布局 |
| `login-check.mjs` / `migrate-auth-test.mjs` | 登录与认证迁移 |
| `shell-guard-check.mjs` | 外壳路由守卫 |
| `interaction-check.mjs` | 对话交互 |
| `pipeline-ux-check.mjs` / `pipeline-def-check.mjs` | 流水线交互 / 定义展示 |
| `intake-id-check.mjs` / `project-id-check.mjs` | 项目接入 ID 规则 |
| `_tmp-env-status-check.mjs` | 对话页环境状态条回归（临时脚本，2026-11） |

---

## 9. 依赖关系

### 9.1 Maven 模块图（编译期，源自各 pom.xml）

```
agent-domain   ← 无依赖（仅父 POM）
agent-infra    ← domain + sqlite-jdbc
agent-governance ← domain
agent-executor ← domain + jsch
agent-tools    ← domain + governance + executor + agentscope-core
agent-runtime  ← domain + governance + tools + agentscope-core + agentscope-extensions-model-openai
agent-gateway  ← runtime + infra + executor + spring-boot-starter-webmvc/actuator/validation
                  + sa-token-spring-boot4-starter + spring-security-crypto + jackson-databind
spec-checks    ← 仅 test（archunit），分析 top.rongx.agent 全包
```

### 9.2 版本管理要点（父 pom）

- **先 import Spring Boot BOM、再 import AgentScope BOM**——Spring 版本以 Boot 4.0.4 为准，防止被反向降级；
- `spring-boot-maven-plugin` repackage 使用 `classifier=exec`：主 artifact 保持普通 jar（ArchUnit 才能读到 gateway 类），可执行 jar 为 `*-exec.jar`；
- 全局 test 依赖 JUnit 5.11.3；编译 `release=21` + `-parameters`。

### 9.3 运行时第三方要点

| 依赖 | 用途 | 注意事项 |
|---|---|---|
| AgentScope 2.0.0 | ReAct 循环、Toolkit、事件流 | 2.0.0 **没有** `Toolkit.addToolToGroup`，用 `toolkit.registration().tool(obj).group(...).apply()` |
| Boot 4 + Jackson | Web/序列化 | Boot 4 默认 Jackson 3；AgentScope 事件按 Jackson 2 注解，必须显式提供 `Jackson2Config#jackson2ObjectMapper` |
| Sa-Token | 会话/权限 | 读 `Authorization: Bearer` 头，不读 cookie |
| sqlite-jdbc | 接入项目/设置/Gitee 绑定存储 | 单文件，位于 `data/` 下 |
| JSch | 远程部署 SSH | 仅 executor 模块使用（A4 约束决策面不得有运行时客户端） |

---

## 10. 配置与环境变量

### 10.1 application.yml（agent-gateway）关键项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `server.port` | 8090 | 后端端口 |
| `spring.threads.virtual.enabled` | true | 虚拟线程 |
| `sa-token.*` | Bearer 头、30 天 | 会话 |
| `agent.workspace.root` | `./data/workspaces` | 项目工作区根（配方/历史/SQLite 所在地） |
| `agent.pipeline.history` | true | 构建历史落盘 |
| `agent.pipeline.image-prefix` | `ci` | 发布镜像 tag 前缀 |
| `agent.projects.items` | **已移除**（预置 RY 下线，项目统一走接入） | 配置预置项目源 |
| `agent.intake.shared-root` | 空（全盘） | 目录浏览边界 |
| `agent.intake.gitee-token` | 空 | 平台兜底 Gitee token（私有库 clone） |
| `agent.gitee.client-id/secret/redirect-uri` | 空 | Gitee OAuth 应用 |
| `agent.sandbox.mode/image/network/memory/cpus/pids-limit/user/cache-volume-prefix` | auto / `maven:3.9-eclipse-temurin-21` / bridge / 2g / 2 / 512 / 1000:1000 / `agent-cache-` | 执行沙箱（缓存卷按项目隔离挂 Maven/npm 依赖） |
| `agent.model.base-url/api-key/model/max-iters/temperature` | DashScope 兼容端点 / 空 / qwen-plus / 8 / 0.2 | 模型兜底（设置页优先） |

### 10.2 环境变量名（值放 `platform/.env`，已 gitignore，绝不入库）

| 变量 | 用途 |
|---|---|
| `AGENT_MODEL_API_KEY` / `AGENT_MODEL_BASE_URL` / `AGENT_MODEL_NAME` | 模型接入（OpenAI 兼容） |
| `AGENT_WORKSPACE_ROOT` / `AGENT_SANDBOX_MODE` / `AGENT_SANDBOX_NETWORK` / `AGENT_SANDBOX_IMAGE` / `AGENT_SANDBOX_CACHE_VOLUME` / `AGENT_PIPELINE_IMAGE_PREFIX` | 工作区与沙箱覆盖 |
| `AGENT_INTAKE_SHARED_ROOT` / `AGENT_GITEE_TOKEN` | 接入边界与兜底 token |
| `GITEE_CLIENT_ID` / `GITEE_CLIENT_SECRET` / `GITEE_REDIRECT_URI` | Gitee OAuth |
| `AGENT_WEB_DIST` | HOST 部署器内部注入的前端 dist 目录（一般无需手工设置）；设置后 `StaticWebConfig` 在同一端口提供 SPA |
| ~~`RY_REPO_URL` 等预置 RY 覆盖~~ | 已随预置项目清单移除（2026-11） |

> 规约 §8：凭证不入配置库；`spec-checks` 的 `ConfigCredentialScanTest` 会扫描决策面配置中的写凭证键。

---

## 11. 构建与运行

### 11.1 前置

JDK 21、Maven 3.9+、Node 20+、（可选）Docker（沙箱执行与镜像发布；无 Docker 时 L2 可回退宿主但 L3 被 fail-closed 拒绝）。

### 11.2 后端

```powershell
cd platform
$env:AGENT_MODEL_API_KEY="sk-xxx"    # 或依赖 platform/.env（AgentApplication 启动时 loadDotEnv 加载，真实环境变量优先）
mvn -q verify                        # 编译 + 规约检查 + 单测（推荐先跑）
mvn -q -DskipTests package           # 只打包
java -jar agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT-exec.jar
# 健康检查: http://127.0.0.1:8090/actuator/health
# 登录种子账号: admin/admin-2026、devops/devops-2026、viewer/viewer-2026
```

日常开发建议用 IDE 运行 `AgentApplication` + Spring Boot devtools/热部署（改依赖或新增模块才需要完整 `mvn package`），避免反复重启打包。

### 11.3 前端

```powershell
cd platform/web
npm install
npm run dev          # http://localhost:5173（/api 代理到 8090）
npm run build        # vue-tsc strict + 产物
npm run typecheck
```

> Vite 默认绑 `::1`，用 `127.0.0.1` 打不开属正常；需 IPv4 用 `npm run dev -- --host 127.0.0.1`。

### 11.4 CI

`.workflow/build.yml`（Gitee Go）：本质一条 `cd platform && mvn -B verify`；分支保护 + verify 必须绿。

---

## 12. 测试与质量门禁

### 12.1 spec-checks（`mvn verify` 强制，违规即构建失败）

| 测试类 | 内容 |
|---|---|
| `ArchitectureRulesTest`（ArchUnit） | **A1** 分层单向（Gateway→Runtime→Tools/Governance→Domain←Executor/Infra）；**A2** domain 零框架；**A3** executor 无 LLM；**A4** 决策面无 docker-java/fabric8；**A6** 禁 util/common/helper 包；**A7** tools 禁 ProcessBuilder；**A9** 禁字段注入 |
| `ForbiddenPatternScanTest` | R01 禁 Runtime.exec、R02 禁 shell 解释器、R03 禁 MyBatis `${}`、R04 禁 new Thread、R05 禁字段注入、R06 金额禁 double、R07 日志禁打印凭证、R08 禁 SELECT *、R09 禁 java.util.Date、R10 禁空 catch、R11 禁 System.out、R12 禁动态脚本、R13 软删用 deleted_at。误报豁免：`// spec-ignore: R06 <原因>`（原因不足 3 字视为无效；现登记豁免仅 `LocalDevHostDeployer` 两处 R02——Windows 的 mvn/npm 是 .cmd 批处理须经 `cmd /c`，参数全常量无拼接，已写入规约 §13）。扫描已剪枝（跳过 target/.git/node_modules/dist/data），避免误扫产物 |
| `ConfigCredentialScanTest` | 决策面（gateway/runtime/tools/governance）配置不得含写凭证键 |
| `MigrationGuardTest` | 已发布迁移文件不得修改（基线 `spec-checks/migration-baseline.txt`） |

### 12.2 单元测试分布

| 模块 | 代表测试 |
|---|---|
| agent-domain | `ProjectEnvFilesTest`（受管块写入幂等/敏感值不落文件）、`StackAdapterTest`（宿主/容器网双地址映射） |
| agent-executor | `ProcessActionRunnerTest`、`DockerActionRunnerTest`、`DockerSandboxTest`、`ProcessExecutorRedactTest`（脱敏）、`PipelineDefinition*Test`（含 GuardTest 多服务/非法定义拒绝、ResolverTest、PreviewRenderTest 命令级预览）、`DeployersTest`、`DeployRecipeFlowTest`、`JschSshTest`、`HostDeployerTest`、`DockerDeployerTest`、`DeployEnvPrecheckTest`（E-509/E-701 预检）、`DockerDeployerEnvTest`（env/network 注入与 sanitize）、`LocalDevHostDeployerTest`/`LocalDevHostDeployerLeaseTest`（dev 本机直跑与端口租约）、`DockerEnvProvisionerTest`/`DockerEnvProvisionerAddressingTest`/`DockerEnvProvisionerSeedTest`（E-702 供给/地址/种子）、`ServiceTopologyTest`（服务拓扑解析）、`RunHistoryStoreTest`（历史落盘与恢复） |
| agent-governance | `ConfirmTokenServiceTest`（六重校验） |
| agent-infra | `RecipeActionResolverTest`、`GitWorktreeManagerTest`、`IntakeScaffoldTest`、`RecipeCheckTest`、`IntakeProjectStoreTest`、`FileRecipeStoreTest`、`SecretBoxTest`、`SqliteSettingsStoreTest`、`SqliteProjectEnvVarStoreTest`、`RecipeScaffolderTest`（工程根识别/env-required）、`WorkspaceCodePatcherTest`（baseSha/白名单/独立分支三道校验） |
| agent-runtime | `DynamicModelProviderTest` |
| agent-gateway | `PipelineAcceptanceTest`、`DualStackPipelineTest`（Java+Vue 双栈）、`IntakeE2ETest`、`ActionUsabilityTest`、`ToolChainTest`、`DeployStageTest`、`DotEnvLoaderTest`、`GiteeWebhookControllerTest`、`SettingsControllerTest`、`ModelProbeTest`、`ChatRunRecoveryAdvisorFormatTest`（恢复建议格式）、`ToolEvalBaselineTest`/`LiveModelToolEvalTest`（需真实模型 Key，缺 Key 自动跳过） |
| web | `state/agentEventReducer.test.ts` + `scripts/*.mjs` 无头浏览器回归 |

---

## 13. 已知边界与留白

1. **会话持久化已部分落地（2026-11）**——模型上下文经 `JsonFileAgentStateStore` 按 userId+sessionId 落 `data/agent-state`（重启续聊）；前端时间线存 localStorage（`sessionHistory.ts`，每会话 200 条上限）；但**轨迹视图数据仅本地会话**（刷新前不落服务端）、多租户未做。
2. **`GET /api/v1/fs/read` 未实现**——前端文件预览调用该端点，后端 `FsBrowserController` 只有 ls/probe/roots。
3. **用户体系为内存版**（`UserStore`），未接数据库；高危二级密码走 `SessionPasswordVerifier`。
4. **事件映射用反射**（`AgentEventMapper`）——正式版应把 32 类 AgentScope 事件映射成稳定枚举。
5. **`changeId` 由前端正则从文本提取**——应由后端发专用 SSE `change` 事件。
6. **构建历史为 YAML 文件**（`RunHistoryStore`）——换 MySQL 成本低（接口已留）。
7. **`ActionGate` 为废弃空壳**——门禁逻辑已并入 `ActionService`/`DeployGate`。
8. **窄控制器 8 步滚动发布未做**——当前 deploy 由仓库配方/平台部署器提供。
9. **沙箱不注入私服凭据**——需要时挂只读 `settings.xml`。
10. **中间件发现候选暂存进程内存**（`EnvironmentController.candidates`）——重启后未导入的候选丢失，需重新发现。
11. **待确认补丁存储为内存版**（`PatchRegistry`）——重启后未应用的补丁提案丢失（变更单本身同样在 `ChangeRegistry` 内存版），生产需落库。
12. **DeepSeek 费用为前端本地估算**——后端只透传 usage，价目表更新需同步 `utils/deepseekPricing.ts`；其他模型显示"费用未知"。

---

## 附录：关键文件速查

| 关注点 | 文件（相对 platform/） |
|---|---|
| 动作词汇表 | `agent-domain/src/main/java/top/rongx/agent/domain/action/ActionId.java` |
| 配方解析 | `agent-infra/src/main/java/top/rongx/agent/infra/recipe/RecipeActionResolver.java` |
| 门禁编排 | `agent-tools/src/main/java/top/rongx/agent/tools/ActionService.java` |
| 令牌服务 | `agent-governance/src/main/java/top/rongx/agent/governance/ConfirmTokenService.java` |
| 编排引擎 | `agent-executor/src/main/java/top/rongx/agent/executor/BuiltInPipelineEngine.java` |
| 容器沙箱 | `agent-executor/src/main/java/top/rongx/agent/executor/DockerSandbox.java` |
| 部署门禁 | `agent-gateway/src/main/java/top/rongx/agent/gateway/deploy/DeployGate.java` |
| 环境端口/存储 | `agent-domain/src/main/java/top/rongx/agent/domain/env/EnvironmentStore.java`、`agent-infra/src/main/java/top/rongx/agent/infra/env/SqliteEnvironmentStore.java` |
| 环境 HTTP/绑定 | `agent-gateway/src/main/java/top/rongx/agent/gateway/web/EnvironmentController.java`、`agent-gateway/src/main/java/top/rongx/agent/gateway/settings/EnvBindingService.java` |
| 环境前端面板 | `web/src/components/settings/EnvTemplatePanel.vue`、`web/src/components/settings/ProjectEnvPanel.vue`、`web/src/components/settings/SecretPanel.vue` |
| 中间件供给/运行时 | `agent-domain/.../domain/env/EnvProvisioner.java`、`agent-executor/src/main/java/top/rongx/agent/executor/DockerEnvProvisioner.java` |
| dev 本机直跑 | `agent-executor/src/main/java/top/rongx/agent/executor/LocalDevHostDeployer.java`、`agent-infra/src/main/java/top/rongx/agent/infra/env/LocalProcessServiceRuntime.java` |
| 受限补丁 | `agent-domain/.../domain/patch/CodePatch.java`、`agent-infra/src/main/java/top/rongx/agent/infra/patch/WorkspaceCodePatcher.java`、`agent-tools/src/main/java/top/rongx/agent/tools/PatchTools.java` |
| 运行时取证/恢复建议 | `agent-executor/src/main/java/top/rongx/agent/executor/AppRuntimeInspector.java`、`agent-gateway/src/main/java/top/rongx/agent/gateway/deploy/ChatRunRecoveryAdvisor.java` |
| 一键自动部署入口 | `agent-gateway/src/main/java/top/rongx/agent/gateway/web/DeployAgentController.java` |
| 轨迹与费用前端 | `web/src/components/chat/ChatTraceView.vue`、`web/src/utils/deepseekPricing.ts`、`web/src/state/sessionHistory.ts` |
| 项目接入 | `agent-infra/src/main/java/top/rongx/agent/infra/git/ProjectIntakeService.java` |
| Agent 工厂 | `agent-runtime/src/main/java/top/rongx/agent/runtime/AgentFactory.java` |
| 分层强制 | `spec-checks/src/test/java/top/rongx/agent/arch/ArchitectureRulesTest.java` |
| 后端配置 | `agent-gateway/src/main/resources/application.yml` |
| 前端路由 | `web/src/router.ts` |
