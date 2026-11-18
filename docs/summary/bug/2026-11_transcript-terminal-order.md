---
canonical: docs/summary/2026-11/2026-11-18_0840_transcript-terminal-order.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T08:42:41+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_0840_transcript-terminal-order
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 571
  passRate: "100%"
  lineCoverage: "38.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修复会话时间线被下一轮覆盖的竞态

## 概述
ConversationManagerTest 偶发失败：waitForRun 返回时时间线可能还没写完，同一会话紧接着的下一轮会读到旧时间线并覆盖上一轮。
WorkspaceRunExecutor.finish 调整为 saveTranscript 先于 runs.updateRun 落终态，保证外部看到 terminal 时本轮时间线已可读；新增 BlockingStore 回归测试锁定该顺序。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `92ad561` | bug | fix(runtime): 先落会话时间线再暴露运行终态 |

## 测试与质量总结
- 命令：`mvn -B test -Djacoco.skip=true -Dtest='!ProcessArchiveTest,!DeployEnvPrecheckTest,!DeployRecipeFlowTest,!DockerDaemonRepairTest' -Dsurefire.failIfNoSpecifiedTests=false`
- 结果：PASSED；Tests run: 571, Failures: 0, Errors: 0, Skipped: 4
- 新增用例：1
- 行覆盖率：38.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 继续

## 链接
- 归档：`docs/process/bug/2026-11-18_0840_transcript-terminal-order/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
