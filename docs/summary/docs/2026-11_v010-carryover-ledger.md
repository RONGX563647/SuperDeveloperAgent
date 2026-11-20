---
canonical: docs/summary/2026-11/2026-11-19_1221_v010-carryover-ledger.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:21:45+08:00
extends: session-6afba843
refs:
  - docs/2026-11-19_1221_v010-carryover-ledger
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 14 条遗留项逐条对账

## 概述
本轮 14 批修复完成后，14 条清单、提交号与三条结构性判断的落地情况散在 14 份归档里，没有一张能直接核对的表：想确认「某条到底修没修、落在哪个提交」只能逐份翻。
新增 docs/summary/bug/2026-11_v010-carryover-ledger.md：按 P0/P1/P2 三条清单逐条列出「任务 id → 后端实际做了什么 → 提交号」，并单列三条结构性判断的落地结果（真后端 bug 与 UI 分开、优先级重排依据、③ 先修）、附带修掉的两处（归档编码命名、两条缺失记录补建）与质量证据（748 用例全绿、62 个新增回归用例、每批都过 pre-commit 与 pre-push 全量门禁）。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `6f8fc5a` | docs | docs(summary): 14 条遗留项逐条对账清单 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/docs/2026-11-19_1221_v010-carryover-ledger/manifest.json`
- 上一份同域总结：session-6afba843
