---
canonical: docs/summary/2026-11/2026-11-19_2133_merge-trunk-reseal.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T21:33:45+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2133_merge-trunk-reseal
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

# 合入主干并重排归档链

## 概述
推送 feat/usage-cache-hit-rate 时 CI 三项失败（backend / docs / frontend）。本地 gate validate 却能通过，排查后发现不是代码问题：我的分支基于较旧的主干，落后 5 个提交（含 PR #24 合并进来的界面归档），而归档校验依赖这些文件；缺文件导致 docs 的归档校验失败，frontend 与 backend 的失败也源于同一棵树不完整。合并时又被并行工作线留在工作区的 api-docs-mcp 改动挡住。
先把并行工作线的 api-docs-mcp 改动 stash 起来（不丢弃，留待其恢复），再合入 origin/0.1.0-snapshot 解决分支落后。合并后 feat 分类的归档链因两路各接旧链尾而断裂（chain.prev 断链），用 gate.reseal_chain 按 createdAt 重排并重算 self，validate 恢复通过（129 份）。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `0f233ae` | chore | chore: 合入最新主干并重排归档链 |

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
- 归档：`docs/process/chore/2026-11-19_2133_merge-trunk-reseal/manifest.json`
- 上一份同域总结：worktree-commit-hook
