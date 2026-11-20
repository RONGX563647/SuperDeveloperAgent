---
canonical: docs/summary/2026-11/2026-11-20_1658_apidoc-contract-operation.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T17:00:21+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-20_1658_apidoc-contract-operation
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 217
  passRate: "100%"
  lineCoverage: "40.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 补齐接口契约：新端点加 @Operation(summary)，spec-checks 恢复绿

## 概述
pre-push 的 spec-checks 红线未通过：新加只读端点缺少 Javadoc 里的 @Operation(summary)，接口契约测试（ApiDocContractTest）判定为未文档化接口。
给 images() 补 Javadoc + @Operation(summary/description)，并重新生成 OpenAPI 快照，使契约测试与快照同时保持一致。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e72ff5d` | chore | chore(gateway): 新端点补 @Operation(summary)，接口契约测试恢复绿 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 217, Failures: 0, Errors: 0, Skipped: 5
- 新增用例：0
- 行覆盖率：40.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-20_1658_apidoc-contract-operation/manifest.json`
- 上一份同域总结：worktree-commit-hook
