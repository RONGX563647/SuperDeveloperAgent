---
canonical: docs/summary/2026-11/2026-11-19_2311_handbook-anchor-sync.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T23:11:44+08:00
extends: v010-carryover-ledger
refs:
  - docs/2026-11-19_2311_handbook-anchor-sync
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "N/A"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 同步 handbook 锚点

## 概述
推送 docs/round-status 时 spec-checks 报「架构 + 契约」未通过，31 个用例里只有 HandbookDriftTest 红。原因是并行的执行器改动（BuiltInPipelineEngine）让 executor-pipeline-engine 这份 handbook 记录的锚点行号漂移——handbook 的锚点是「文件+起止行号」，代码一插入行就对不上。这类漂移不是逻辑错误，但门禁要求生成区与代码严格一致。
跑 tools/handbook/build.py sync 让生成区跟随代码更新，提交后再推送；同步只改行号（5 行新增/删除），锚点的 symbol 与语义不变。这也说明 handbook 的「锚点即代码位置」设计对重构敏感：改动执行器后必须同步，否则 pre-push 直接拦。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `48a8c15` | docs | docs(handbook): 同步执行器锚点行号 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：N/A（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/docs/2026-11-19_2311_handbook-anchor-sync/manifest.json`
- 上一份同域总结：v010-carryover-ledger
