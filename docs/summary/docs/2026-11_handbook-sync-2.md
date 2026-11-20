---
canonical: docs/summary/2026-11/2026-11-19_2332_handbook-sync-2.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T23:33:24+08:00
extends: v010-carryover-ledger
refs:
  - docs/2026-11-19_2332_handbook-sync-2
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "32.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 同步执行器锚点

## 概述
推送命中率面板这批改动时，pre-push 报「Harness Handbook 已漂移」。原因是本 worktree 上与并行工作线共用：它们的执行器改动（BuiltInPipelineEngine / TriggerScheduler）改变了代码行号，而 handbook 的锚点是「文件 + 起止行号」，行号一变就对不上。第一次同步时我为了跑通测试把这些文件 stash 了，导致同步结果基于「被 stash 后的代码」，一旦恢复就又漂移——这是顺序错了。
先恢复并行工作线的改动，再执行 handbook sync，让锚点基于真实代码计算；提交 handbook 后单独推送 docs。这条经验值得记：handbook 同步必须与代码的最终状态同源，在临时 stash 期间同步等于白做。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c21e04a` | docs | docs(handbook): 按当前执行器代码同步锚点 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：32.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/docs/2026-11-19_2332_handbook-sync-2/manifest.json`
- 上一份同域总结：v010-carryover-ledger
