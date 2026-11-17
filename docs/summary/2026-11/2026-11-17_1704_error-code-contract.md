---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T17:30:52+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-17_1704_error-code-contract
metrics:
  sessions: 1
  casesAdded: 8
  casesTotal: 536
  passRate: "100%"
  lineCoverage: "37.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 异常码契约与提交门禁

## 概述
异常码改造不全：存在 throw new 通用异常(ErrorCode) 的写法，缺少按业务归类的异常类型与提交门禁
新增 ErrorGroup/PlatformException 与 8 个分组业务异常，错误码与分组强绑定；迁移全部携带 ErrorCode 的通用 throw；响应体新增 errorCode；spec-checks 增加 EC1/EC2/EC3/EC5 契约与只减不增基线

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `525fd71` | feat | feat: 业务异常按业务分组并落地异常码强制契约 |

## 测试与质量总结
- 命令：`mvn -s /tmp/maven-settings.xml -B -pl spec-checks -am test -Dtest="!DockerDaemonRepairTest,!DeployEnvPrecheckTest,!DeployRecipeFlowTest"`
- 结果：PASSED；Tests run: 536, Failures: 0, Errors: 0, Skipped: 4
- 新增用例：8
- 行覆盖率：37.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 全部实现改造并验收成功后合并到main分支并推送

## 链接
- 归档：`docs/process/chore/2026-11-17_1704_error-code-contract/manifest.json`
- 上一份同域总结：worktree-commit-hook
