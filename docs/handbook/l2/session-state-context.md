---
type: handbook-l2
level: L2
module: agent-infra
title: 会话状态与上下文压缩
summary: 按 (userId, sessionId) 加密落盘会话状态；上下文压缩由 HarnessAgent 的 CompactionMiddleware 在运行期接管
refs:
  - ../l3/state-agent-state-store.md
---

# 会话状态与上下文压缩

## 职责边界

只管两件事：会话状态怎么加密存，压缩职责归谁。不决定模型、工具面与权限，也不碰运行记录、变更单和项目数据。
落盘实现是 `platform/agent-infra/src/main/java/top/rongx/agent/infra/state/EncryptedAgentStateStore.java`，
装配入口是 `AgentStateConfig#agentStateStore`。运行层只依赖 AgentScope 的 `AgentStateStore` 接口，不反向依赖 infra。

上下文压缩**不在本模块**：迁移到官方 HarnessAgent 后，运行期的上下文压缩由 harness 的
`CompactionMiddleware` 接管（结果写回 `AgentState`），落盘层只负责加密，不再做二次裁剪。

## 输入

- `(userId, sessionId, key)`，以及要保存的 `State` 或 `List<? extends State>`。
- 状态根目录 `agent.state.root`；缺省为 `<workspace>/_platform/agent-state`。
- 平台主密钥 `AGENT_SECURITY_MASTER_KEY`，经 `SecretBox` 完成 AES-256-GCM 加解密。

## 输出

- `<state-root>/<userId>/<sessionId>/<key>.state.enc`：单值状态，一行 `Base64(cipher):Base64(iv)`。
- 同目录的 `<key>.list.enc`：列表状态，一行一个元素密文。
- 路径片段只保留 `[A-Za-z0-9._-]`，非法字符替换为 `_`，最长 120 字符，防止路径穿越。
- `get` 返回 `Optional`，`getList` 返回列表；`exists`、`delete`、`listSessionIds` 提供目录级操作。

## 关键状态与流转

1. `EncryptedAgentStateStore#save` 收到状态后直接序列化、加密、写临时文件、原子移动，最后收紧文件与目录权限；不再裁剪上下文。
2. 同一 `(userId, sessionId)` 的读写经进程内 `ReentrantLock` 串行化，避免并发轮次读-改-写互相覆盖；不同会话之间不阻塞。
3. 上下文压缩在 Agent 运行期由 harness `CompactionMiddleware` 完成：动态阈值 `model.contextWindow - reserved(20k)`，
   缺省消息数阈值 50；压缩后写回 `AgentState.context`，随下一次 `save` 一起加密落盘。
4. 单条工具输出仍由工具层 `CachingAgentTool#budget` 按 `agent.tools.max-output-chars` 截断——这是结果产生时的输出预算，
   与 harness 在进模型上下文前的 ToolResultEviction 是两层，各自独立。
5. `userId` 为空视为匿名槽位：`save` 直接忽略，`get/getList/exists/delete/listSessionIds` 返回空，不报错。这是 AgentScope 绑定会话槽位前的探测路径。

## 失败与降级

- 写盘失败抛 `IllegalStateException`（会话状态写入失败），不静默丢状态。
- 读取或解密失败只记 WARN，按"没有状态"返回空；旧版明文 JSON 同样忽略，会话从下一轮重新开始。
- 非 POSIX 文件系统设置权限失败时忽略；Windows 由 `AgentStateConfig#stateDirectory` 用 ACL 收紧到仅属主。
- 会话锁只在单实例内生效；多实例同时写同一会话仍是最后写入者获胜。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| 会话状态加密落盘 | `EncryptedAgentStateStore#save` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 状态读取、删除与会话枚举 | `EncryptedAgentStateStore#get` / `#delete` / `#listSessionIds` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 状态目录装配与权限收紧 | `AgentStateConfig#agentStateStore` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 运行期上下文压缩 | `CompactionMiddleware`（HarnessAgent 内置） | [runtime-agent-loop](runtime-agent-loop.md) |
| 单条工具输出截断 | `CachingAgentTool#budget` | [tool-execution](tool-execution.md) |

## 修改指引

- 改落盘格式、路径或加密：先看 `EncryptedAgentStateStore.java`、`AgentStateConfig.java`、`SecretBox.java`。
  既有测试：`platform/agent-infra/src/test/java/top/rongx/agent/infra/state/EncryptedAgentStateStoreTest.java`、`AgentStateConfigTest.java`。
- 改压缩策略：调 `AgentFactory#baseBuilder` 里的 harness 压缩开关，或 `CompactionConfig`；不要退回落盘层裁剪。
- 改单条工具输出截断：看 `CachingAgentTool#budget` 与 `ToolResultCache#maxOutputChars`，
  既有测试在 `platform/agent-tools/src/test/java/top/rongx/agent/tools/cache/ToolResultCacheTest.java`。
- 改装配链路：`AgentFactory` 构造时把 `stateStore` 交给 `HarnessAgent`；改这里要回归对话续聊与重启恢复。
