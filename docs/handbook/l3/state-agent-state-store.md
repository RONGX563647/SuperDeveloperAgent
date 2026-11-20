---
type: handbook-l3
level: L3
module: agent-infra
title: 会话状态落盘
summary: 按会话加锁加密写入，目录权限收紧到属主；上下文压缩由 harness 在运行期接管
---

# EncryptedAgentStateStore 落盘

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 会话状态加密落盘 | `EncryptedAgentStateStore#save` | `platform/agent-infra/src/main/java/top/rongx/agent/infra/state/EncryptedAgentStateStore.java:67-76` | agent-infra |
<!-- generated:end -->

## 行为拆解

1. 匿名用户或空状态直接跳过，不产生文件。
2. 状态不再落盘前裁剪：运行期压缩由 HarnessAgent 的 `CompactionMiddleware` 写回 `AgentState`，已压缩的结果随状态一起落盘。
3. 序列化成 JSON 后加密，按 `(userId, sessionId)` 目录写入；同一会话用 `ReentrantLock` 串行化。
4. 目录与文件权限收紧到属主（POSIX 700 / Windows ACL 仅属主），启动时由 `AgentStateConfig` 兜底。

## 状态与副作用

- 单一文件保存最新状态；列表状态写另一后缀文件。
- 落盘不对上下文做二次裁剪；压缩结果来自运行期 harness 写回的状态。

## 失败与降级

- 写盘失败向上抛出，由调用方决定是否中断；
- 读取时解密失败/文件损坏按"无状态"处理，会话从头开始而不是崩溃。

## 相关测试

`platform/agent-infra/src/test/java/top/rongx/agent/infra/state/` 下的
`EncryptedAgentStateStoreTest`、`AgentStateConfigTest`。
