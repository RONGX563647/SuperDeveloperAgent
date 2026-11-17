---
canonical: docs/summary/2026-11/2026-11-17_1739_capability-bar-ui.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T17:46:07+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_1739_capability-bar-ui
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 552
  passRate: "100%"
  lineCoverage: "37.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 会话能力栏布局与会话发送回归

## 概述
会话能力栏（Skill / MCP 选择页）可用性差：默认排序把已启用项打散在长列表里，找「这轮开了什么」要反复滚动；两组能力卡片无法折叠，能力多时页面很长；启停操作叫「全选/清空」，没有说明，且一直可点；Skill 描述过长被截断也没有 hover 提示。发送按钮问题已由 dbadfba 修复，本轮只做回归确认。
能力栏：默认排序改为已启用优先（按名称排序保留），排序逻辑抽成 orderCapabilities 纯函数便于测试；Skill/MCP 卡片增加折叠按钮与启用数徽标；一键启停改为「启用全部 / 全部停用」并在无变化时禁用；Skill 描述补 hover 全文提示。发送按钮回归：useAgentChat 以 run.completed/run.failed/run.cancelled 作为终态并调用 finishStream 复位 streaming，本轮未改动该链路。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c6f1cce` | bug | fix(chat): 会话能力栏已启用置顶、分组折叠与一键启停 |
| `603cb80` | chore | test(chat): 覆盖能力栏已启用优先排序 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 552, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：4
- 行覆盖率：37.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_1739_capability-bar-ui/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
