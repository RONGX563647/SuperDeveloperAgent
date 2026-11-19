---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T16:21:00+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_1526_server-timeline-and-watchdog
metrics:
  sessions: 1
  casesAdded: 14
  casesTotal: 669
  passRate: "100%"
  lineCoverage: "39.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 服务端时间线落盘+任务票据化与断线续传/看门狗

## 概述
会话事件只在内存总线里滚动，刷新/换端/进程重启后看不到历史；运行卡死时一直显示运行中并占着会话并发位。
新增 conversation_timeline 落盘与游标读取、派发任务票据化；新增 SSE 时间线订阅支持 Last-Event-ID 断线续传；新增运行看门狗收敛超时/排队超限运行并释放并发位。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c969d20` | feat | feat(workspace): 服务端时间线、任务票据与断线续传/看门狗 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 669, Failures: 0, Errors: 0, Skipped: 1
- 新增用例：14
- 行覆盖率：39.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_1526_server-timeline-and-watchdog/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
