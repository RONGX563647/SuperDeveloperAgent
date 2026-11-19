---
canonical: docs/summary/2026-11/2026-11-18_2341_issue-version-labels-readback.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T23:50:36+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_2341_issue-version-labels-readback
metrics:
  sessions: 1
  casesAdded: 8
  casesTotal: 191
  passRate: "100%"
  lineCoverage: "40.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 任务版本与标签可读可选取，部分更新不丢字段

## 概述
task-605d5e5fcd / task-67bd7174cf / task-a1074891b7 的共同根：任务关联版本号与标签写不进读不出。版本号每条任务只能手输，没有候选来源；PUT 更新时缺失字段被当成空串，只改版本号会把标题与标签一起清空；Agent 工具不回显 code/version/labels，模型看不到自己写进去的值。
新增 GET /api/v1/git-issues/versions，返回当前分支、按分支版本线推导的建议版本与仓库标签，任务栏与 Agent 共用同一份候选；PUT 改为按 key 局部更新，请求体里没出现的字段沿用当前值，显式空串仍可清空；create/update 工具回显 code/version/labels，list/get 工具输出 code 与 version；前端版本上下文改由后端接口提供，删掉前端重复实现的版本推导。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `ff496d1` | feat | feat(task): 任务版本与标签可写可读，版本候选与局部更新统一口径 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,agent-tools,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway,agent-tools test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 191, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：8
- 行覆盖率：40.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_2341_issue-version-labels-readback/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
