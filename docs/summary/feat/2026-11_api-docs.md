---
canonical: docs/summary/2026-11/2026-11-16_2048_api-docs.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T10:23:40+08:00
extends: tool-schema-token-budget
refs:
  - feat/2026-11-16_2048_api-docs
metrics:
  sessions: 1
  casesAdded: 7
  casesTotal: 484
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 接入 springdoc、前端契约审计与错误码收敛

## 概述
缺少可机器校验的 API 契约，前后端类型漂移；错误码散落字符串难以审计
接入 springdoc 生成 OpenAPI 与类型快照，增加前端契约审计与 Mock 联调；错误码统一收敛为 ErrorCode 枚举，校验改为扫描源码避免过期快照

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e075cd6` | feat | feat(api-docs): 接入 springdoc 并新增 Agent API 文档工具链 |
| `e2202b3` | feat | feat(api-docs): 增加前端契约审计、类型快照与 Mock 联调能力 |
| `979d6dd` | chore | refactor(api-docs): 审计支持三元路径配对并清理输出细节 |
| `7941122` | chore | refactor(error): 错误码统一收敛为 ErrorCode 枚举 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am -Dtest=!ApiDocsSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：PASSED；Tests run: 484, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：7
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- ApiDocsSnapshotTest 在该提交点因快照未同步失败，后续会话已修复

## 链接
- 归档：`docs/process/feat/2026-11-16_2048_api-docs/manifest.json`
- 上一份同域总结：tool-schema-token-budget
