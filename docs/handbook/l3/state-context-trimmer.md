---
type: handbook-l3
level: L3
module: agent-infra
title: 上下文裁剪
summary: 按字符预算与消息条数裁剪历史，保留 SYSTEM 且不以 TOOL 开头
---

# ConversationContextTrimmer 裁剪

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 上下文预算裁剪 | `ConversationContextTrimmer#trim` | `platform/agent-infra/src/main/java/top/rongx/agent/infra/state/ConversationContextTrimmer.java:29-86` | agent-infra |
<!-- generated:end -->

## 行为拆解

1. 开头连续的 SYSTEM 消息整体保留，且不计入预算。
2. 其余消息从尾部保留 `keepMessages` 条，并把总量压到 `maxChars` 以内。
3. 裁剪窗口不能以 TOOL 开头：否则会留下没有对应 tool_use 的工具结果，模型会拒绝或误判。
4. 返回新的 `AgentState`（复制 sessionId、权限、工具/任务/计划上下文等字段），不修改入参。

## 状态与副作用

- 纯函数式：没有 I/O，也没有全局状态。
- 与工具侧 `agent.tools.max-output-chars` 形成两层截断：先截单条工具输出，再裁整体窗口。

## 失败与降级

- 预算不足或没有可裁窗口时返回原状态，不做半截裁剪。
- `maxChars <= 0` 视为关闭裁剪。

## 相关测试

`platform/agent-infra/src/test/java/top/rongx/agent/infra/state/ConversationContextTrimmerTest.java`。
