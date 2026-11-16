---
type: handbook-l3
level: L3
module: agent-runtime
title: Agent 装配与工具面
summary: 按环境与角色裁剪工具面、权限模式与迭代上限，构造 ReActAgent
---

# AgentFactory 装配

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 按环境与角色裁剪工具面并构造 ReActAgent | `AgentFactory#createChat` | `platform/agent-runtime/src/main/java/top/rongx/agent/runtime/AgentFactory.java:133-165` | agent-runtime |
<!-- generated:end -->

## 行为拆解

1. `PolicyPort#decide(env, L2_EXECUTE_ISOLATED)` 取环境裁决；`DENY` 或 `ctx.readOnly()` 判为只读会话。
2. 只读会话用 `DEFAULT` 权限 + 只读工具面；非只读会话用 `BYPASS`。
   非生产不用 `ACCEPT_EDITS`：对话链路没有"用户已确认"的回传通道，会卡在 ASK。
3. 工具面先 `copy()` 再挂载，避免本会话的 MCP 工具污染共享单例。
4. 非只读会话才挂 MCP；只读会话一律不挂（MCP 工具没有 readOnly 元数据）。
5. 按设置页已启用集合取交集挂载 Skill；最后设置系统提示、模型、`maxIters` 与状态存储。

## 状态与副作用

- 返回 `AgentSession(agent, closeables)`，关闭时释放 MCP 客户端。
- 不修改共享 `Toolkit`，也不写会话状态。

## 失败与降级

- 单个 MCP 服务初始化失败只降级跳过，不阻断对话。
- 请求要启用的 Skill/MCP 超过管理员启用集合时，以管理员集合为上限。

## 相关测试

工具面与权限的回归在 `platform/agent-gateway/src/test/.../ToolChainTest`、
`ActionUsabilityTest`；状态存储装配见 `AgentStateConfigTest`。
