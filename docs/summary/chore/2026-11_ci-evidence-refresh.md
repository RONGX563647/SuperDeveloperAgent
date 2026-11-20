---
canonical: docs/summary/2026-11/2026-11-19_2032_ci-evidence-refresh.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T20:33:08+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2032_ci-evidence-refresh
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "41.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 补 CI 门禁的第二组失败证据

## 概述
修完 PR #21 那条后，PR #22 合并又产生一条 58c383a「feat(task): ?????????(PR #22)」，同一位置再次变红。这证明「每合并一个 PR 多一条损坏提交」是持续发生的，不是偶发；而原来的归档只写了第一组证据（8 条），没有把「规律有多稳」和「怎么复现验证」说清楚，下一轮看的人仍会怀疑是不是碰巧。
补第二组证据：重新统计主干损坏提交，已达 13 条且全部是平台合并（subject 结尾 (PR #n)）、非平台损坏 0 条，说明规律稳定成立。docs/ci-commit-encoding.md 增加可复现的验证命令——用修复后的门禁重放两次失败的 CI 原位范围（--base b82fe79 --head 1d03365 与 --base 1d03365 --head 58c383a），修复前均 exit 1、修复后均 exit 0 并列出平台合并提示。归档 manifest 同步补这段复核记录并重算 chain.self。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `2e8b205` | docs | docs(ci): 补门禁的第二组失败证据与可复现验证方式 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：41.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_2032_ci-evidence-refresh/manifest.json`
- 上一份同域总结：worktree-commit-hook
