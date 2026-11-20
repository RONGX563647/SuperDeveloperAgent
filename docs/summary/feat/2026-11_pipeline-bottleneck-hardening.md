---
canonical: docs/summary/2026-11/2026-11-18_0730_pipeline-bottleneck-hardening.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T08:47:29+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_0730_pipeline-bottleneck-hardening
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 619
  passRate: "100%"
  lineCoverage: "39.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 流水线卡点加固：准备预算、缓存卷并发、健康检查窗口

## 概述
流水线存在三类卡点：(1) 准备阶段（镜像拉取/缓存卷初始化）耗时从预算扣掉后把剩余预算夹成 1ms，命令未执行就报'执行超时（0s）'，用户与自愈无法判断原因；(2) DockerSandbox.prepareCacheVolume 用 synchronized(initializedVolumes) 包住最长数分钟的建卷初始化，所有项目串行排队；(3) 健康检查窗口写死 90×2s/60×1s，与运行剩余预算脱钩，看护线程先按总预算强制终态，日志只剩 E-302'超时且无法中断'，真实失败原因被盖住；健康检查里的 docker inspect/logs/rm 复用 5 分钟阶段预算，守护进程挂起即可吃掉整个窗口。
DockerActionRunner：准备阶段耗时进日志；准备耗尽预算时直接返回结构化 E-302（失败摘要写明'准备阶段已耗时 X 超过预算 Y，命令未开始执行'），不再以 1ms 预算启动命令。ProcessExecutor 抽出 human(millis)，超时摘要不足 1s 显示毫秒。DockerSandbox：缓存卷锁改为按卷粒度的 ConcurrentHashMap，不同项目并行、同卷（含 withImage 派生）仍串行。BuiltInPipelineEngine：新增 healthWindowMillis()，窗口取 min(计划窗口, 剩余运行预算) 且带 30s 下限；循环按 nanoTime deadline 收敛；健康探测/诊断/清理改用 5s/10s 专用预算而非 STAGE_TIMEOUT。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `98ce5db` | bug | fix(executor): 收敛流水线卡点：准备阶段预算耗尽原因化、缓存卷按卷加锁、健康检查窗口随总预算收敛 |
| `c4ff5e1` | chore | test(executor): 覆盖准备预算耗尽原因、缓存卷并行初始化与健康检查窗口收敛 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 619, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：5
- 行覆盖率：39.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_0730_pipeline-bottleneck-hardening/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
