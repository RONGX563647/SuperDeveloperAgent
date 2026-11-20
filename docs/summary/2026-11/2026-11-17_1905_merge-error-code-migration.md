---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T19:08:10+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-17_1905_merge-error-code-migration
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 572
  passRate: "100%"
  lineCoverage: "38.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 合并远端异常码迁移并适配版本发布改动

## 概述
推送前发现远端 main 新增「通用异常全量迁移」提交，与本地的版本发布改动在流水线引擎、PipelineController、Handbook 与异常码基线四处冲突；若直接取一侧会丢失远端迁移成果或让新代码重新引入通用异常。
合并 origin/main 并逐处适配：新增的版本/分支/标签校验全部改用 BuildException(BUILD_INVALID_INPUT) 与 RecipeException(RECIPE_INVALID_INPUT)，ProjectIntakeController 新接口改用 IntakeException；ReleaseVersion 的解析异常改为 BuildException 并同步单测；异常码基线保持远端清零状态，Handbook 重新 sync 生成；归档链按 createdAt 顺序重接到远端新档案后重新计算 self。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `4e1e0b9` | feat | feat: 全量迁移通用异常为分组业务异常并清零存量基线 |
| `8b3dc1b` | docs | docs: 归档异常码全量迁移会话 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 572, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：0
- 行覆盖率：38.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-17_1905_merge-error-code-migration/manifest.json`
- 上一份同域总结：worktree-commit-hook
