---
type: handbook-l1
level: L1
module: platform
title: 系统总览
summary: 平台定位、九个模块的职责与依赖方向，以及三条主干链路
---

# 系统总览

## 平台定位

研发运维智能体平台：把"接入项目 → 对话/规划 → 受控执行 → 流水线 → 部署"收敛到同一个工作区。
后端 Java 21 + Spring Boot 4 + AgentScope 2，前端 Vue3 + Element Plus，接入存储用单文件 SQLite。

## 模块地图

| 模块 | 职责 | 内部依赖 |
|---|---|---|
| `agent-domain` | 领域模型与端口：动作、流水线、错误码、治理契约。零框架依赖 | 无 |
| `agent-infra` | 落地实现：Git/worktree、项目接入、SQLite 存储、会话状态加密、配方存储 | domain |
| `agent-governance` | 治理：环境策略矩阵（L0-L3）、变更单、确认令牌 | domain |
| `agent-executor` | 确定性执行面：流水线引擎、动作执行器、部署器、失败修复、运行历史 | domain |
| `agent-tools` | Agent 可调用的工具：部署、动作、环境、只读查询 | domain, governance |
| `agent-runtime` | Agent 运行时：会话、模型解析、工具面组装、事件流 | domain |
| `agent-gateway` | HTTP/SSE 入口、项目上下文解析、事件映射、部署门禁 | 除 domain 外全部 |
| `spec-checks` | 构建期规约检查：架构、禁令、迁移锁、会话归档、Handbook 漂移 | 全部 |
| `web` | 前端：聊天、流水线、项目、设置 | 无（走 HTTP） |

依赖方向是单向的：`domain ← infra / governance / executor / tools ← runtime ← gateway`。
`runtime` 只依赖 `domain` 与 AgentScope 接口，具体存储与密钥由 `infra` 装配，避免运行时反向依赖。

## 三条主干链路

1. **对话**：`ChatController#chat`（SSE）→ `ChatService#stream` → `AgentFactory#createChat` → `ReActAgent.streamEvents`。
   工具面按环境与角色裁剪，写操作在生产被策略拒绝，详见 [执行模型](execution-model.md)。
2. **执行**：`BuiltInPipelineEngine#trigger` 把 `ci/pipeline.yml` 的阶段交给 ActionRunner，
   阶段状态、证据、日志与产物留在运行记录里。
3. **治理**：`EnvPolicyService` 决定动作放行/拒绝/需确认；`DeployGate` 在部署前校验证据并消费确认令牌。

## 阅读路径

- 想改 Agent 行为：先读 [执行模型](execution-model.md)，再进 `l2/runtime-agent-loop.md` 与 `l3/runtime-*`。
- 想改工具/权限：读 `l2/tool-execution.md` 与 `l2/governance-policy.md`。
- 想改流水线或部署：读 `l2/pipeline-stages.md`、`l2/deploy-recipe.md`。
- 想改项目接入：读 `l2/project-intake.md` 与 [数据流](data-flow.md) 的目录约定。
