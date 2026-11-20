---
canonical: docs/summary/2026-11/2026-11-20_1738_force-terminate-test-race.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T17:44:48+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_1738_force-terminate-test-race
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 264
  passRate: "100%"
  lineCoverage: "40.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# forceTerminate 用例竞态：cancel 落在任务启动前，等 25s 必失败

## 概述
PipelineEngineLifecycleTest.forceTerminateInterruptsThread 在 forkCount=4 下稳定 25s 失败（10s 有界等待 + 15s 断言超时），单独跑 10s 通过，属于并行负载敏感；同类空洞也存在于'不可中断线程'用例。另：executionFuture == null 的有界等待旁路一直没有用例覆盖。
定位为用例自身竞态：submit 后立刻 cancel(true) 却没有等任务开始，FutureTask 在启动前被 cancel 时 callable 永不执行，executionDone 不发信号，于是白等 10s。两处补 started.await(10s) 前置断言并 shutdownNow 收尾；新增'无执行线程的幽灵任务强制终态不白等'回归用例覆盖 executionFuture == null 分支。引擎侧不改：forceTerminate 只在 cancelRequestedAt 超时后触发，那时线程必然早已开始。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `0cc8889` | chore | test(executor): forceTerminate 用例先等执行线程真跑起来，消除并行 fork 下的 25s 假失败 |
| `b11dfc8` | chore | test(executor): 补"无执行线程"回归用例——幽灵任务强制终态不白等 10s |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 264, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：1
- 行覆盖率：40.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_1738_force-terminate-test-race/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
