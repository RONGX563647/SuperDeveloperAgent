# 文档-代码对照表

> 用途：把每篇现行文档绑定到它描述的实现位置。**代码目录/文件被移动或删除，`check-docs` 直接失败**，迫使同 PR 更新文档或将其废弃。
> 规则：
> 1. 每篇非索引文档必须在本表出现且只出现一次；`README.md`、ADR 原文、`changelog/`、`deprecated/`、`_research/` 不在此表强制登记。
> 2. `代码锚点` 写仓库相对路径，必须是真实存在的文件或目录；未实现的规划文档写 `—`，状态必须含“规划”。
> 3. `最后核对` 是人工确认“文档与这些代码仍一致”的日期；只改代码不改日期视为未核对，评审时应拒绝。
> 4. 只校验“位置存在”，不校验语义漂移；语义变更仍需人工更新日期和正文。

## 对照表

| 类别 | 文档 | 状态 | 代码锚点 | 最后核对 | 说明 |
|---|---|---|---|---|---|
| 架构 | [Code-Wiki](Code-Wiki.md) | 稳定 | `platform/agent-tools/src/main/java`、`platform/agent-executor/src/main/java`、`platform/agent-runtime/src/main/java` | 2026-11-15 | 总览 + 模块/类/API 地图 |
| 产品 | [PRD](PRD-DevOps-Agent平台-v1.0.md) | Draft | `platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web`、`platform/agent-tools/src/main/java/top/rongx/agent/tools` | 2026-11-15 | 产品目标与历史决策；现状以 Code-Wiki 为准 |
| 流水线 | [流水线设计](流水线设计-Java与Vue双栈.md) | 参考 | `platform/agent-executor/src/main/java/top/rongx/agent/executor/PipelineDefinition.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/scaffold/RecipeScaffolder.java` | 2026-11-15 | Java/Vue 双栈与脚手架 |
| 流水线 | [规约-流水线执行器](规约-流水线执行器-v1.0.md) | 参考 | `platform/agent-executor/src/main/java/top/rongx/agent/executor/PipelineDefinition.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/BuiltInPipelineEngine.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/PipelineDefinitionResolver.java` | 2026-11-15 | stage 契约与定义解析 |
| 流水线 | [CICD 决策](CICD方案替代与自创-决策-v1.md) | 决策记录 | `platform/agent-domain/src/main/java/top/rongx/agent/domain/action/ActionId.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/recipe/RecipeActionResolver.java` | 2026-11-15 | 决策时点原文；动作数量以代码为准 |
| 流水线 | [窄控制器](窄控制器设计-单机滚动部署器.md) | 规划 | — | 2026-11-15 | 未实现的备选执行面，不得当作现状 |
| 部署 | [发布编排](发布编排与平台部署器-v1.md) | 现行 | `platform/agent-executor/src/main/java/top/rongx/agent/executor/Deployers.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerDeployer.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/HostDeployer.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/ServerDeployer.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/LocalDevHostDeployer.java` | 2026-11-15 | 三种平台目标 + dev 本机部署 |
| 部署 | [多项目托管 v4](多项目托管设计-v4.md) | 部分现行 | `platform/agent-infra/src/main/java/top/rongx/agent/infra/git`、`platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ProjectIntakeController.java` | 2026-11-15 | 项目一级实体与工作项 |
| 部署 | [项目接入 v2](项目接入设计-v2.md) | 现行 | `platform/agent-infra/src/main/java/top/rongx/agent/infra/git/ProjectIntakeService.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/git/GitWorktreeManager.java`、`platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ProjectIntakeController.java` | 2026-11-15 | 本地目录/远端/worktree |
| 环境 | [环境模板 v2](环境变量与环境模板设计-v2.md) | 现行 | `platform/agent-infra/src/main/java/top/rongx/agent/infra/env/SqliteEnvironmentStore.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/env/LocalProcessServiceRuntime.java` | 2026-11-15 | 模板/资源/绑定/端口租约 |
| 环境 | [环境事实层](环境配置事实层与分环境门控-方案-v1.md) | 部分现行 | `platform/agent-infra/src/main/java/top/rongx/agent/infra/env/SqliteEnvironmentStore.java`、`platform/agent-tools/src/main/java/top/rongx/agent/tools/ActionService.java` | 2026-11-15 | 事实层 + 分环境门控 |
| 产品 | [流水线工作台使用指南](流水线工作台-使用指南-v1.md) | 现行 | `platform/agent-domain/src/main/java/top/rongx/agent/domain/action/ActionId.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/recipe/RecipeActionResolver.java` | 2026-11-15 | 动作白名单与配方 |
| 产品 | [设置](设置-服务器与模型连接-v1.md) | 现行 | `platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/SettingsController.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/settings/SqliteDeployServerStore.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/settings/SqliteModelConnectionStore.java` | 2026-11-15 | 服务器与模型连接 |
| 治理 | [项目规约](项目规约-v1.0.md) | 稳定 | `platform/spec-checks/src/test/java/top/rongx/agent/arch` | 2026-11-15 | 强制条款与机械检查 |
| 治理 | [规约自动化检查](规约自动化检查-v1.0.md) | 稳定 | `platform/spec-checks/src/test/java/top/rongx/agent/arch` | 2026-11-15 | 规约测试实现 |
| 治理 | [Agent 文档治理方案](governance/Agent文档治理方案-v1.md) | 部分现行 | `scripts/docs/check-links.mjs`、`scripts/docs/check-docs.mjs` | 2026-11-15 | 已落地/暂缓项 |
| 规格 | [dev 本机部署规格](specs/dev-local-deploy/spec.md) | 现行 | `platform/agent-executor/src/main/java/top/rongx/agent/executor/LocalDevHostDeployer.java`、`platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/DeployController.java` | 2026-11-15 | 本机前后端部署；T17/T33 未完成 |
| 规格 | [dev 本机部署任务](specs/dev-local-deploy/tasks.md) | 现行 | `platform/agent-executor/src/main/java/top/rongx/agent/executor/LocalDevHostDeployer.java` | 2026-11-15 | 任务清单 |
| 规格 | [注入自检规格](specs/env-inject-health/spec.md) | 现行 | `platform/agent-domain/src/main/java/top/rongx/agent/domain/env/ProjectEnvFiles.java`、`platform/agent-gateway/src/main/java/top/rongx/agent/gateway/settings/EnvBindingService.java`、`platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/EnvironmentController.java` | 2026-11-15 | 环境注入与健康；T7 未完成 |
| 规格 | [注入自检任务](specs/env-inject-health/tasks.md) | 现行 | `platform/agent-domain/src/main/java/top/rongx/agent/domain/env/ProjectEnvFiles.java`、`platform/agent-gateway/src/main/java/top/rongx/agent/gateway/settings/EnvBindingService.java` | 2026-11-15 | 任务清单 |
| 规格 | [环境一致性规格](specs/env-runtime-consistency/spec.md) | 部分现行 | `platform/agent-domain/src/main/java/top/rongx/agent/domain/env/StackAdapter.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerEnvProvisioner.java`、`platform/agent-infra/src/main/java/top/rongx/agent/infra/env/SqliteEnvironmentStore.java` | 2026-11-15 | 环境与运行时一致；P2-1 未实现 |
| 规格 | [环境一致性任务](specs/env-runtime-consistency/tasks.md) | 部分现行 | `platform/agent-domain/src/main/java/top/rongx/agent/domain/env/StackAdapter.java`、`platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerEnvProvisioner.java` | 2026-11-15 | 任务清单 |

## 维护流程

1. 新增文档：先在 [docs/README.md](README.md) 登记，再在本表加一行，写真实代码锚点。
2. 移动/重命名代码：同 PR 改本表锚点；改不动就降级文档状态或走[废弃登记](deprecated/README.md)。
3. 改代码语义：更新文档正文与 `最后核对` 日期；只改日期不改正文视为造假。
4. 运行 `node scripts/docs/check-docs.mjs`；锚点缺失、文档漏登记、日期格式错误都会失败。
