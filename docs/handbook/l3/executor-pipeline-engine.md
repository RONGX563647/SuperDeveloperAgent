---
type: handbook-l3
level: L3
module: agent-executor
title: 流水线触发
summary: 参数校验、dry-run 哈希自证、建运行状态并异步执行
---

# BuiltInPipelineEngine 触发

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 流水线触发与异步执行入口 | `BuiltInPipelineEngine#trigger` | `platform/agent-executor/src/main/java/top/rongx/agent/executor/BuiltInPipelineEngine.java:1107-1201` | agent-executor |
<!-- generated:end -->

## 行为拆解

1. 流水线必须在白名单内，`gitSha` 必须是 7-40 位小写十六进制。
2. 环境名规范化：只认 dev/test/prod，缺省按 test，未知值 fail-fast。
3. 带 `dryRunHash` 时重算 dry-run：未通过抛 `DRY_RUN_FAILED`，哈希不一致抛 `DRY_RUN_STALE`
   （防止"预检后配方/代码被改"）。
4. 服务器操作要求已有测试环境的成功案例（`promotionProblem`）。
5. 建 `RunState`（runId、seq、部署目标、版本/tag、bindingId），写入运行历史与账本，交给调度器异步执行。

## 状态与副作用

- `runs` 内存表 + `history` 持久化双写；重启后由 `restoreHistory` 恢复。
- dev 环境即使显式传 docker 也固定本机运行（`verify` 流水线除外）。

## 失败与降级

- 参数/白名单/证据错误：直接抛异常，不产生运行记录。
- 仓库不可定位：抛 `COMPILE_FAILED` 并提示先完成项目接入。

## 相关测试

`platform/agent-executor/src/test/java/top/rongx/agent/executor/` 下的
`PipelineDefinitionTest`、`PipelineAutoRepairTest`、`PipelineWatchdogTest`。
