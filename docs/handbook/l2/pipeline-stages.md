---
type: handbook-l2
level: L2
module: agent-executor
title: 流水线阶段执行与运行状态
summary: 负责把 pipeline.yml 的 stage 编译为 ActionSpec 并确定性执行，维护运行状态、看护与证据留存。
refs:
  - ../l3/executor-pipeline-engine.md
  - ../l3/infra-git-worktree.md
  - ../l3/infra-project-intake.md
  - ../l3/tools-deploy-tools.md
---

# 流水线阶段执行与运行状态

## 职责边界

把 `ci/pipeline.yml` 里的 stage 编译为 `ActionSpec` 并确定性执行，维护 `RunState`、`StageStatus`、超时看护、失败修复与证据留存。引擎是确定性执行器，stage 只能引用白名单动作，不能写任意 shell。不负责项目接入、worktree 目录布局细节，也不调用模型编排阶段。

## 输入

- `projectId`、`pipelineId`（必须在白名单）、`gitSha`（7-40 位小写十六进制）。
- `env`：缺省 test，未知值直接拒绝。
- 可选：`dryRunHash`、`deployTarget`、`bindingId`、`confirmedBy`、`deployImage`、`version`、`tag`、`verifyImage`。

## 输出

- `runId`，运行态进入 `runs` 与 `RunHistoryStore`。
- `List<StageResult>`、运行日志、产物 `ArtifactRef`。
- 成功后固化环境脚本版本（仅真正部署的运行），并登记镜像版本账本。

## 关键状态与流转

1. `BuiltInPipelineEngine#trigger` 校验流水线白名单、gitSha、环境；带 `dryRunHash` 时重新 dry-run 并比对报告哈希。
2. `EnvironmentPromotion.problem` 做晋升前置；仓库不可定位直接拒绝，避免留下必败运行。
3. 生成 `runId`、`seq`，写 `RunState`、历史与账本，交给 `TriggerScheduler`：同项目同环境 FIFO，全局 4 线程池。
4. `execute` 置 RUNNING；dev 本机部署走 `executeLocalDev`，其余用 bare 仓库 `git worktree add` 检出到 `wt-build-<runId>`。
5. 解析定义：环境脚本优先，其次仓库 `ci/pipeline.yml`、平台定义、规则生成；随后执行受审校验。
6. `runStagesFrom` 顺序执行；`runStage` 按 type 分派 `health`、`deploy`、`rollback`，其余动作经 `ActionId.fromWire` 与 `RecipeStore#resolve` 得到 `ActionSpec`，替换变量后交给 `ActionRunner`。
7. 每个 stage 记录 `StageResult`；失败先跑 `on-failure`，再置 FAILED。
8. 成功且真正部署：更新镜像账本、`freezeRecipe` 固化脚本；所有成功运行归档文件产物。
9. `StageStatus`：PENDING、RUNNING、SUCCEEDED、FAILED、TIMEOUT、CANCELLED；终态不再推进。

## 失败与降级

- 定义错误：登记 `E-220` 或 `E-221` 失败 stage，不执行任何动作。
- 用户代码失败（E-1xx）不重试；平台或环境失败可重试。
- 失败保留 worktree，支持 `retryStage` 从失败阶段继续；成功后清理。
- 看护每 5 秒扫描：10 秒落盘、30 秒无输出写心跳、超预算先取消、60 秒不收敛强制 TIMEOUT。
- 仅 UI 触发且非 prod 的运行进入自动修复；修复不成功回落人工重跑。
- 平台重启时未完成的运行标记 CANCELLED，避免幽灵运行。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| 触发与异步执行 | `BuiltInPipelineEngine#trigger` | [executor-pipeline-engine](../l3/executor-pipeline-engine.md) |
| 阶段循环与失败处理 | `BuiltInPipelineEngine#runStagesFrom` | [executor-pipeline-engine](../l3/executor-pipeline-engine.md) |
| 工作树创建 | `GitWorktreeManager#addWorktree` | [infra-git-worktree](../l3/infra-git-worktree.md) |
| 运行历史持久化 | `RunHistoryStore#save` | [executor-pipeline-engine](../l3/executor-pipeline-engine.md) |
| 部署证据查询 | `DeployTools#envStatus` | [tools-deploy-tools](../l3/tools-deploy-tools.md) |

## 修改指引

- 改触发前置：先看 `platform/agent-executor/src/main/java/top/rongx/agent/executor/BuiltInPipelineEngine.java` 的 `trigger` 与 `PipelineController`，对应 `platform/agent-gateway/src/test/java/top/rongx/agent/gateway/DeployStageTest.java`。
- 改阶段执行与状态机：先看 `BuiltInPipelineEngine.java` 的 `runStagesFrom`、`runStage`，以及 `platform/agent-domain/src/main/java/top/rongx/agent/domain/run/StageStatus.java`，对应 `PipelineWatchdogTest.java`、`ProcessActionRunnerTest.java`。
- 改看护与续跑：先看 `heartbeat`、`forceTerminate`、`retryStage`，对应 `PipelineWatchdogTest.java`、`PipelineAutoRepairTest.java`。
- 改定义解析：先看 `PipelineDefinition.java`、`PipelineDefinitionResolver.java`，对应 `PipelineDefinitionTest.java`、`PipelineDefinitionGuardTest.java`。
- 改历史与产物：先看 `RunHistoryStore.java`，对应 `RunHistoryStoreTest.java`。
