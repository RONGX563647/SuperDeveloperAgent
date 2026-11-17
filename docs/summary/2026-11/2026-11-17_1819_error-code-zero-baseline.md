---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T18:21:42+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-17_1819_error-code-zero-baseline
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 543
  passRate: "100%"
  lineCoverage: "37.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 异常码全量迁移与零基线门禁

## 概述
生产代码仍有 641 处未带业务错误码的通用 throw，异常码改造不完整
新增 ErrorKind 与各组 INVALID_INPUT/INVALID_STATE/INTERNAL 兜底码；迁移 629 处 throw 与 70 处 Supplier 构造；ApplyException 归并为 PatchApplyException；通用 throw 基线清零，EC5 零容忍

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `4e1e0b9` | feat | feat: 全量迁移通用异常为分组业务异常并清零存量基线 |

## 测试与质量总结
- 命令：`mvn -s /tmp/maven-settings.xml -B verify -Dtest='!DockerDaemonRepairTest,!DeployEnvPrecheckTest,!DeployRecipeFlowTest' -Dsurefire.failIfNoSpecifiedTests=false`
- 结果：PASSED；Tests run: 543, Failures: 0, Errors: 0, Skipped: 4
- 新增用例：1
- 行覆盖率：37.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_1819_error-code-zero-baseline/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
