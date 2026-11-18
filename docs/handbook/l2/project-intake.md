---
type: handbook-l2
level: L2
module: agent-infra
title: 项目接入
summary: 把本地目录或远程仓库接入平台，完成 git 登记、配方自检与项目持久化
refs:
  - ../l3/infra-project-intake.md
  - ../l3/infra-git-worktree.md
---

# 项目接入

## 职责边界

接入只负责“把仓库变成平台可用项目”：校验表单、异步执行 git 登记、做配方自检、写项目表。
不负责构建、部署和运行流水线，这些在接入完成后由执行面接手。
平台不往用户仓库写 `ci/`，也不把用户目录登记成平台 bare 的 worktree；用户目录始终是它自己的仓库。
入口服务是 `platform/agent-infra/src/main/java/top/rongx/agent/infra/git/ProjectIntakeService.java`，
git 操作在 `GitWorktreeManager.java`，HTTP 入口在 `ProjectIntakeController`。

## 输入

- `ProjectIntakeService.IntakeRequest`：`projectId`、`localPath`、`repoUrl`、`type`、`gitInitIfMissing`、`storageMode`、`cloneTargetDir`、可选 HTTPS 凭据。
- `projectId` 白名单：大写字母开头，大写字母/数字/下划线，2-16 位。
- `storageMode` 默认 `DIRECT`：本地目录就地使用，远程仓库 clone 到用户选择目录；`MANAGED` 才建立平台 bare。
- 工作区根 `agent.workspace.root`；接入项目持久化到统一库 `<workspace>/_platform/platform.db`（配置 `agent.db.url` 时落 PostgreSQL）。
- HTTP 入口：`POST /api/v1/projects/intake/local`、`/intake/remote`，状态查询 `GET /intake/{projectId}/status`。

## 输出

- `IntakeState(projectId, phase, message, report, updatedAt)`：同步返回起始状态，后续靠状态查询轮询。
- 目录约定：
  - MANAGED bare：`<workspace>/<projectId>/git`。
  - MANAGED worktree：`<workspace>/<projectId>/worktrees/<name>`，自检固定 `wt-verify`，构建为 `wt-build-*`。
  - DIRECT：仓库留在用户目录或 clone 目标目录，自检 worktree 在 `<repo>/.agent/worktrees/wt-verify`。
- 配方自检报告：`pipeline`、`dockerfile`、`actions`、`blockers`、`suggestions`、`info`、`warnings`、`deployable`、`ready`。
- SQLite 项目记录：来源 `LOCAL/REMOTE`、存储模式 `DIRECT/MANAGED`、`localPath`、三个环境 `dev/test/prod` 的登记行。

## 关键状态与流转

1. `ProjectIntakeService#submit` 先做表单校验，再记录 `VALIDATING`，用虚拟线程异步执行；HTTP 不等待 git 与自检完成。
2. 状态机：`VALIDATING → LINKING → RECIPE_CHECK → REGISTERED`，任一步抛异常进入 `INTAKE_FAILED`。
3. 重复提交拦截只看“正在跑”的 `VALIDATING/LINKING/RECIPE_CHECK`；`REGISTERED` 与 `INTAKE_FAILED` 都允许重提交，前者是重新收编，后者是重试。
4. 本地目录 + DIRECT：确认 `.git` 存在；缺失时只有 `gitInitIfMissing=true` 才执行 `initBareless`；随后 `revParse` 取 HEAD 并挂 `wt-verify`。
5. 本地目录 + MANAGED：`linkLocal` 建 bare、给用户目录加 `agent` remote、把 HEAD push 进 bare；平台读取靠之后 `fetchLocal`，不接管用户目录。
6. 远程 + DIRECT：`cloneTo` 到 `cloneTargetDir`；远程 + MANAGED：`cloneRemote` 建 bare；两者都在 `wt-verify` 上做自检。
7. `RECIPE_CHECK` 优先使用仓库 `ci/pipeline.yml`；缺失时由 `ProjectScaffolder` 按仓库结构生成，定义写到平台侧而非用户仓库。已有手工定义不覆盖，自动生成的旧定义可刷新。
8. 自检只检查最终定义真正引用的动作：`ci/actions.yml`、`Makefile` 或平台生成配方；同一 code 只保留一条诊断。`ready` 要求无 blocker 且拿到流水线定义。
9. 完成后 `persistProject` 写 SQLite 并更新为 `REGISTERED`；有 blocker 仍然登记，但 message 标明“有阻塞项”。

## 失败与降级

- 表单错误同步抛 `IllegalArgumentException`，由 HTTP 层直接返回。
- git 命令失败、clone 目标非空、重复 clone、目录缺 `.git` 且未允许 init，都进入 `INTAKE_FAILED`，message 保留原始报错。
- 自检发现 `DOCKERFILE_MISSING`、`ACTION_MISSING`、`SCAFFOLD_FAILED` 时记 blocker，`deployable=false`，但仍完成项目登记。
- git 命令硬超时 600 秒，fetch 单独 90 秒；超时带可读提示，不无限挂起。
- 用户目录被删除后，后续 `fetchLocal` 抛“本地目录已不存在（项目标记为待修复）”，不静默重建。
- `intakes` 是内存态，进程重启后运行中的状态丢失；已 `REGISTERED` 的项目在 SQLite 中保留。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| 接入提交、校验与状态机 | `ProjectIntakeService#submit` | [infra-project-intake](../l3/infra-project-intake.md) |
| 配方自检与诊断分级 | `ProjectIntakeService#recipeCheck` | [infra-project-intake](../l3/infra-project-intake.md) |
| 本地收编 / 远程 clone | `GitWorktreeManager#linkLocal` / `#cloneRemote` | [infra-git-worktree](../l3/infra-git-worktree.md) |
| 平台 worktree 创建 | `GitWorktreeManager#addWorktree` | [infra-git-worktree](../l3/infra-git-worktree.md) |
| 接入 HTTP 入口 | `ProjectIntakeController#intakeLocal` / `#intakeRemote` | [infra-project-intake](../l3/infra-project-intake.md) |

## 修改指引

- 改状态机、重试或重复提交：先看 `ProjectIntakeService#submit`、`#isRunning`、`#runIntake`。
  既有测试：`platform/agent-infra/src/test/java/top/rongx/agent/infra/git/RecipeCheckTest.java`、
  `IntakeScaffoldTest.java`、`platform/agent-gateway/src/test/java/top/rongx/agent/gateway/IntakeE2ETest.java`。
- 改目录约定、clone 或收编：看 `GitWorktreeManager#bareDir`、`#worktreesDir`、`#linkLocal`、`#cloneRemote`、`#addWorktree`。
  既有测试：`platform/agent-infra/src/test/java/top/rongx/agent/infra/git/GitWorktreeManagerTest.java`、`GitAuthInjectionTest.java`。
- 改配方自检：看 `ProjectIntakeService#recipeCheck`、`#usedActions`、`#targetIsDocker`；
  既有测试是 `RecipeCheckTest.java`，覆盖缺 Dockerfile、Makefile 回退、Java 工程生成与结构化摘要。
- 改平台侧定义生成：看 `ProjectScaffolder`、`RecipeScaffolder` 与 `PipelineDefStore` 的读写；
  既有测试 `IntakeScaffoldTest.java` 覆盖“不写用户仓库”和“重新接入刷新自动定义”。
- 改 HTTP 参数或凭据匹配：看 `ProjectIntakeController` 与 `GitHostingAccountRepository`；不要把 token 放进 URL 或日志。
