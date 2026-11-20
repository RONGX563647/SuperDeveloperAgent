---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T15:48:53+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_1548_dispatch-ticket-close-duplicate-key
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 72
  passRate: "100%"
  lineCoverage: "40.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 派发票据回写撞主键（closeTicket 误用 INSERT）

## 概述
调度派发票据关单时 closeTicket 对已存在票据用 saveTask（INSERT），每次回写终态都撞 workspace_tasks_pkey（duplicate key），票据永远停在 RUNNING、结果丢失
closeTicket 改走 updateTask（UPDATE）；ConversationManagerTest fake store 补主键语义（saveTask putIfAbsent 撞 id 抛 / updateTask 缺失抛），新增 dispatchTicketCloseUpdatesInsteadOfInsert 调度流回归

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e50b9f1` | bug | fix(runtime): 派发票据回写改走 updateTask，不再撞 workspace_tasks_pkey |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-runtime,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-runtime test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 72, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：1
- 行覆盖率：40.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_1548_dispatch-ticket-close-duplicate-key/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
