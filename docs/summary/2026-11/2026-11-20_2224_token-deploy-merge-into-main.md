---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T22:30:03+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-20_2224_token-deploy-merge-into-main
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 229
  passRate: "100%"
  lineCoverage: "44.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 合并 token 量化基线与部署矩阵修复到当前分支

## 概述
需把 feat/token-quantified-baseline 与 fix/deploy-matrix-metadata-staple 合并到当前分支 0.1.0-snapshot；合并暴露三处漂移：feat 归档链 prev 断链、gateway ChatService 行号 handbook 锚点过期、capability 路由变更导致 OpenAPI 快照过期
隔离 worktree 基于 d630a8c8 用 --no-ff 合并两分支（ ort 自动合并无冲突）；用 gate.reseal_chain 重排 feat 归档链；build.py sync 刷新 handbook 行号；ApiDocsSnapshotTest -Dapi.docs.update=true 重生成 OpenAPI 快照

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `a533133` | docs | docs(api): 合并后刷新 OpenAPI 快照（capability 路由变更） |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 229, Failures: 0, Errors: 0, Skipped: 5
- 新增用例：0
- 行覆盖率：44.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-20_2224_token-deploy-merge-into-main/manifest.json`
- 上一份同域总结：worktree-commit-hook
