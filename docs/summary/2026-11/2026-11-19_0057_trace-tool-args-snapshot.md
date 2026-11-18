---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T01:02:54+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0057_trace-tool-args-snapshot
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 142
  passRate: "100%"
  lineCoverage: "39.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 轨迹工具详情补齐入参快照与不可用标记

## 概述
task-da2bbce041（轨迹工具详情参数缺失）：上一轮只在 TOOL_CALL_DELTA 上把入参归一化成 args，插件类工具与 MCP 工具（workspace_*、mcp_*）走的是 tool.started / tool.completed 两条路，因此它们在轨迹里永远空白；「无参工具」与「参数丢失」在界面上长得一样，无法判断是映射漏了还是本来就没参数。
AgentEventMapper 把入参归一化扩展到 tool.started 与 tool.completed，按 args/arguments/input/toolArguments/delta 多字段取第一个非空；稳定契约：有入参给 args、无参 args=null 且 argsUnavailable=false、拿不到入参 args=null 且 argsUnavailable=true；前端 reducer 透传 argsUnavailable，并在已有真实参数时不误标；ToolActivity 类型补注释说明两者区别。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `7bd8900` | feat | feat(trace): 工具轨迹统一 args 快照并标记 argsUnavailable |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 142, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：39.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0057_trace-tool-args-snapshot/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
