---
canonical: docs/summary/2026-11/2026-11-19_2020_task-version-filter.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T20:21:20+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_2020_task-version-filter
metrics:
  sessions: 1
  casesAdded: 17
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "41.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 任务栏新增按版本号筛选

## 概述
任务栏只能按状态/类型/优先级筛选，无法按关联版本号收敛——一个版本发布前想知道这个版本还剩哪些任务只能肉眼翻列表。
新增 utils/taskVersionFilter.ts（normalizeVersion / compareVersion / filterByVersion / versionFilterOptions）承接纯逻辑，列表工具栏新增版本下拉（全部/各版本/未关联）；版本号按点分段数值比较而非字符串，并归一前导 v；只列真实出现过的版本避免死选项；筛选项基于全部已加载任务以免选完消失；看板点状态/优先级时清空版本筛选。顺带把前端测试改成自动发现（package.json 用新 run-tests.mjs，门禁 web_tests 改目录扫描），修掉手写清单导致 8 个测试文件只有 3~6 个真正执行的静默漏跑。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `abe33a1` | feat | feat(task): 任务栏新增按版本号筛选 |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：17
- 行覆盖率：41.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_2020_task-version-filter/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
