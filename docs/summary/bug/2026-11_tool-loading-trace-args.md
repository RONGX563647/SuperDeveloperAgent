---
canonical: docs/summary/2026-11/2026-11-17_1721_tool-loading-trace-args.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T17:27:38+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_1721_tool-loading-trace-args
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 550
  passRate: "100%"
  lineCoverage: "37.7%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 插件工具加载提示与轨迹参数写入修复

## 概述
插件工具（工单写入类 create/update/comment/close_git_issue 等）未加载时模型直接表示做不到，用户看到的是无法操作工单：tool_search 结果只给工具名不给插件名，模型不知道要 tool_load；系统提示缺少「缺能力先加载，不许让用户手工做」的硬规则；issue 插件关键词也没覆盖 关闭/评论/优先级/合并 等意图。轨迹详情栏参数空白或状态误判：工具入参增量字段上游是 delta，前端只认 delta/arguments/content；tool.completed 缺少 state 时被当成失败标红。
tool_search 返回每个命中工具的插件名并直接给出 tool_load 指令；系统提示新增硬规则：所需能力不在工具列表时禁止推脱，必须先 tool_search/tool_plugins 再 tool_load（举例工单写入需加载 issue 插件）；issue 插件补充 关闭/合并/评论/优先级/指派/标签/创建工单 关键词。AgentEventMapper 把 TOOL_CALL_DELTA 的参数统一写成 payload.args（兼容 delta/arguments）；前端 reducer 按 args、delta、arguments、text、content 的顺序累积入参，tool.completed 用终态入参兜底，并且只有 state 为 error/denied/interrupted 才判失败（缺省与 running 视为成功，失败仍由 Error 前缀兜底）。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `42fe028` | bug | fix(agent): 强制先加载插件工具，轨迹入参统一为 args 并修正工具状态判定 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 550, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：37.7%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_1721_tool-loading-trace-args/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
