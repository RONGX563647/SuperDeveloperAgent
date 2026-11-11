# Code Wiki：研发运维智能体平台（agentscope-ruoyi）

> 基线：2026-11 当前主干代码（`platform/` 7 个运行时模块 + `spec-checks` 校验模块 + `web/` 前端）。
> 本文档面向新成员与代码评审，覆盖：整体架构、模块职责、关键类与函数、依赖关系、HTTP API、运行方式与质量门禁。
> 配套规约：《项目规约-v1.0》《PRD-DevOps-Agent平台-v1.0》《规约-流水线执行器-v1.0》（均在 `docs/`）。
> 环境子系统决策：《环境模板与中间件自动注入 v1/v2》（`docs/环境变量与环境模板设计-v1.md` 仅存档、`-v2.md` 为现行决策）。

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
├── docs/                  设计与规约文档（PRD / 规约 / 架构分析 / 验收报告等 20+ 份）
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
│   ├── data/workspaces/   项目工作区（运行时生成：配方、历史、SQLite）
│   └── .env               本地凭证（已 gitignore，绝不入库）
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
L3 高危：request_confirmation → 人 POST /changes/{id}/confirm 拿一次性令牌 → POST /changes/{id}/apply
流水线：POST /ci/pipelines/{id}/run 或 Gitee webhook → BuiltInPipelineEngine → TriggerScheduler（项目串行）
```

---

## 4. 后端模块详解

### 4.1 agent-domain —— 领域模型与端口（49 类，零框架）

包 `top.rongx.agent.domain.*`，按业务概念分包（禁 util 包，A6）。

| 包 | 关键类 | 职责 |
|---|---|---|
| `action` | `ActionId` | **动作词汇表**（方案 D 核心）：`backend_build`/`backend_test`/`frontend_build`/`image_package`（L2 隔离执行）、`stack_deploy`/`stack_rollback`（L3 生产）。`autoExecutable()` 判定 L3 必须走变更确认；`fromWire()` 容错解析 |
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
| | `PipelineEngine` | 流水线引擎端口：trigger/status/history/cancel/`preview`（触发前阶段预览，含 `StagePreview` record） |
| | `Deployer` | 部署器端口（方案 C：平台拥有部署语义） |
| | `SshAccess`（在 settings 包） | SSH 访问端口：连通性探测、远程命令、文件上传 |
| | `ChangeStore` / `RecipeStore` / `PipelineDefStore` / `PipelineDefAuthor` | 变更单/环境脚本/流水线定义存储；DefAuthor=agent 兜底生成器；`RecipeStore.invalidate(projectId,env)` 让当前固化脚本失效（定义重新生成后不再误用旧脚本） |
| | `ProjectRepository` / `ProjectRepoLocator` / `ProjectScaffolder` | 项目仓储、仓库定位（git 在哪/工作树放哪/怎么同步）、脚手架端口 |
| | `ModelRegistry` | 运行时模型注册表：返回"当前生效的模型连接" |
| `project` | `Project` / `ProjectContext` / `ProjectEnv` | 托管项目（多项目托管 v4 的作用域边界）、会话级项目上下文、环境 |
| `run` | `StageResult` / `StageStatus` | stage 执行结果与状态（执行器契约 §1.3） |
| `scaffold` | `ScaffoldPlan` | 脚手架产物：从仓库结构推导出的流水线脚本（预览/落盘） |
| `settings` | `ModelConnection` / `DeployServer` / `DeployServerRepository` / `ModelConnectionRepository` / `SshAccess` | 设置页管理的模型连接与部署服务器（替代 application.yml 硬编码） |
| | `ProjectEnvVar` / `ProjectEnvVarRepository` | 旧版 per-project 环境变量模型与仓储（保留兼容；新链路见 `env` 包） |
| `env` | `EnvironmentStore` | **环境子系统端口**：模板（templates/template/saveTemplate）、中间件资源（resources/saveResource/deleteResource/recordHealth）、模板变量（envVars/saveEnvVar/deleteEnvVar）、项目覆盖（overrides/saveOverride/deleteOverride）、有效视图（`effective(projectId,env)`）、Agent 绑定（saveBinding/binding/confirmBinding）、端口租约（lease/leases/saveLease） |
| | `EffectiveEnvironment` | 部署时真正注入的合并结果（record）：vars 掩码展示、values 明文仅执行面可见、resources、templateCount/overrideCount、network/defaultPort/healthPath |
| | `EnvTemplate` / `EnvVariable` / `EnvResource` / `PortLease` / `EnvBinding` | dev/test/prod 模板；变量（source=RESOURCE/TEMPLATE/OVERRIDE，敏感值掩码）；中间件资源（source=LOCAL_DOCKER/REMOTE_DOCKER/MANUAL/COMPOSE，含健康检查结果）；宿主端口租约；Agent 环境映射绑定（status=DRAFT/CONFIRMED/EXPIRED，TTL 24h） |
| | `EnvBindingPort` | 绑定端口：`draft`（生成草案）/ `requireForRun`（触发前校验，E-703） |
| | `StackAdapter` | 资源 → 默认变量映射（`DB_*`/`REDIS_*`/`RABBITMQ_*`/`MINIO_*`） |
| `tool` | `ToolLevel` | 工具能力分级（规约 §1.2）：L0 只读 / L1 计划 / L2 隔离执行 / L3 生产 |

### 4.2 agent-infra —— 端口适配实现（18 类，仅依赖 domain）

| 包 | 关键类 | 职责 |
|---|---|---|
| `recipe` | `RecipeActionResolver` | **配方解析（方案 D 核心）**：按优先级 `ci/actions.yml → Taskfile.yml → Makefile → 内置默认` 解析出 `ActionSpec`；`resolveCommit` 读 `.git/HEAD` 补审计链 |
| `git` | `GitWorktreeManager` | git 托管底层：**bare 对象库 + N 个 worktree**（v2 接入设计） |
| | `ProjectIntakeService` | **项目接入服务（v2）**：本地收编 / 远程 clone + 配方自检 + 状态机 |
| | `GitConfig` | git 相关装配 |
| `persistence` | `InMemoryProjectRepository` | 预置项目源（application.yml `agent.projects`，只读） |
| | `IntakeProjectStore` | 接入项目的 **SQLite 存储**（重启不丢） |
| | `CompositeProjectRepository` | 两源合并（配置预置 ∪ 接入）对外唯一视图 |
| | `ProjectStoreConfig` / `ProjectsProperties` / `DefaultRepoLocator` | 仓储装配 / yml 绑定 / 直接模式仓库定位器 |
| `pipeline` | `FilePipelineDefStore` | 平台侧流水线定义落盘：`<workspaceRoot>/<projectId>/ci/{pipeline.yml,actions.yml}`（**不写用户仓库**） |
| | `FileRecipeStore` | 环境级固化脚本存储：`<workspace>/<projectId>/recipes/<env>/vN/{pipeline.yml,actions.yml,meta.yml}`；`invalidate` 只删 `current` 指针（历史版本保留，可人工 activate 回退） |
| `env` | `SqliteEnvironmentStore` | `EnvironmentStore` 端口的 SQLite 实现（库文件 `<workspace>/_platform/projects.db`）：表 `env_templates`/`env_resources`/`env_vars`/`project_env_overrides`/`env_bindings`/`deploy_port_leases`；启动 seed dev(host)/test(docker)/prod(server) 三套模板；旧表 `project_env_vars` 数据迁移进 `project_env_overrides`（不删旧表）；变量值 AES-GCM 加密、敏感项只回掩码 `****abcd`；env 名限 dev/test/prod |
| `scaffold` | `RecipeScaffolder` | **流水线脚手架**：识别工程根（仓库根 → `backend/` → 一层子目录，兼容 RuoYi 式"仓库根+工程目录"布局与 Java 目录内 UI 子目录）；Dockerfile 内已编译（RUN 含 mvn/gradle/npm）则不重复生成构建 stage；按 Dockerfile EXPOSE/HEALTHCHECK 推断端口与健康路径；扫描 `.env.example`/`docker/.env.example` 的 KEY 名（只读名不读值）写入 `deploy.env-required`；生成平台侧 `ci/` 脚本 |
| `security` | `SecretBox` | 平台凭据加解密（**AES-256-GCM**）：Gitee token / SSH 密码 / 模型 API Key / 环境变量值共用 |
| `settings` | `SqliteModelConnectionStore` / `SqliteDeployServerStore` | 模型连接/服务器配置 SQLite 存储，密钥加密落库、接口只回掩码 |
| | `SqliteProjectEnvVarStore` | 旧表 `project_env_vars` 上的 `ProjectEnvVarRepository` 实现（兼容旧链路） |

### 4.3 agent-governance —— 变更确认守门（4 类，仅依赖 domain）

| 类 | 职责 |
|---|---|
| `ConfirmTokenService` | 确认令牌服务（PRD §4.10 四道守卫中的第 2、3 道）：`issue` 绑定 `userId+changeId+planHash`、5 分钟 TTL；`consume` 六重校验（存在/未用/未过期/changeId 一致/planHash 一致/标记已用） |
| `ConfirmToken` | 一次性确认令牌（record） |
| `ChangeRegistry` | 变更单存储（内存实现，`ChangeStore` 端口） |
| `ActionGate` | 历史空壳，已废弃（勿引用） |

### 4.4 agent-executor —— 执行面（18 类，仅依赖 domain，无 LLM）

**动作执行器**：

| 类 | 职责 |
|---|---|
| `AbstractActionRunner` | 执行骨架：超时预算、成功/失败判定、产物 digest、日志尾部；`validateArgv` 拒绝 shell 元字符依赖（argv 直 exec，不经 shell） |
| `ProcessActionRunner` | 宿主进程模式（`isolated()=false`） |
| `DockerActionRunner` | 容器内执行模式（`isolated()=true`，生产用） |
| `DockerSandbox` | 把业务命令包成 `docker run`：`--entrypoint=` 清空入口、`--user 1000:1000`、`--cap-drop ALL --security-opt no-new-privileges`、`--memory/--cpus/--pids-limit`、网络三档（bridge/build-net/none）、工作区挂 `/ws`；`withImage` 支持 stage 级专属工具链镜像 |
| `ProcessExecutor` | 单条命令执行内核（宿主/容器共用）：`redact` 日志脱敏（Bearer/token/secret/password/api_key/64位hex/PEM 私钥块；64 位 hex 规则用负向后顾保留 `sha256:`/`sha512:` 镜像指纹）、`tail` 输出截断 |
| `SandboxConfig` | 执行模式装配（auto/docker/process 探测） |

**流水线引擎**：

| 类 | 职责 |
|---|---|
| `BuiltInPipelineEngine` | `PipelineEngine` 端口的真实实现：trigger→checkout 指定 sha→加载定义→逐 stage（分解为 `ActionSpec` 复用同一套 Runner 与门禁）→失败跑 on-failure→成功记 `lastGood` 镜像 tag；`verify` 自动裁掉 deploy/health stage；`preview` 返回同执行期的阶段裁剪；**只有含 deploy/stack_deploy 的成功运行**才更新 `lastGood` 并固化 recipe（纯 verify 不再固化脚本）；启动 `restoreHistory` 恢复历史与回滚锚点，非终态运行标记 CANCELLED；deploy stage 走环境注入链路（有效变量→E-701 中间件预检→E-509 必填预检→端口租约→env-snapshot 产物，见 §5.11） |
| `PipelineDefinition` / `PipelineDefinitionResolver` | 流水线定义（来自仓库 `ci/pipeline.yml`，随代码版本化；stage 只引用动作名不写命令）；`DeploySpec` 新增 `envRequired`（解析 `deploy.env-required`，支持字符串列表或 `- name: X`，非法名报错）；定义解析：决定这次用哪份定义（仓库版/平台侧固化 recipe 版） |
| `PipelineConfig` | 装配：把执行器组合成真正的流水线（Spring 组合根） |
| `TriggerScheduler` | 触发调度：**同项目串行**（防争抢工作区/缓存），不同项目并行（4 线程池） |
| `RunHistoryStore` | 构建历史持久化：每次运行一个 YAML（`_history/<runId>.yml`），重启可读回；落盘失败不阻断构建 |

**部署器（方案 C：平台拥有部署语义）**：

| 类 | 职责 |
|---|---|
| `Deployers` | 部署器注册表：按 `DeployTarget` 选择实现，未命中 fail-closed（E-505） |
| `HostDeployer` | 本机直接运行部署（HOST）：`pb.environment()` 注入 `request.env()` |
| `DockerDeployer` | 本机容器替换式发布（DOCKER）：`docker run` 追加 `--network` 与按 TreeMap 排序的 `-e KEY=VALUE`；首次部署无旧容器时友好跳过；失败摘要先 `sanitize`（抹掉本次注入的 env 值）再 redact |
| `ServerDeployer` | 远程服务器部署（SERVER）：镜像经 ssh/scp 推到远端 docker 运行；远端端口预检（`ss -ltn` + `docker ps` 只读探测，冲突从期望端口续找 200 个，耗尽 E-704，不杀未知进程）；`-e` 值单引号转义注入、`--network` 透传；失败摘要 sanitize |
| `JschSsh` / `SshExec` | 纯 Java SSH 实现（JSch）：连通性探测、远程命令、文件上传；`SshExec` 实现 domain 的 `SshAccess` 端口（探测 docker 版本用 `{{.Server.Version}}` 模板并兜底 `docker info`） |

### 4.5 agent-tools —— 工具层（10 类）

| 类 | 职责 |
|---|---|
| `ActionService` | **动作编排服务**：工具与 HTTP 共用同一套门禁（plan → `assertHostSafe` → bindParams → run → specHash），避免两条路径行为不一致；`specHash` 把 `action+source.ref+commit+commands` 一起哈希——配方提交变了令牌即失效；`assertHostSafe`：非隔离执行遇 MAKEFILE/TASKFILE 来源或 L3 动作直接拒绝（fail-closed） |
| `ActionQueryTools` | 只读工具：`list_actions`、`plan_action` |
| `ActionCommandTools` | 写工具（L2 仅测试环境）：`run_action` |
| `CiQueryTools` | 流水线只读（L0）：`ci_list_pipelines`、`ci_status`、`ci_history` |
| `CiCommandTools` | 流水线写（L2）：`ci_run`、`ci_cancel` |
| `ChangeTools` | `request_confirmation`（L1）：只产出 changeId+planHash，**模型不能自己确认** |
| `DeployTools` | 部署 agent 专用：`deploy_env_status`、`deploy_run`、`deploy_save_recipe`——「把某个环境跑通并固化」；`deploy_run` 必须携带人工确认过的 `binding_id`（由 `env_mapping_draft` 生成，经 `EnvBindingPort.requireForRun` 校验，E-703） |
| `EnvTools` | 环境工具：`env_summary`（L0 只读：变量数/网络/端口/中间件健康，**不返回值**）、`env_mapping_draft`（L2 写：生成 DRAFT 绑定草案，prod 拒绝，须人工确认后才能 `deploy_run`） |
| `ProjectTools` | 项目只读（L0）：`project_info`、`list_envs` |
| `ToolCatalog` | 工具装配：注册进 AgentScope `Toolkit`（读组/写组）；`EnvTools` 同时注册进默认与部署两套 Toolkit |

### 4.6 agent-runtime —— Agent 装配与对话（7 类）

| 类 | 职责 |
|---|---|
| `AgentFactory` | 按 `ProjectContext` 构造 ReActAgent（注入 Toolkit、系统提示、RuntimeContext 传递项目上下文——模型无法伪造 projectId） |
| `ChatService` | 对话服务：一次用户输入 → AgentScope 事件流 |
| `ModelConfig` | 模型装配（OpenAI 兼容端点，默认 DashScope） |
| `AgentProperties` | `agent.model.*` 配置绑定（max-iters=8、temperature=0.2） |
| `DynamicModelProvider` | **动态模型提供者**：设置页保存后无需重启即可切换模型 |
| `SettingsModelRegistry` | `ModelRegistry` 实现：设置页（SQLite）配置优先，application.yml 兜底 |
| `AgentPipelineDefAuthor` | 流水线定义的 agent 兜底：规则脚手架识别不出技术栈时，请模型按同一契约给候选定义（仍走人工预览落盘） |

### 4.7 agent-gateway —— HTTP 边界与安全（32 类，Boot 入口）

| 包 | 关键类 | 职责 |
|---|---|---|
| 根 | `AgentApplication` | Spring Boot 入口（`agent-gateway-0.1.0-SNAPSHOT-exec.jar`，classifier=exec 以保留普通 jar 供 ArchUnit 读取） |
| `web` | `ChatController` | `POST /api/v1/chat`（SSE）；虚拟线程承载会话，身份来自 Sa-Token 会话 |
| | `AgentEventMapper` | AgentScope 事件 → 前端 DTO（骨架期用反射映射） |
| | `ActionController` | 动作 HTTP 接口（不经模型也能用）：list / plan / run / request；注释明确"不再接受请求头伪造身份" |
| | `ChangeController` | **整个平台唯一签发确认令牌的地方**：`GET /{id}`（确认卡片数据）、`POST /{id}/confirm`、`POST /{id}/apply` |
| | `PipelineController` | 流水线运行查询与触发：pipelines/preview（触发前阶段预览）/run/status/log（offset 增量续拉）/cancel/history/deploy targets；run 可带 `bindingId`（Agent 代触发时校验绑定），trigger params 记录 `actor`（`ui:<user>` 或 `agent`）与 `confirmedBy` |
| | `DeployController` | 环境视角部署：envs / confirm（上服务器一次人工确认）/ replay / recipes / activate / ports（端口租约查询，排障与审计） |
| | `ProjectIntakeController` | 项目接入与 git 管理（v2）：intake validate/local/remote/status、git status/diff/sync/remotes/commits、worktrees、列表/删除 |
| | `ProjectScaffoldController` | 脚手架：GET 预览 / POST 落盘流水线定义；覆盖保存（overwrite）时联动 `RecipeStore.invalidate` 失效 dev/test/prod 当前固化脚本（响应含 `resetRecipes`） |
| | `SettingsController` | 设置：服务器 CRUD+测试+默认、模型连接 CRUD+测试+默认+options；默认服务器可直接删除（自动提升下一台启用服务器为默认）；编辑态"测试"沿用库中密钥并把结果写回该行 |
| | `EnvironmentController` | **环境模板/资源/变量/覆盖/绑定**（`/settings/envs*`、`/settings/projects/{id}/overrides`、`/effective`、`/env-binding/*`，权限 settings:read/write）：模板 CRUD、中间件发现（本机/远程 Docker 只读探测，候选暂存内存、密码不出后端）与导入、手工登记、健康检查、模板变量、项目覆盖、有效视图、绑定草案/确认/查询 |
| | `ProjectEnvController` | `/settings/projects/{id}/envs`：项目环境变量读写（已统一写 `project_env_overrides`，经 `EnvironmentStore`；value 留空=保持原值） |
| | `GiteeBrowserController` | Gitee OAuth（start/callback，state 防 CSRF）+ PAT 绑定（AES-GCM 加密落库，接口永不回明文）+ 仓库列表；上游鉴权失败回 400/502（401 专指本平台会话失效，避免前端误清登录态） |
| | `GiteeWebhookController` | push 事件自动触发 verify 流水线；Token/HMAC-SHA256 校验（未配置密钥即拒）；同 commit 幂等去重 |
| | `FsBrowserController` | 本地目录浏览（供前端选文件夹）：ls / probe / roots |
| | `ProjectContextResolver` | 请求参数 projectId → `ProjectContext`（服务端绑定） |
| | `GlobalExceptionHandler` | 统一错误响应 `{code, message, traceId}` |
| `auth` | `AuthController` | 登录/登出/me（唯一签发会话令牌处） |
| | `SaTokenConfig` | Sa-Token 装配与路由保护 |
| | `CurrentUser` / `Role` / `UserStore` / `SaPermissionSource` | 当前身份出口 / 角色最小集合 / 用户仓库（内存版）/ 权限码数据源 |
| `security` | `PasswordVerifier` / `SessionPasswordVerifier` | 高危变更二级密码校验（早期 `FailClosedPasswordVerifier` 已被会话版替代） |
| `deploy` | `DeployGate` | **服务器操作双重前置**（两条入口共用，防"换个 URL 绕过"）：测试环境成功案例 + 一次人工确认 |
| `gitee` | `GiteeAccountStore` | Gitee 账号绑定存储（token AES-256-GCM 加密落 SQLite） |
| `settings` | `ModelProbe` | 模型连通性探测：调 `/chat/completions`（max_tokens=1） |
| | `EnvBindingService` | `EnvBindingPort` 实现：draft 扫描仓库 `.env.example`/`docker/.env.example` 的 KEY（只读 key 不读值）产出映射草案（keys/missing/warnings E-701/effectiveHash）；confirm TTL 24h；`requireForRun` 校验项目/环境/已确认/未过期/变量未变更（E-703） |
| | `EnvironmentDiscoveryService` | 中间件只读发现：本机 `docker ps/inspect` 或远程 SSH `docker inspect`，识别 mysql/redis/rabbitmq/minio/postgres/mongo；无共享网络时 host=127.0.0.1 或服务器 IP + 宿主映射端口 |
| `config` | `Jackson2Config` | **显式定义 Jackson 2 ObjectMapper**——Boot 4 默认 Jackson 3，但 AgentScope 事件按 Jackson 2 注解序列化（启动期踩坑修复） |

### 4.8 spec-checks —— 规约自动化检查（仅 src/test，4 个测试类）

`mvn verify` 执行，任一违规直接构建失败——架构不是文档约定，而是编译期门禁。详见 §12。

---

## 5. 核心机制

### 5.1 动作契约（模型只选动作，不写命令）
1. 模型调用 `list_actions` 看到词汇表（`ActionId` 6 个动作）；
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
- `preview(projectId, pipelineId, env)` 与真实执行共用同一裁剪逻辑：verify 裁掉 deploy/health/rollback/stack_deploy，无 Dockerfile 再裁 image_package——"运行前看到什么，跑的就是什么"；
- **只有含 deploy/stack_deploy 的成功运行**才更新 `lastGood` 并固化 recipe：纯 verify 成功不再把脚本固化成"发布证据"；
- 重新生成脚手架定义（覆盖保存）会 `RecipeStore.invalidate` 当前环境脚本，历史版本仍可 activate 回退。

### 5.6 项目接入（v2：bare + worktree）
`ProjectIntakeService`：本地目录收编（DIRECT 不复制仓库）或远程 clone（Gitee token 优先用户绑定、平台兜底）→ `GitWorktreeManager` 建 bare 对象库 + 按需 worktree → 配方自检 → `RecipeScaffolder` 按仓库结构**在平台侧**生成 `ci/` 定义（不写用户仓库；规则识别失败时 `AgentPipelineDefAuthor` 让模型给候选）。接入项目落 SQLite（`IntakeProjectStore`），与 yml 预置项目经 `CompositeProjectRepository` 合并。

### 5.7 环境固化与发布（方案 C）
Agent 用 `deploy_run` 在测试环境把流程跑通 → `deploy_save_recipe` 固化为 `recipes/<env>/vN/`（可重放/回滚/activate 为当前定义）→ 上服务器需 `DeployGate` 双重前置：**该环境已有测试机成功案例（EnvironmentPromotion）+ 一次人工确认**（E-601/E-602），由 `ServerDeployer` 经 SSH 推送镜像并 docker 运行（远端端口预检，冲突续找，耗尽 E-704）。

**Agent 触发的第三道前置（2026-11 新增）**：`deploy_run` 必须携带 `binding_id`——先 `env_mapping_draft` 生成映射草案，再由人在设置页确认（TTL 24h）；未确认/过期/变量已变更一律 E-703 拒绝（见 §5.11）。

### 5.8 模型配置热切换
`SettingsController` 管理 `ModelConnection`（API Key AES-GCM 加密落 SQLite，只回掩码）→ `SettingsModelRegistry`（设置优先、yml 兜底）→ `DynamicModelProvider` 使切换**无需重启**；`ModelProbe` 提供"测试连接"。

### 5.9 认证与授权
Sa-Token（`Authorization: Bearer <token>`，30 天会话）：`AuthController` 登录签发 → 路由拦截保护 `/api/v1/**`（登录页除外）→ `SaPermissionSource` 按 `Role` 给权限码 → 各 Controller 经 `CurrentUser` 取身份。已替代早期 `X-User-Id` 请求头方案。

### 5.10 日志脱敏
`ProcessExecutor.redact` 过滤 Bearer、赋值型 token/secret/password/api_key、64 位 hex、PEM 私钥块（64 位 hex 规则用负向后顾保留 `sha256:` 镜像指纹）；`BuiltInPipelineEngine` 日志尾部与 `RunState.snapshot` 均走脱敏——凭证不进证据链。部署链路额外两层：`DockerDeployer.sanitize` 在失败摘要中按本次注入的 env 值全局抹除（`KEY=***`，值长度≥6）；`ServerDeployer` 远端 `-e` 参数单引号转义，值含空格/引号不被 shell 拆开。

### 5.11 环境模板、项目覆盖与部署注入（env 子系统，2026-11 新增）
决策文档：`docs/环境变量与环境模板设计-v2.md`（v1 仅存档）。核心思想：**变量按环境配一次、项目只写差异、部署只注入合并结果、Agent 只能"建议映射"不能"擅自使用"**。

1. **三层数据**（`EnvironmentStore` 端口，SQLite 实现 `SqliteEnvironmentStore`）：
   - 环境模板（dev/test/prod）：网络、默认部署目标/端口、健康路径、模板变量、中间件资源；
   - 中间件资源：来源=本机 Docker 发现 / 远程 SSH 发现 / 手工登记 / Compose；平台**只读发现 + 健康检测，不启停中间件**；`StackAdapter` 按资源类型生成默认变量映射（`DB_*`/`REDIS_*`/`RABBITMQ_*`/`MINIO_*`）；
   - 项目覆盖：同名 key 覆盖模板值，项目专属 key 标"仅本项目"。
2. **有效视图**：`effective(projectId, env)` 合并三层；对外（设置页/接口）变量一律掩码，明文只到执行面；所有值 AES-GCM 加密落库。
3. **部署注入链路**（`BuiltInPipelineEngine` deploy stage）：
   `effective` 取变量 → **E-701 中间件预检**（资源已登记且最近检查"未运行"→ 拒绝启动容器；从未检查仅告警）→ **E-509 必填预检**（`deploy.env-required` 与有效变量求差，缺失不启动容器）→ **端口租约**（沿用既有租约；冲突从期望端口 +1 起 200 个内找本机空闲端口；SERVER 不做本机探测）→ `DeployRequest` 带 env+network 注入 → 部署后若实际端口≠租约端口则回写（远端重分配场景）。
4. **env-snapshot 产物**：每次部署记 `ArtifactRef(kind="env-snapshot", ref="env:<test>", digest=对 key+sha256(value) 的排序摘要)`，meta 含变量数/模板数/覆盖数/敏感数/网络/租约端口/bindingId/confirmedBy/各 key 哈希——**不落任何明文值**，可审计"这次注入的是哪一版映射"。
5. **健康检查增强**：health stage URL 端口替换为租约端口；docker 目标每轮重试前 `docker inspect` 探测 exited/dead 即提前失败；失败产出容器日志摘要（优先 ERROR 行 ≤3 条，截 300 字符）并清理失败容器（E-401 摘要含退出码）。
6. **Agent 绑定闭环**：`env_mapping_draft`（扫描仓库 `.env.example` 的 KEY，与有效变量比对出 missing/告警，写 DRAFT，不含值）→ 人在「设置 → 项目环境」两步确认（CONFIRMED，TTL 24h）→ `deploy_run` 携带 `binding_id`，`EnvBindingService.requireForRun` 五重校验（存在/属于本项目该环境/已确认/未过期/变量摘要未变，否则 **E-703**）。prod 环境拒绝 Agent 草案触发。

---

## 6. HTTP API 一览

前缀 `/api/v1`；除 `/auth/login` 外均需 Bearer 会话。错误统一 `{code, message, traceId}`。

| Controller | 方法与路径 | 作用 |
|---|---|---|
| AuthController | POST `/auth/login` `/auth/logout` `/auth/me` | 登录/登出/当前身份 |
| ChatController | POST `/chat`（SSE） | 对话，流式 Agent 事件 |
| ActionController | GET `/actions`、GET `/actions/{action}/plan` | 看动作目录 / 看执行计划（命令原文） |
| | POST `/actions/{action}/run`、POST `/actions/{action}/request` | L2 直接执行 / L3 登记变更 |
| ChangeController | GET `/changes/{id}`、POST `/changes/{id}/confirm`、POST `/changes/{id}/apply` | 确认卡片 / 签发一次性令牌 / 带令牌执行 |
| PipelineController | GET `/ci/pipelines`、GET `/ci/pipelines/{id}/preview` | 流水线列表 / 运行前阶段预览（verify 裁部署、无 Dockerfile 裁镜像） |
| | POST `/ci/pipelines/{id}/run` | 触发（gitSha/env/部署目标；可带 `bindingId` 表示 Agent 代触发，须已人工确认） |
| | GET `/ci/runs/{runId}`、GET `/ci/runs/{runId}/log`、POST `/ci/runs/{runId}/cancel` | 状态 / 增量日志（offset）/ 取消 |
| | GET `/ci/history`、GET `/ci/deploy/targets` | 构建历史 / 可选部署目标（含不可用原因） |
| DeployController | GET `/deploy/{projectId}/envs`、GET `…/recipes`、POST `…/recipes/activate` | 环境跑通状态 / 固化脚本版本 / 激活 |
| | POST `/deploy/{projectId}/confirm`、POST `/deploy/{projectId}/replay` | 上服务器人工确认 / 重放固化脚本 |
| | GET `/deploy/ports?projectId&env` | 端口租约列表（实际宿主端口，排障/审计） |
| ProjectIntakeController | POST `/projects/intake/validate` `/intake/local` `/intake/remote`、GET `/intake/{id}/status` | 接入校验 / 本地收编 / 远程 clone / 状态机 |
| | GET `/projects`、DELETE `/projects/{id}` | 项目列表（预置∪接入）/ 移除 |
| | GET `/projects/{id}/git/{status,diff,remotes,commits}`、POST `…/git/sync` | git 查看与同步 |
| | GET `/projects/{id}/worktrees`、DELETE `…/worktrees/{name}` | worktree 管理 |
| ProjectScaffoldController | GET/POST `/projects/{id}/scaffold` | 脚手架预览 / 落盘 |
| SettingsController | GET/POST/PUT/DELETE `/settings/servers[/{id}]`、POST `…/default` `…/test` | 部署服务器 CRUD、设默认、连通测试（编辑态留空密钥沿用库值，结果写回该行） |
| | GET/POST/PUT/DELETE `/settings/models[/{id}]`、POST `…/default` `…/test`、GET `…/options` | 模型连接 CRUD、设默认、探测 |
| EnvironmentController | GET `/settings/envs[/{env}]`、PUT `/settings/envs/{env}` | 环境模板列表/详情/保存 |
| | POST `/settings/envs/{env}/discover`、`…/import`、POST/DELETE `/settings/envs/{env}/resources[/{id}]`、POST `…/resources/{id}/check` | 中间件发现（候选暂存内存，密码不出后端）/ 导入 / 登记删除 / 健康检查 |
| | GET/PUT `/settings/envs/{env}/vars`、DELETE `…/vars/{key}` | 模板变量（敏感值只回掩码） |
| | GET/PUT `/settings/projects/{projectId}/overrides?env`、DELETE `…/overrides/{key}` | 项目覆盖 |
| | GET `/settings/projects/{projectId}/effective` | 有效视图（部署真正注入的合并结果） |
| | POST `/settings/projects/{projectId}/env-binding/draft`、POST `…/env-binding/{bindingId}/confirm`、GET `…/env-binding/{bindingId}` | Agent 绑定草案 / 人工确认 / 查询 |
| ProjectEnvController | GET/PUT `/settings/projects/{projectId}/envs?env`、DELETE `…/envs/{key}` | 项目环境变量（统一写 project_env_overrides；value 空=保持原值） |
| GiteeBrowserController | GET `/gitee/oauth/start` `/oauth/callback`、POST/DELETE/GET `/gitee/bind`、GET `/bind/status`、GET `/repos` | OAuth 授权、PAT 绑定（永不回明文）、仓库浏览 |
| GiteeWebhookController | POST `/webhooks/gitee/push`、GET `/webhooks/gitee/history` | push 自动触发 verify（Token/HMAC + 幂等） |
| FsBrowserController | GET `/fs/ls` `/fs/probe` `/fs/roots` | 服务端目录浏览（受 shared-root 边界约束） |
| Actuator | GET `/actuator/health` | 健康检查（8090） |

> ⚠️ 前端 `web/src/api/project.ts` 调用了 `GET /api/v1/fs/read`，当前后端 `FsBrowserController` 未实现该端点（文件预览的已知缺口）。

---

## 7. 模型工具（@Tool）一览

AgentScope `Toolkit` 注册（`ToolCatalog` 装配，读/写分组）：

| 工具 | 级别 | 所在类 | 作用 |
|---|---|---|---|
| `project_info` / `list_envs` | L0 只读 | ProjectTools | 项目与环境信息 |
| `ci_list_pipelines` / `ci_status` / `ci_history` | L0 只读 | CiQueryTools | 流水线只读视图 |
| `list_actions` / `plan_action` | L0/L1 | ActionQueryTools | 动作目录 / 执行计划 |
| `run_action` | L2 | ActionCommandTools | 执行隔离动作（仅测试环境） |
| `ci_run` / `ci_cancel` | L2 | CiCommandTools | 流水线触发/取消 |
| `deploy_env_status` / `deploy_save_recipe` | L2 | DeployTools | 环境跑通与固化 |
| `deploy_run` | L2 | DeployTools | 触发运行（仅 dev/test；**必须带人工确认过的 `binding_id`**） |
| `env_summary` | L0 只读 | EnvTools | 环境摘要：变量数/网络/端口/中间件健康（**不返回变量值**） |
| `env_mapping_draft` | L2 | EnvTools | 生成环境映射草案（DRAFT，不含值；须人确认后 `deploy_run` 才能用） |
| `request_confirmation` | L1 | ChangeTools | 登记变更（**无 confirm 工具**，确认只能走 HTTP） |

---

## 8. 前端架构（platform/web）

Vue 3 + Vite + TypeScript strict + Element Plus；`npm run build` 走 vue-tsc 严格检查。

### 8.1 路由与页面（src/router.ts）

| 路由 | 视图 | 职责 |
|---|---|---|
| `/login` | `LoginView.vue` | 登录（公开页） |
| `/` → `/chat` | `ChatView.vue` | 对话（SSE 流式、工具调用卡片、审批卡片） |
| `/pipeline` | `PipelineView.vue` | 流水线工作台（动作面板、触发、历史、stage 时间线、日志） |
| `/projects` | `ProjectListView.vue` | 项目列表 |
| `/projects/intake` | `ProjectIntakeView.vue` | 项目接入向导（本地目录/Gitee 仓库选择） |
| `/projects/:id` | `ProjectDetailView.vue` | 项目详情（文件树 + 预览） |
| `/settings` | `SettingsView.vue` | 设置（服务器/模型/环境模板/项目环境/Gitee 仓库分区；支持 `?section=` 深链） |

路由守卫：无 token → `/login`；有 token 未加载身份 → 调 `/auth/me` 校验（失效清 token）；任意接口返回 401 经 `setUnauthorizedHandler` 统一收口（清身份 + 跳登录并带 redirect）。

### 8.2 分层

| 目录 | 内容 |
|---|---|
| `src/api/` | `http.ts`（统一 fetch + Bearer）、`auth.ts`、`chat.ts`、`sse.ts`（fetch+ReadableStream 解析 SSE，支持 POST 单流与 GET 续传流、`Last-Event-ID`/`afterSequence`、4xx 不重试）、`action.ts`、`change.ts`、`pipeline.ts`、`deploy.ts`、`project.ts`、`settings.ts` |
| `src/views/` | 7 个页面（见上表） |
| `src/components/` | `chat/`（ConversationTimeline、MessageItem、ToolCallCard、ApprovalRequestCard、MarkdownView）、`pipeline/`（ActionPanel、TriggerBar 运行卡（提交+阶段预览+运行）、StageTimeline、RunLog、HistoryList、EnvStateBand 环境胶囊切换条、EnvSummary 运行前环境摘要）、`project/`（ProjectTree、FilePreview、TabbedFiles、ResizableSplitter 等）、`settings/`（ModelPanel、ServerPanel、RepoPanel、EnvTemplatePanel 环境模板（模板/发现/登记/健康检查/模板变量）、ProjectEnvPanel 项目环境（覆盖/有效视图/端口租约/Agent 绑定两步确认））、`DirPickerDialog.vue`、`GiteeRepoPicker.vue` |
| `src/composables/` | `useAgentChat.ts`（对话事件消费）、`useRunTracker.ts`（运行轮询 + 日志 offset 增量拉取）、`useFollowScroll.ts` |
| `src/state/` | `agentEventReducer.ts`（SSE 事件 → 消息状态归约，含同名 `.test.ts` 单测） |
| `src/stores/` | `app.ts`（登录身份等全局状态；无 Pinia，轻量 store 模块） |
| `src/layouts/` | `AppLayout.vue` + `AppAside`/`PageHeader`（外壳） |
| `src/utils/` | `markdown.ts`、`pipelineLabels.ts`、`projectId.ts`、`status.ts` |
| `src/types.ts` | 与后端 DTO 对应的核心类型（ActionView、RunView、StageResult、ChangeView、身份等） |

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
| `agent.projects.items` | 预置 RY（RuoYi-Vue-Plus，dev/test/prod 三环境） | 配置预置项目源 |
| `agent.intake.shared-root` | 空（全盘） | 目录浏览边界 |
| `agent.intake.gitee-token` | 空 | 平台兜底 Gitee token（私有库 clone） |
| `agent.gitee.client-id/secret/redirect-uri` | 空 | Gitee OAuth 应用 |
| `agent.sandbox.mode/image/network/memory/cpus/pids-limit/user` | auto / `maven:3.9-eclipse-temurin-21` / bridge / 2g / 2 / 512 / 1000:1000 | 执行沙箱 |
| `agent.model.base-url/api-key/model/max-iters/temperature` | DashScope 兼容端点 / 空 / qwen-plus / 8 / 0.2 | 模型兜底（设置页优先） |

### 10.2 环境变量名（值放 `platform/.env`，已 gitignore，绝不入库）

| 变量 | 用途 |
|---|---|
| `AGENT_MODEL_API_KEY` / `AGENT_MODEL_BASE_URL` / `AGENT_MODEL_NAME` | 模型接入（OpenAI 兼容） |
| `AGENT_WORKSPACE_ROOT` / `AGENT_SANDBOX_MODE` / `AGENT_SANDBOX_NETWORK` / `AGENT_SANDBOX_IMAGE` / `AGENT_PIPELINE_IMAGE_PREFIX` | 工作区与沙箱覆盖 |
| `AGENT_INTAKE_SHARED_ROOT` / `AGENT_GITEE_TOKEN` | 接入边界与兜底 token |
| `GITEE_CLIENT_ID` / `GITEE_CLIENT_SECRET` / `GITEE_REDIRECT_URI` | Gitee OAuth |
| `RY_REPO_URL` / `RY_DEV_URL` / `RY_TEST_URL` / `RY_PROD_URL` / `RY_COMPOSE_DIR` | 预置项目 RY 覆盖 |

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
| `ForbiddenPatternScanTest` | R01 禁 Runtime.exec、R02 禁 shell 解释器、R03 禁 MyBatis `${}`、R04 禁 new Thread、R05 禁字段注入、R06 金额禁 double、R07 日志禁打印凭证、R08 禁 SELECT *、R09 禁 java.util.Date、R10 禁空 catch、R11 禁 System.out、R12 禁动态脚本、R13 软删用 deleted_at。误报豁免：`// spec-ignore: R06 <原因>` |
| `ConfigCredentialScanTest` | 决策面（gateway/runtime/tools/governance）配置不得含写凭证键 |
| `MigrationGuardTest` | 已发布迁移文件不得修改（基线 `spec-checks/migration-baseline.txt`） |

### 12.2 单元测试分布

| 模块 | 代表测试 |
|---|---|
| agent-executor | `ProcessActionRunnerTest`、`DockerActionRunnerTest`、`DockerSandboxTest`、`ProcessExecutorRedactTest`（脱敏）、`PipelineDefinition*Test`、`DeployersTest`、`DeployRecipeFlowTest`、`JschSshTest`、`HostDeployerTest`、`DockerDeployerTest`、`DeployEnvPrecheckTest`（E-509/E-701 预检）、`DockerDeployerEnvTest`（env/network 注入与 sanitize） |
| agent-governance | `ConfirmTokenServiceTest`（六重校验） |
| agent-infra | `RecipeActionResolverTest`、`GitWorktreeManagerTest`、`IntakeScaffoldTest`、`RecipeCheckTest`、`IntakeProjectStoreTest`、`FileRecipeStoreTest`、`SecretBoxTest`、`SqliteSettingsStoreTest`、`SqliteProjectEnvVarStoreTest`、`RecipeScaffolderTest`（工程根识别/env-required） |
| agent-runtime | `DynamicModelProviderTest` |
| agent-gateway | `PipelineAcceptanceTest`、`DualStackPipelineTest`（Java+Vue 双栈）、`IntakeE2ETest`、`ActionUsabilityTest`、`ToolChainTest`、`DeployStageTest`、`DotEnvLoaderTest`、`GiteeWebhookControllerTest`、`SettingsControllerTest`、`ModelProbeTest`、`ToolEvalBaselineTest`/`LiveModelToolEvalTest`（需真实模型 Key，缺 Key 自动跳过） |
| web | `state/agentEventReducer.test.ts` + `scripts/*.mjs` 无头浏览器回归 |

---

## 13. 已知边界与留白

1. **会话与消息未持久化**——对话历史仍在内存；多租户未做。
2. **`GET /api/v1/fs/read` 未实现**——前端文件预览调用该端点，后端 `FsBrowserController` 只有 ls/probe/roots。
3. **用户体系为内存版**（`UserStore`），未接数据库；高危二级密码走 `SessionPasswordVerifier`。
4. **事件映射用反射**（`AgentEventMapper`）——正式版应把 32 类 AgentScope 事件映射成稳定枚举。
5. **`changeId` 由前端正则从文本提取**——应由后端发专用 SSE `change` 事件。
6. **构建历史为 YAML 文件**（`RunHistoryStore`）——换 MySQL 成本低（接口已留）。
7. **`ActionGate` 为废弃空壳**——门禁逻辑已并入 `ActionService`/`DeployGate`。
8. **窄控制器 8 步滚动发布未做**——当前 deploy 由仓库配方/平台部署器提供。
9. **沙箱不注入私服凭据**——需要时挂只读 `settings.xml`。
10. **中间件发现候选暂存进程内存**（`EnvironmentController.candidates`）——重启后未导入的候选丢失，需重新发现。

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
| 环境前端面板 | `web/src/components/settings/EnvTemplatePanel.vue`、`web/src/components/settings/ProjectEnvPanel.vue` |
| 项目接入 | `agent-infra/src/main/java/top/rongx/agent/infra/git/ProjectIntakeService.java` |
| Agent 工厂 | `agent-runtime/src/main/java/top/rongx/agent/runtime/AgentFactory.java` |
| 分层强制 | `spec-checks/src/test/java/top/rongx/agent/arch/ArchitectureRulesTest.java` |
| 后端配置 | `agent-gateway/src/main/resources/application.yml` |
| 前端路由 | `web/src/router.ts` |
