---
canonical: docs/summary/2026-11/2026-11-18_2355_sse-stall-failover.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T00:08:40+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_2355_sse-stall-failover
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 141
  passRate: "100%"
  lineCoverage: "40.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# SSE 心跳改为命名事件并在静默超时主动下发 run.failed

## 概述
task-76c9432989（对话流中断）与 task-90836fe8d5（发送按钮失效）：SSE 只发注释帧心跳，前端解码器把注释帧直接丢弃，界面无法区分「服务端在算」和「服务端已挂」；上游长时间无进展时连接无限挂起，只能手点停止；调度会话的 DAG 派发过程完全不落时间线，断线后刷新无法重放。
心跳改成命名事件 heartbeat（带 alive 载荷）；新增静默看门狗，业务事件超过 agent.chat.stall-seconds 无进展时主动下发 error 帧（写明等了多少秒与该怎么做）并关流，同时取消上游订阅；ChatController 与 WorkspaceSuperController 的超时和静默都落终态到 ConversationTimelineRecorder；调度会话补上时间线记录器；前端 heartbeat 帧不进 reducer，并在 60 秒收不到心跳时主动中止并提示重新发送。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c2821ad` | feat | feat(chat): SSE 命名心跳与静默看门狗，挂住变成明确报错 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 141, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：4
- 行覆盖率：40.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_2355_sse-stall-failover/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
