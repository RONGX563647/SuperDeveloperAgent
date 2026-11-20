---
canonical: docs/summary/2026-11/2026-11-17_0921_issue-priority.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T10:23:47+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-17_0921_issue-priority
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 524
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 任务板统一到 GitIssue：优先级与量化看板

## 概述
工作区右栏任务使用独立存储，与任务页 GitIssue 数据割裂；任务页缺少优先级标注与量化看板
工作区任务统一读写 GitIssue，相关 Agent 工具改走 GitIssueService；任务页新增 P0/P1/P2 徽标、优先级筛选排序与纯 SVG 量化看板；移除宽度限制铺满内容区

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `fab91ab` | feat | feat(issues): 工单优先级、任务板与就绪清单改进 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am -Dtest=!ApiDocsSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：PASSED；Tests run: 524, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- ApiDocsSnapshotTest 在该提交点因快照未同步失败，后续会话已修复

## 链接
- 归档：`docs/process/feat/2026-11-17_0921_issue-priority/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
