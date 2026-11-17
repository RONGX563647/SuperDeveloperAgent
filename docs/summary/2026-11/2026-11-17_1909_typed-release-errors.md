---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T19:12:26+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-17_1909_typed-release-errors
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

# 版本校验改用分组业务异常

## 概述
合并远端异常码迁移时，ReleaseVersion 的解析异常与 ProjectIntakeController 新增分支接口仍抛 IllegalArgumentException，虽未在测试中暴露，但违背「通用异常清零」的契约，后续 ErrorCodeContractTest 会再次拦截。
ReleaseVersion 的负数与格式校验改为 BuildException(BUILD_INVALID_INPUT)，tryParse 同步捕获 BuildException 并更新单测；ProjectIntakeController 创建分支参数校验改为 IntakeException(INTAKE_INVALID_INPUT)。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `eb9e4ff` | chore | refactor(release): 版本解析与分支校验改用分组业务异常 |

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
- 归档：`docs/process/chore/2026-11-17_1909_typed-release-errors/manifest.json`
- 上一份同域总结：worktree-commit-hook
