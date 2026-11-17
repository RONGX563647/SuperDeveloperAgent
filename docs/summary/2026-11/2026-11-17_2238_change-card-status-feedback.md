---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T22:59:33+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_2238_change-card-status-feedback
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 585
  passRate: "100%"
  lineCoverage: "38.7%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 变更卡：留痕单不推卡、失败回执与状态查询

## 概述
确认卡链路三处口径不一致：留痕单(env_ensure/deploy_save_recipe/env_mapping_draft)也会弹可执行卡导致 apply 必 400；apply 失败只弹本地 toast，Agent 收不到回执；变更单无状态，Agent 无法查询确认/执行结果
ChangePlan 增加 executable() 与 status(PENDING/CONFIRMED/APPLIED/FAILED) 并持久化，推卡前过滤不可执行变更，确认/执行/失败时更新状态；新增只读工具 change_status；卡片失败 emit failed 由 ChatView 回灌给 Agent（流式期间排队补发）；确认卡改为紧凑布局，diff/日志按需展开

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `eaf56bc` | bug | fix(change): 留痕单不弹确认卡、失败回执回灌并支持查询变更状态 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 585, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：3
- 行覆盖率：38.7%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_2238_change-card-status-feedback/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
