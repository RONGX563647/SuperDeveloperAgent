---
type: handbook-l2
level: L2
module: agent-infra
title: 会话状态与上下文预算
summary: 按 (userId, sessionId) 加密落盘会话状态，并在落盘前按预算裁剪上下文
refs:
  - ../l3/state-agent-state-store.md
  - ../l3/state-context-trimmer.md
---

# 会话状态与上下文预算

## 职责边界

只管两件事：会话状态怎么存，上下文留多少。不决定模型、工具面与权限，也不碰运行记录、变更单和项目数据。
落盘实现是 `platform/agent-infra/src/main/java/top/rongx/agent/infra/state/EncryptedAgentStateStore.java`，
裁剪实现是同目录的 `ConversationContextTrimmer.java`，装配入口是 `AgentStateConfig#agentStateStore`。
运行层只依赖 AgentScope 的 `AgentStateStore` 接口，不反向依赖 infra。

## 输入

- `(userId, sessionId, key)`，以及要保存的 `State` 或 `List<? extends State>`。
- `AgentState` 里的 `context` 消息列表；每条消息的文本长度按字符计。
- 状态根目录 `agent.state.root`；缺省为 `<workspace>/_platform/agent-state`。
- 上下文预算 `agent.context.max-chars`（`application.yml` 缺省 80000）与 `agent.context.keep-messages`（缺省 24）。
- 平台主密钥 `AGENT_SECURITY_MASTER_KEY`，经 `SecretBox` 完成 AES-256-GCM 加解密。

## 输出

- `<state-root>/<userId>/<sessionId>/<key>.state.enc`：单值状态，一行 `Base64(cipher):Base64(iv)`。
- 同目录的 `<key>.list.enc`：列表状态，一行一个元素密文。
- 路径片段只保留 `[A-Za-z0-9._-]`，非法字符替换为 `_`，最长 120 字符，防止路径穿越。
- `get` 返回 `Optional`，`getList` 返回列表；`exists`、`delete`、`listSessionIds` 提供目录级操作。
- `ConversationContextTrimmer#trim` 返回新的 `AgentState`，不修改运行中的原对象。

## 关键状态与流转

1. `EncryptedAgentStateStore#save` 收到 `AgentState` 后先调用 `ConversationContextTrimmer#trim`，再序列化、加密、写临时文件、原子移动，最后收紧文件与目录权限。
2. 同一 `(userId, sessionId)` 的读写经进程内 `ReentrantLock` 串行化，避免并发轮次读-改-写互相覆盖；不同会话之间不阻塞。
3. 裁剪保留开头的 SYSTEM 消息且不计入预算；预算取 `max(1000, maxChars)`，最少保留 `max(1, keepMessages)` 条。
4. 从尾部保留消息时，窗口不能以 TOOL 开头，否则会留下没有对应 tool_use 的工具结果；必要时继续向前移动，直到窗口合法且总量进入预算。
5. 两层截断分工明确：`CachingAgentTool#budget` 先按 `agent.tools.max-output-chars` 截单条工具输出；本层裁剪在落盘前压缩整段历史。前者管单条，后者管总量。
6. `userId` 为空视为匿名槽位：`save` 直接忽略，`get/getList/exists/delete/listSessionIds` 返回空，不报错。这是 AgentScope 绑定会话槽位前的探测路径。

## 失败与降级

- 写盘失败抛 `IllegalStateException`（会话状态写入失败），不静默丢状态。
- 读取或解密失败只记 WARN，按“没有状态”返回空；旧版明文 JSON 同样忽略，会话从下一轮重新开始。
- 非 POSIX 文件系统设置权限失败时忽略；Windows 由 `AgentStateConfig#stateDirectory` 用 ACL 收紧到仅属主。
- 预算小于等于 0、上下文为空、或裁剪后起点不大于 0 时，原样返回，不做破坏性裁剪。
- 会话锁只在单实例内生效；多实例同时写同一会话仍是最后写入者获胜。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| 会话状态加密落盘 | `EncryptedAgentStateStore#save` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 状态读取、删除与会话枚举 | `EncryptedAgentStateStore#get` / `#delete` / `#listSessionIds` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 状态目录装配与权限收紧 | `AgentStateConfig#agentStateStore` | [state-agent-state-store](../l3/state-agent-state-store.md) |
| 上下文预算裁剪 | `ConversationContextTrimmer#trim` | [state-context-trimmer](../l3/state-context-trimmer.md) |
| 单条工具输出截断 | `CachingAgentTool#budget` | [state-context-trimmer](../l3/state-context-trimmer.md) |

## 修改指引

- 改落盘格式、路径或加密：先看 `EncryptedAgentStateStore.java`、`AgentStateConfig.java`、`SecretBox.java`。
  既有测试：`platform/agent-infra/src/test/java/top/rongx/agent/infra/state/EncryptedAgentStateStoreTest.java`、`AgentStateConfigTest.java`。
- 改裁剪规则：先看 `ConversationContextTrimmer.java`，重点确认 SYSTEM 前缀、TOOL 开头约束与幂等性。
  既有测试：`platform/agent-infra/src/test/java/top/rongx/agent/infra/state/ConversationContextTrimmerTest.java`。
- 改预算取值：改 `platform/agent-gateway/src/main/resources/application.yml` 的 `agent.context.*`；
  `max-chars` 不含 SYSTEM 提示，调参时不要把它当成总上下文上限。
- 改单条工具输出截断：看 `CachingAgentTool#budget` 与 `ToolResultCache#maxOutputChars`，
  既有测试在 `platform/agent-tools/src/test/java/top/rongx/agent/tools/cache/ToolResultCacheTest.java`。
- 改装配链路：`AgentFactory` 构造时把 `stateStore` 交给 `ReActAgent`；改这里要回归对话续聊与重启恢复。
