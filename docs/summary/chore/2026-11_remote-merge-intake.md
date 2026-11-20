---
canonical: docs/summary/2026-11/2026-11-20_1251_remote-merge-intake.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T12:52:52+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-20_1251_remote-merge-intake
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 214
  passRate: "100%"
  lineCoverage: "42.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 收录远端合并进来的流水线权限/远程部署修复提交

## 概述
rebase 收录远端 12 个未归档代码提交（fd01545..25c7716：流水线权限逻辑修复、远程部署矩阵并行探测、部署版本槽覆盖修复）；同时 handbook 锚点漂移与 DeploymentControlService 通用异常被 spec-checks 拦下
handbook build.py sync；DeploymentControlService IllegalStateException 改 DeployException(E-591)；本归档收录远端合并提交（merge intake）

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `fd2636d` | chore | chore: handbook sync（executor-pipeline-engine 锚点）；DeploymentControlService 通用异常改 DeployException(E-591) |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 214, Failures: 0, Errors: 0, Skipped: 5
- 新增用例：0
- 行覆盖率：42.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-20_1251_remote-merge-intake/manifest.json`
- 上一份同域总结：worktree-commit-hook
