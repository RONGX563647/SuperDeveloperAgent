---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T23:23:47+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_2222_task-version-select-by-kind
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 234
  passRate: "100%"
  lineCoverage: "44.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 任务版本号选择：建议值进下拉并按类型给目标版本

## 概述
任务版本号下拉里建议值与任务类型（Feat/Bug）不匹配：选 Feat 却建议补丁版本，且可能建议已打过 tag 的版本。
suggestVersion 增加 kind 口径：Feat 取该 major 已发布最高次版本+1（跨过已发布版本），Bug 维持当前版本线最大补丁+1；GET /versions 透传 kind，OpenAPI 快照与前端类型同步；建议值放进下拉首项标「推荐」，类型切换联动刷新回填。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `864e974` | bug | fix(issues): 任务版本号选择：建议值进下拉并按类型给目标版本 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 234, Failures: 0, Errors: 0, Skipped: 5
- 新增用例：5
- 行覆盖率：44.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_2222_task-version-select-by-kind/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
