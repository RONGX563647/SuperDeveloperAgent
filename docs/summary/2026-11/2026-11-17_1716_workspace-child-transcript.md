---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T17:21:34+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_1716_workspace-child-transcript
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 548
  passRate: "100%"
  lineCoverage: "37.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 工作区子会话消息持久化与派发去重修复

## 概述
工作区子会话（调度 Agent 派发的会话）运行结束后没有消息记录：WorkspaceRunExecutor 只累积 run.result，时间线仅由浏览器侧 saveConversationTranscript 写入，子会话没有前端页面所以永远空白；同时 ConversationManager.send 用 goalKey 跨会话去重，不同会话的相同目标会复用旧 runId，导致派发的任务被静默吞掉；调度会话时间线只靠防抖写入，页面隐藏或关闭时最后一段输出丢失。
WorkspaceRunExecutor.finish 在运行终态时读取已有时间线并追加本轮 user/assistant 消息（结构同前端 messages+tools，保留最近 200 条，写入失败只记日志）；send 的去重改为按会话隔离，同会话相同目标返回明确 err 文案，不同会话不再互相复用运行；workspaceChat 增加 pagehide/visibilitychange 立即落盘（本地+服务端 keepalive），并把 projection watcher 挂到独立 effectScope，避免离开工作区页面后不再落盘。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `eea143c` | bug | fix(workspace): 子会话运行结束自动落时间线，派发去重按会话隔离 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 548, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：37.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_1716_workspace-child-transcript/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
