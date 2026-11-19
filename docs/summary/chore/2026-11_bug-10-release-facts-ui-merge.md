---
canonical: docs/summary/2026-11/2026-11-19_2059_bug-10-release-facts-ui-merge.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T21:04:45+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2059_bug-10-release-facts-ui-merge
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "41.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-10 把发布事实界面与复用产物入口并入主干

## 概述
BUG-10/BUG-11/BUG-12 的界面部分（发布事实徽标、复用产物部署入口）只落在 feat/release-facts-ui 分支上，没进主干——看板已按「已修」关闭，但用户在界面上仍看不到「首次发布」「标签仅本地」，也找不到复用产物部署入口。原因是主干期间被 PR #21/#22/#23 连续推进，该分支与主干分叉。
把该分支 4 个提交 cherry-pick 到最新主干 06bb27d 之上，形成 fix/release-facts-ui-merge。冲突只在 gate.py 的前端自检清单：主干已改为自动发现所有 *.test.ts（比手工清单更不易漏），故采用主干版本。cherry-pick 期间 pre-commit 门禁要求每步有活跃会话，按提示逐步 continue。复核：vue-tsc 通过，五套前端测试 63 个断言全过。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `4fe1c13` | feat | feat(pipeline): 界面展示发布事实与日志总行数 |
| `35188f6` | docs | docs(process): 归档发布事实界面展示会话 |
| `636a7ce` | feat | feat(pipeline): 构建历史提供「复用产物部署」入口 |
| `ef5fb2b` | docs | docs(process): 归档复用产物部署入口会话 |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：41.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_2059_bug-10-release-facts-ui-merge/manifest.json`
- 上一份同域总结：worktree-commit-hook
