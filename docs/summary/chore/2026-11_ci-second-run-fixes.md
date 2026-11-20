---
canonical: docs/summary/2026-11/2026-11-19_1254_ci-second-run-fixes.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:57:14+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1254_ci-second-run-fixes
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 756
  passRate: "100%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修 CI 第二轮失败：Node 版本、文档契约冲突、dev 依赖漏洞

## 概述
修 CI 第二轮失败：Node 版本、文档契约冲突、dev 依赖漏洞
fix(ci): Node 22、去掉冲突的 doc-marshal、dev 依赖漏洞清零

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `6f2d9a9` | bug | fix(ci): Node 22、去掉冲突的 doc-marshal、dev 依赖漏洞清零 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl !spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 756, Failures: 0, Errors: 0, Skipped: 3
- 新增用例：0
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1254_ci-second-run-fixes/manifest.json`
- 上一份同域总结：worktree-commit-hook
