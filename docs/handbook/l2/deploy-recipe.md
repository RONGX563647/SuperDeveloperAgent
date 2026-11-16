---
type: handbook-l2
level: L2
module: agent-tools
title: 部署配方与发布门禁
summary: 把跑通的动作序列固化为环境配方，按证据与确认令牌控制上生产
refs:
  - ../l3/tools-deploy-tools.md
  - ../l3/gateway-deploy-gate.md
  - ../l3/executor-pipeline-engine.md
---

# 部署配方与发布门禁

## 职责边界

部署工具负责四件事：查环境当前配方与证据、把成功路径固化成配方、在 dev/test 触发运行、停掉遗留的 HOST 进程。
它不实现具体部署动作，也不允许写任意命令或改凭证；HOST/Docker/SERVER 的实际执行在 `Deployer` 实现里。
`DeployTools` 在 `platform/agent-tools/src/main/java/top/rongx/agent/tools/DeployTools.java`；
配方存储是 `platform/agent-infra/src/main/java/top/rongx/agent/infra/pipeline/FileRecipeStore.java`；
上生产门禁是 `platform/agent-gateway/src/main/java/top/rongx/agent/gateway/deploy/DeployGate.java`。

## 输入

- 环境 `env`（`dev/test/prod`）与 `ProjectContext`（项目、环境、用户、是否生产）。
- 固化参数：动作白名单序列、`with_deploy`、`target`、`port`、`health_path`。
- 触发参数：`pipeline_id`、`git_sha`、`env`、人工确认过的 `binding_id`、可选 `version` 与 `tag`。
- 流水线定义的 `deploy` 段是缺省事实来源；没有定义时才回落到 `docker / 8080 / /healthz`。
- 配方存储路径：`<workspace>/<projectId>/recipes/<env>/v<N>/`，`current` 文件指向生效版本。

## 输出

- `deploy_env_status`：项目、环境、当前配方版本、`evidenceRunId`、证据运行状态、`gitSha`、历史版本数。
- `deploy_save_recipe`：新版本号、证据运行 id、渲染后的 YAML；`DeployRecipe` 记录版本、证据、提交与固化人。
- `deploy_run`：`runId`、环境、版本、是否打 tag、当前状态；生产环境拒绝用工具触发。
- `deploy_host_stop`：`stopped` 或 `not_running`，列出停掉的槽位与 pid。
- 上生产确认：`DeployController#confirm` 在证据通过后签发一次性令牌；`DeployGate#issue` 绑定项目、环境、提交。

## 关键状态与流转

1. 探索态：先 `deploy_env_status` 看是否已有成功配方；有则直接重放，避免重复试错。
2. 运行：`DeployTools#run` 先校验流水线白名单、sha 格式与绑定，再 `engine.dryRun`；dry-run 不通过不创建运行。
3. 固化：`deploy_save_recipe` 取最近一次成功运行作为证据，`FileRecipeStore#save` 递增版本、写 `ci/pipeline.yml`、`ci/actions.yml`、`meta.yml`，并更新 `current`。
4. 重放：`DeployController#replay` 用当前配方触发；生产允许用 test 环境的配方晋升，但必须先过门禁。
5. 门禁：`DeployGate#requireIfNeeded` 只对 prod 生效，要求 test 环境有配方、证据运行仍是 `SUCCEEDED`、证据提交与本次 sha 一致。
6. 令牌：确认令牌 5 分钟有效，绑定 `project + env + sha`，`DeployGate` 消费后即废；换提交或换环境都要重新签发。
7. HOST：`HostDeployer#deploy` 在 `<deployRoot>/<project>-<env>` 下维护 `releases/<sha>/app.jar`、`current`、`previous`、`app.pid`、`app.log`；打 tag 时用 `versions/<slot>` 独立槽。
8. Docker：`DockerDeployer#deploy` 先 `docker rm -f agent-deploy-<project>` 再按端口与环境变量 `docker run`；缺镜像返回 `E-501`。
9. 停止遗留 HOST 进程：只认固定 `app.pid`，可停默认槽或指定版本槽；校验命令行包含 `app.jar` 才结束，避免 pid 复用误杀。

## 失败与降级

- `deploy_run`、`deploy_save_recipe` 对 prod 一律返回错误，不把生产当探索目标。
- 没有已确认绑定时拒绝触发；绑定变量变化由 `requireForRun` 拒绝，需重新生成草案并人工确认。
- dry-run 失败返回 `E-708`，不创建失败历史；缺 Dockerfile 时提示先补 Dockerfile 或改 target。
- `envStatus` 无配方时明确显示“无”，不伪造版本；证据失败不影响查询，只如实显示状态。
- `saveRecipe` 没有成功运行仍可保存，但 evidence 为空并提示先跑一次；生产门禁会因此拒绝晋升。
- 门禁失败：证据不足 `E-601`，缺人工确认 `E-602`；令牌过期、已消费或计划哈希不符同样被拒。
- `FileRecipeStore` 写失败抛 `IllegalStateException`；`invalidate` 只删 `current` 指针，历史版本保留，可用 `activate` 回到旧版本。
- 停止 HOST 进程：prod 拒绝；pid 已退出或文件损坏则清理记录；命令行不是 `app.jar` 则拒绝结束并保留记录。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| 部署环境状态只读查询 | `DeployTools#envStatus` | [tools-deploy-tools](../l3/tools-deploy-tools.md) |
| 配方固化与证据绑定 | `DeployTools#saveRecipe` | [tools-deploy-tools](../l3/tools-deploy-tools.md) |
| 配方版本存储与切换 | `FileRecipeStore#save` / `#activate` | [tools-deploy-tools](../l3/tools-deploy-tools.md) |
| 触发运行与 dry-run | `DeployTools#run` → `BuiltInPipelineEngine#trigger` | [executor-pipeline-engine](../l3/executor-pipeline-engine.md) |
| 停止遗留 HOST 进程 | `DeployTools#stopHostDeployment` | [tools-deploy-tools](../l3/tools-deploy-tools.md) |
| 上生产证据与确认令牌 | `DeployGate#requireIfNeeded` | [gateway-deploy-gate](../l3/gateway-deploy-gate.md) |

## 修改指引

- 改配方模型或版本语义：先看 `DeployRecipe`（`platform/agent-domain/src/main/java/top/rongx/agent/domain/deploy/DeployRecipe.java`）与 `FileRecipeStore`。
  既有测试：`platform/agent-infra/src/test/java/top/rongx/agent/infra/pipeline/FileRecipeStoreTest.java`。
- 改固化规则或默认值：看 `DeployTools#saveRecipe`、`#definedTarget`、`#definedPort`、`#definedHealthPath` 与 `RecipeRenderer`。
  既有测试：`platform/agent-tools/src/test/java/top/rongx/agent/tools/DeployToolsConfigTest.java`、
  `platform/agent-executor/src/test/java/top/rongx/agent/executor/DeployRecipeFlowTest.java`。
- 改上生产门禁或令牌：看 `DeployGate`、`EnvironmentPromotion`、`ConfirmTokenService`、`DeployController#confirm/#replay`。
  既有测试：`DeployRecipeFlowTest.java`（证据与晋升矩阵）、`platform/agent-gateway/src/test/java/top/rongx/agent/gateway/DeployStageTest.java`（部署阶段与 prod 拒绝）。
- 改 HOST/Docker 部署行为：看 `HostDeployer#deploy`、`DockerDeployer#deploy`；`DeployStageTest.java` 覆盖部署段、缺 deploy 段与回滚。
- 改部署配置的受控修改：看 `DeployTools#configureDeploy` 与 `#applyDeployConfig`，改完会让三个环境的旧配方失效；
  既有测试是 `DeployToolsConfigTest.java`，覆盖端口/健康路径、只改 target、volume 与非法 YAML。
- 改停止 HOST 进程：看 `DeployTools#stopHostDeployment` 与 `#hostStateDir`；现有测试只覆盖状态目录命名，停止逻辑需按 dev/test 实测。
