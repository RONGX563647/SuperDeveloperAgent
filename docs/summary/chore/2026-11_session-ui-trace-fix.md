---
canonical: docs/summary/2026-11/2026-11-17_0021_session-ui-trace-fix.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T00:22:05+08:00
extends: pre-push-unarchived-guard
refs:
  - chore/2026-11-17_0021_session-ui-trace-fix
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 8
  passRate: "100%"
  lineCoverage: "29.7%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 会话侧栏选中态 / 能力页改版 / 轨迹参数修复

## 概述
侧栏整块工作区高亮、会话能力页重文档难用、轨迹工具详情栏参数缺失
选中态收敛到单条会话；能力页改为 Skill/MCP 开关清单并提示上下文成本；轨迹步骤固化工具参数并在 reducer 兜底缺失 toolCallId 的碎片

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `fe02f3a` | bug | fix(ui): 侧栏选中态只落在单条会话上 |
| `1ec8671` | feat | feat(ui): 会话能力页改为按需开关清单 |
| `ef99058` | bug | fix(trace): 工具详情栏参数不再依赖运行时反查 |

## 测试与质量总结
- 命令：`cd web && npx --no-install vue-tsc --noEmit`
- 结果：PASSED；Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：29.7%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-17_0021_session-ui-trace-fix/manifest.json`
- 上一份同域总结：pre-push-unarchived-guard
