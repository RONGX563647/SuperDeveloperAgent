# 流水线体验重构 + 开发环境本机部署 规格说明

## 1. 背景

当前流水线页面存在四类问题，用户已明确反馈：

1. **构建历史无法按环境区分**：列表混排 dev / test / prod 运行记录，只能靠小标签看。
2. **单步动作是多余功能**：「单步动作（高级，调试用）」面板与该页面定位不符。
3. **高级选项多余**：`高级选项` 里的「流水线定义」「部署目标」下拉让使用者每次都要做技术决策。
4. **部署目标不直观**：用户希望只有两种选择——**打包成镜像** 或 **直接部署**，用一个可切换按钮表达。
5. **开发环境语义错误**：开发环境本应是**前后端在本机运行**，现在却打成镜像跑在 Docker 里。
6. **无法交还 IDE**：开发环境需要能一键停掉平台托管的前后端，改由 IDE 启动。

## 2. 已确认决策

| 议题 | 决策 |
| --- | --- |
| 开发环境「直接部署」前端形态 | **平台托管前端 dist**：平台本机构建 `npm run build:prod`，用内置 Node 静态服务器托管 dist，并把 `/prod-api`、`/dev-api` 反代到本机后端 |
| 本轮范围 | **UI 重构 + 开发环境本机部署一起做** |
| 模式切换 | 「打包成镜像」= `deployTarget=docker`；「直接部署」= `deployTarget=host`；默认 dev→host，test/prod→docker |
| 停止本机服务 | 新增 `POST /api/v1/ci/deploy/host/stop`，停掉平台托管的 dev 前后端进程 |
| 开发环境代码来源 | 直接使用项目本地工作区（`repoLocator.repoDir`），**不建 git worktree、不检出提交**，符合「开发环境部署当前代码」语义 |

## 3. 需求

### R1 构建历史按环境区分
- 历史列表顶部新增环境筛选：`全部 / 开发 / 测试 / 生产`（单选，默认全部）。
- 每条记录始终显示环境标签（已有则保持），筛选后仅显示该环境记录。
- 环境取值：`dev`→开发、`test`→测试、`prod`→生产、其它→原样。

### R2 删除「单步动作」
- 移除流水线页面的：单步动作面板、动作执行结果区块、L3 变更二次确认对话框，以及支撑它们的状态、函数、API 调用。
- `ActionPanel.vue` 无其它引用，一并删除。

### R3 删除「高级选项」
- `TriggerBar` 移除 `高级选项` 折叠区（流水线定义下拉、部署目标下拉）。
- 流水线定义由环境自动解析（沿用现有 `PipelineDefinitionResolver`）；部署目标由 R4 的模式切换决定。

### R4 模式切换按钮（打包成镜像 / 直接部署）
- 运行卡片新增二选一分段按钮：`打包成镜像` / `直接部署`。
- 选择结果通过现有触发参数 `deployTarget` 传给后端：`docker` / `host`。
- 默认值：`dev` → `host`；`test` / `prod` → `docker`。
- 主按钮文案随模式变化：
  - dev + 直接部署 → `本机部署`
  - docker → `打包并部署`
  - 环境为 test/prod 时仍显示 `发布到测试环境` / `发布到生产环境`。
- 「检查构建」按钮保留不动。

### R5 开发环境本机部署（后端 + 前端 dist）
触发条件：`env=dev` 且 `deployTarget != docker`（含未显式指定）。

执行位置：项目本地仓库根 `repoLocator.repoDir(projectId)`（不建 worktree）。

阶段：
| 阶段 id | 内容 | 失败码 |
| --- | --- | --- |
| `workspace` | 合成成功阶段，日志 `[dev] 本机模式：直接使用工作区 <path>（不检出提交）` | — |
| `build` | `mvn -q -DskipTests package`（工作目录 = 检测到的后端根） | E-601 |
| `frontend` | `npm run build:prod`（工作目录 = 检测到的前端根；`NODE_OPTIONS=--openssl-legacy-provider` 兼容 webpack4 + Node 新版本）；未检测到前端根 → 阶段成功但日志说明「无前端模块，跳过」 | E-602 |
| `deploy` | 启动本机后端 jar + 前端静态服务器（见 R6） | E-601/E-603 |
| `health` | `GET http://127.0.0.1:<frontendPort>/` 期望 2xx；无前端时改为后端 `GET /` | E-604 |

目录检测（仅这两种规则，避免过度设计）：
- 后端根：仓库根有 `pom.xml` → 仓库根；否则一级子目录中存在 `RuoYi-Vue/ruoyi-admin/pom.xml` 形式（子目录下含 `ruoyi-admin/pom.xml` 或 `<sub>/src/main/java`）→ 该子目录。
- 前端根：仓库根有 `package.json` → 仓库根；否则一级子目录中 `*/ui`、`*/web`、`*/ruoyi-ui` 之一含 `package.json` → 该子目录。

jar 定位：后端根下 `**/target/*.jar`，优先非 `*-sources.jar`，多个时取修改时间最新。
dist 定位：前端根 `dist/index.html`；构建前若 `dist/index.html` 已存在且新于 `src` 目录则不重复构建（日志 `[dev] 前端产物较新，跳过构建`）。

端口：
- 后端：`allocateHostPort(state, HOST, 8688, 8688)`
- 前端：`allocateHostPort(state, HOST, 8687, 8687)`（最后分配，保证 `state.deployHostPort` 为对外地址）

### R6 本机进程托管与停止
新增执行器 `LocalDevHostDeployer`（`agent-executor`），状态目录 `<repo>/.agent/deploy/<projectId>-<env>/`：

| 文件 | 说明 |
| --- | --- |
| `backend.pid` / `frontend.pid` | 子进程 pid |
| `backend.log` / `frontend.log` | 进程 stdout+stderr |
| `static-server.mjs` | 平台生成的静态服务器脚本 |

- 启动顺序：先停旧（读 pid → `taskkill /T /F`，Windows；其它系统 `kill -9`）→ 起后端 → 轮询后端就绪（≤90s）→ 起前端静态服务器 → 轮询前端 2xx（≤30s）。
- 后端命令：`java -jar <jar> --server.port=<backendPort>`，环境变量注入 `effective env`（与 Docker 部署同一来源）。
- 前端命令：`node static-server.mjs <distDir> <frontendPort> <backendPort>`。
- `static-server.mjs` 行为：静态文件 + SPA fallback（未命中路径回 `index.html`）；`/prod-api/*`、`/dev-api/*` 反代到 `http://127.0.0.1:<backendPort>/*`（去掉前缀）；其余 404。
- 停止 API：`POST /api/v1/ci/deploy/host/stop?projectId=<id>&env=dev` → `{ "stopped": true|false, "message": "..." }`。
- 停止能力同时用于：部署前清旧、页面「停止本机服务」按钮。
- 失败时把 `backend.log` / `frontend.log` 尾部（各 ≤60 行）写进流水线日志，便于 Agent 自愈。

### R7 页面「本机服务」可见与可停
- 环境为 `dev` 且模式为「直接部署」时，运行卡显示一行提示：
  `本机运行：后端 127.0.0.1:<backendPort> · 前端 127.0.0.1:<frontendPort>（可在 IDE 启动前先停止）`
  端口取最近一次 run 的 deployment 信息；无运行记录时只显示模式说明。
- 同处提供 `停止本机服务` 按钮（危险色文字按钮），调用 R6 停止接口，成功后提示「已停止，可用 IDE 启动」。
- 运行结果区域展示可点击的部署地址。

## 4. 非目标

- 不改动 `ci/pipeline.yml`、`ci/actions.yml` 等仓库内定义文件。
- 不删除后端单步动作/动作计划接口（仅移除前端入口）。
- prod 环境策略（`server`）与 `E-703` 人工确认门禁保持不变。
- 不重写 `LocalProcessServiceRuntime`（设置页 dev 服务），仅补 RuoYi 目录识别，避免两套能力对同一仓库表现不一致。

## 5. 验收标准

1. 流水线页面不再出现「单步动作」「高级选项」；无 `ActionPanel` 挂载与相关请求。
2. 历史列表可按环境筛选（全部/开发/测试/生产），筛选结果正确。
3. 模式切换按钮在两态间切换，触发请求体 `deployTarget` 分别为 `host` / `docker`。
4. dev + 直接部署实跑成功：阶段为 workspace/build/frontend/deploy/health；`http://127.0.0.1:<frontendPort>/` 返回 2xx；`docker ps` 中该项目**没有新增** dev 应用容器。
5. 停止本机服务后，前后端端口均不再监听；随后可用 IDE/命令行重新启动。
6. test 环境 + 打包成镜像仍成功（`agent-deploy-ruoyiwnz-test` healthy），不受本轮改动影响。
7. 平台单测 `DeployersTest`、新增 `LocalDevHostDeployerTest` 通过；后端打包成功。

---

## 6. v2 修订：dev 固定本机运行，不再允许 Docker

### 6.1 现场证据（2026-11-10）

| 运行 | 环境 | 结果 | 说明 |
| --- | --- | --- | --- |
| `run-96d218c3` | dev | FAILED | 走了本机分支（`[dev] 本机模式…E:\CODE\developing\ruoyi-wnz`），`mvn package` 退出码 1：工作区有 `com.ruoyi.script` / `com.ruoyi.task` 缺失的编译错误（用户代码问题） |
| `run-3a49ba2f` | dev | SUCCEEDED | **走了 Docker**：`[checkout] worktree` → `[image] sha256:…` → `[deploy] docker → http://127.0.0.1:8689/` |

### 6.2 根因

1. `BuiltInPipelineEngine.isLocalDev()` 只在 `deployTargetOverride != docker` 时成立；请求带了 `docker` 就回落到打包镜像。
2. 前端对 dev 仍展示「打包成镜像 / 直接部署」切换，用户可选中 docker。
3. `PipelineView.doPrimary()` 在 `deployTarget != host` 且环境已有固化脚本时调用 `replayEnv()` → `POST /deploy/{id}/replay`，该接口**不传 `deployTarget`**，最终按 `ci/pipeline.yml` 的 `target`（docker）执行，并且日志里是 `definition=RECIPE`。这正是 `run-3a49ba2f` 的路径。
4. 历史遗留的 dev 容器 `agent-deploy-ruoyiwnz-dev` 仍占着 8689，与 dev 本机前端默认端口冲突，用户打开看到的仍是容器里的旧应用。

### 6.3 决策

| 议题 | 决策 |
| --- | --- |
| dev 部署目标 | **强制 host**。`env=dev` 时忽略任何 `deployTarget=docker`（含 replay / 历史重跑 / Agent 触发），并在日志显式说明忽略原因 |
| dev 是否还提供「打包成镜像」开关 | **不提供**。dev 只显示本机运行说明；`test` 保留「打包成镜像 / 直接部署」切换；`prod` 固定远程服务器 |
| dev 重放路径 | 已有固化脚本时仍可重放，但 `isLocalDev` 优先命中，**不会**走镜像/容器 |
| 端口冲突 | 本机启动前先清理同项目 dev 的遗留 Docker 容器（`agent-deploy-<project>-dev` 与旧命名 `agent-deploy-<project>`），释放端口 |
| 停止后交还 IDE | 「停止本机服务」在 dev 环境**始终可见**（不再依赖 deployTarget），停止后 PID 文件清除、端口释放，IDE 可直接启动 |
| 与设置页「本地服务」的关系 | 保持两套并存但语义一致：设置页用于单独启停（`npm run dev` + 后端进程，端口 18080+/15173+），流水线用于「构建后一键本机运行」（端口 8688/8689）。两者都停平台托管的进程，不动 IDE 进程 |

### 6.4 变更点（精确到文件与函数）

1. `platform/agent-executor/src/main/java/top/rongx/agent/executor/BuiltInPipelineEngine.java`
   - `isLocalDev(RunState)`：去掉 `!"docker".equalsIgnoreCase(state.deployTargetOverride)` 条件，改为
     `localDevHost != null && env=dev && !"verify".equals(pipelineId)`。
   - `executeLocalDev(RunState)`：启动前后端之前，调用 `dockerDeployer.stopDevContainers(repo, state)`（新增方法）清理遗留 dev 容器；日志写 `[dev] 已清理遗留 dev 容器：<name>`。
   - `trigger(...)` 里当 `env=dev` 且 `deployTarget=docker`：记录 `[dev] 开发环境固定本机运行，已忽略 docker 目标`（保留 `deployTargetOverride` 供日志/追溯，但不再影响分支）。
2. `platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerDeployer.java`
   - 新增 `public void stopDevContainers(Path dir, String projectId, String env, StringBuilder log)`：
     依次 `docker rm -f agent-deploy-<project>-<env>`、`docker rm -f agent-deploy-<project>`，`No such container` 视为成功。
3. `platform/web/src/views/PipelineView.vue`
   - `showModeSwitch`：`env !== 'dev' && env !== 'prod'`（只有 test 显示切换）。
   - `modeNote`（dev）：`开发环境固定在本机运行：后端 jar + 前端 dist，不打镜像`。
   - `primaryLabel`（dev）：`启动本机前后端`。
   - `canStopLocal`：`env === 'dev'`（不再判断 deployTarget）。
   - `doPrimary()`：dev 一律 `triggerSelected()`（显式传 `deployTarget=host`），不进入 `replayEnv()` 分支。
   - `localHint` 无运行记录时：`平台托管的本机前后端可随时停止，停止后用 IDE 启动`（保持）。
4. `platform/web/src/components/pipeline/TriggerBar.vue`
   - 无需改结构；确认 `showModeSwitch=false` 时不渲染「方式」区。

### 6.5 v2 验收标准

1. dev 触发（流水线页 / 历史重跑 / `deploy/replay`）均不产生 `[image]`、不出现 `docker →`；日志为 `[dev] 本机模式…`。
2. dev 页面上没有「打包成镜像」按钮；test 仍有该切换。
3. dev 页面「停止本机服务」始终可见；点击后 8688/8689 不再监听，PID 文件被删除。
4. 启动前若存在 `agent-deploy-<project>-dev` 容器，日志出现清理记录，`docker ps` 中不再有该容器。
5. 平台 `mvn -q -DskipTests package` 通过；`LocalDevHostDeployerTest`、`DeployersTest` 通过；前端 `npm run build` 通过。
6. 若目标仓库工作区本身编译失败，dev 运行以 `build` 阶段 FAILED（E-601）结束，日志尾部包含 Maven 报错，不回落 Docker。
