---
canonical: docs/summary/2026-11/2026-11-19_1214_api-doc-baseline-refresh-2.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:14:50+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1214_api-doc-baseline-refresh-2
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 24
  passRate: "100%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 派发接口补文档后刷新 API 文档基线

## 概述
WorkspaceController#send 按新契约补了 Javadoc（说明失败抛异常而不塞假 runId），api-doc-baseline.txt 里对应行从缺口变成已文档化，ApiDocContractTest 把它判为过期项而变红，pre-push 拦住推送。
用 -Dapi.doc.baseline.update=true 重新生成基线（棘轮只允许收缩），把已补文档的行移出；spec-checks 恢复 24 用例全绿。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `f3aeab3` | chore | chore(spec-checks): 派发接口补文档后刷新 API 文档基线 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1214_api-doc-baseline-refresh-2/manifest.json`
- 上一份同域总结：worktree-commit-hook
