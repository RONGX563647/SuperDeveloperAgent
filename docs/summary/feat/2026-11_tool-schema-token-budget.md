---
canonical: docs/summary/2026-11/2026-11-17_0913_tool-schema-token-budget.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T09:34:02+08:00
extends: session-archive-gate
refs:
  - feat/2026-11-17_0913_tool-schema-token-budget
metrics:
  sessions: 1
  casesAdded: 6
  casesTotal: 531
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 工具 schema token 预算：描述瘦身 + 插件收敛 + 输出分级

## 概述
77 个工具的完整 schema 每轮常驻（约 20-27k token）：中文长描述里塞流程指令、核心工具过多、未归类工具默认可见、输出截断一刀切
描述压到 ≤40 字符英文并把行为约束迁入系统提示；核心工具收敛为 8 个发现类；会话/任务/工作区工具移入 dispatch 插件，未归类工具进 other 插件默认不加载；首轮一次意图路由最多 2 个插件；工具输出预算按工具分级并保留头尾

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `fab91ab` | feat | feat(issues): 工单优先级、任务板与就绪清单改进 |
| `02bb05e` | feat | feat(agent): 工具 schema token 预算：英文短描述 + 插件收敛 + 输出分级 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 531, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：6
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_0913_tool-schema-token-budget/manifest.json`
- 上一份同域总结：session-archive-gate
