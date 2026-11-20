---
canonical: docs/summary/2026-11/2026-11-19_1508_bug-24-pending-tool-recovery.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T15:27:22+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1508_bug-24-pending-tool-recovery
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 66
  passRate: "100%"
  lineCoverage: "40.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-24 工具调用悬挂导致会话永久中断

## 概述
BUG-24 工具调用悬挂导致会话永久中断
﻿fix(agent): 开启悬挂工具调用恢复，堵住会话永久中断

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `5fc1ab1` | chore | ﻿fix(agent): 开启悬挂工具调用恢复，堵住会话永久中断 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-runtime,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-runtime test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：2
- 行覆盖率：40.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1508_bug-24-pending-tool-recovery/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
