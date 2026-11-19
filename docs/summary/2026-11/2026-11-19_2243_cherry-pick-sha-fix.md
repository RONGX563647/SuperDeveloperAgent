---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T22:43:59+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2243_cherry-pick-sha-fix
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "41.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 修正 cherry-pick 后归档记录的旧 sha

## 概述
PR #27 的 docs job 报「会话归档校验失败：2 项」，指出 feat/1747 与 feat/1820 两份归档里的 commit ea95dc0 与 2d8c0e4 不存在。本地 gate validate 却能通过——因为本地仓库仍能解析这两个 sha（它们在我的旧分支历史里），而 CI 是全新 clone，只拿到主干可达的提交，旧 sha 在新基线上不可达。根因是：把界面那批 cherry-pick 到主干后，同一份改动换了 sha（4fe1c13 / 636a7ce），但归档里的 commits 仍记着旧 sha。
用 append-cherry-pick.py 把两份归档的旧 sha 改记为新 sha 并重算 chain.self；feat 分类因多个并行会话各自接旧链尾而断链，整体 reseal 一次（涉及 6 份）。同时把这个工具正式入库——它此前反复被写出来又在分支切换中丢失，这次连同「sha 必须 40 位全长」「改完必须重算链」两个坑一起固化。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `a054113` | bug | fix(process): 归档改记 cherry-pick 后的新 sha，修复 CI 归档校验 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：41.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_2243_cherry-pick-sha-fix/manifest.json`
- 上一份同域总结：worktree-commit-hook
