---
name: pipeline-engine
description: 流水线引擎：阶段执行、看门狗强杀、自动修复闭环、运行账本与超时语义
covers: [BuiltInPipelineEngine, PipelineEngine, RunHistoryStore, ImageProvisioner, DockerActionRunner]
when_to_read: 流水线阶段不跑/卡死/超时/强制终态/自动修复/运行历史/部署阶段问题
budget: 120
---

# 流水线引擎（pipeline-engine）

## 关键文件

| 文件 | 角色 |
|---|---|
| `platform/agent-executor/.../BuiltInPipelineEngine.java` | 引擎核心：阶段循环、看门狗、自动修复、部署 |
| `platform/agent-executor/.../DockerActionRunner.java` | 动作执行：超时/取消/退出码 → 错误码映射 |
| `platform/agent-executor/.../SandboxConfig.java` | 沙箱装配（配置键 → DockerSandbox） |
| `platform/agent-gateway/src/main/resources/application.yml` | `agent.pipeline.*`、`agent.sandbox.*` 配置 |

## 关键事实

- **开跑前先亮环境**：`runStagesFrom` 先 `provisionImages`（并行供给全部阶段镜像），失败不进阶段循环。
- 看门狗 `forceTerminate`：`future.cancel(true)` + `executionDone.await(10s)`；超时未退出会**明说"未确认退出"**（下一次部署可能竞争）。
- `FutureTask.cancel(true)` 落在任务启动前 → callable **永不执行**、latch 永不触发：测试必须先等线程真跑起来（`PipelineEngineLifecycleTest` 的教训，禁止回退）。
- `executionFuture == null`（恢复的幽灵任务）→ 不等 10s，立即终态。
- 自动修复闭环：失败 → `repairContext`（携带 provisioner）→ 重跑；`agent.pipeline.auto-repair*` 控制轮次。
- 错误码分组有构造器守卫：`EnvironmentException` 只能带 ENVIRONMENT 组错误码；TIMEOUT/CANCELLED 要经 `subErrorCode` 降级（见 `DockerActionRunner`）。
- 运行账本 `run_ledger` 跨引擎统一（`RunKind` 分类）；终态条目由 `evictIfTerminal` 移出内存、history 仍可查。

## 超时语义（两档，别混）

- 停滞：idle 无输出 → "执行停滞（Ns 无输出）"（`ProcessExecutor.execUntilIdle`）
- 总量：到达上限 → "执行超时（Ns，总时长已达上限）"

## 怎么验证

- `PipelineEngineLifecycleTest`（终态/强杀/幽灵任务）、`DockerActionRunnerPrepareTest`、`ImagePullRepairTest`
- 跑法：`mvn -pl agent-executor '-Djacoco.skip=true' test -Dtest='<类名>'`
- 并行负载下才暴露的竞态要 `-Dsurefire.forkCount=4` 复现（历史上唯一 flake 就是这么抓的）

## 找历史

`python tools/docs/find.py --impact BuiltInPipelineEngine`；`docs/views/modules/agent-executor.md`。
