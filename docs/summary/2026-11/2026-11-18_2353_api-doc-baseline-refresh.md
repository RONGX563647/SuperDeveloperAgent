---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T23:53:24+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-18_2353_api-doc-baseline-refresh
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 24
  passRate: "100%"
  lineCoverage: "40.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 任务接口补文档后刷新 API 文档基线

## 概述
任务接口补上 Javadoc 后，api-doc-baseline.txt 里对应的基线行变成过期项，spec-checks 的 ApiDocContractTest 因此变红，pre-push 直接拒绝推送。
用 -Dapi.doc.baseline.update=true 重新生成基线（棘轮只允许收缩），GitIssueController#update 从基线中移除；spec-checks 恢复 24 个用例全绿。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `78fe011` | chore | chore(spec-checks): 任务接口补文档后重新生成 API 文档基线 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：40.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-18_2353_api-doc-baseline-refresh/manifest.json`
- 上一份同域总结：worktree-commit-hook
