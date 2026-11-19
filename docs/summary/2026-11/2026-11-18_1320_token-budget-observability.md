---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T13:31:46+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_1320_token-budget-observability
metrics:
  sessions: 1
  casesAdded: 9
  casesTotal: 626
  passRate: "100%"
  lineCoverage: "39.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# Token 治理批次1：KV 命中率可观测 + 历史折叠摘要

## 概述
token 治理的前两层（渐进工具披露、工具输出限流、上下文预算）已落地，但后端没有统一的 KV 缓存命中率口径；上下文裁剪直接丢弃旧历史，runId/changeId 等关键标识随之消失，长会话追问需要重新取证；且 TOOL 消息正文不在 getTextContent() 里，导致工具输出完全不计入上下文预算。
1) UsageTotals.cacheHitRate() 统一命中率算法，TokenUsageRecorder 日志追加 hit=xx.x%，UsageController.session() 返回 cacheHitRate；2) ConversationContextTrimmer 新增 4 参重载，把被裁掉的旧历史折叠成一条 synthetic 摘要消息，关键标识单独汇总在头部且最多 12 个，summary-chars=0 可回退旧行为；3) textOf() 递归读取 ToolResultBlock.output，使工具结果真正计入上下文预算。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `b95f342` | feat | feat(token): 补齐 KV 命中率口径并把上下文裁剪升级为折叠摘要 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test -Dtest=!DualStackPipelineTest,!IntakeE2ETest,!PipelineAcceptanceTest,!ChangeRegistryTest,!ConfirmTokenServiceTest,!PatchRegistryTest,!ProcessArchiveTest -Dsurefire.failIfNoSpecifiedTests=false`
- 结果：PASSED；Tests run: 626, Failures: 0, Errors: 0, Skipped: 1
- 新增用例：9
- 行覆盖率：39.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_1320_token-budget-observability/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
