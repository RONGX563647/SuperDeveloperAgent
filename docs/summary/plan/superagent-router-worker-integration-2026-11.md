---
canonical: docs/summary/2026-11/2026-11-19_superagent-router-worker-integration.md
schema: session-plan/v1
kind: plan
slug: superagent-router-worker-integration
period: 2026-11
createdAt: 2026-11-19T10:00:00+08:00
updatedAt: 2026-11-19T15:30:00+08:00
status: reviewing
owner: TBD
verdict: null
supersedes: null
supersededBy: null
refs:
  - https://github.com/deepseek-ai/dsh-superagent-prototype
  - https://openai.github.io/openai-agents-python/multi_agent/
  - https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/swarm.html
  - https://docs.langchain.com/oss/python/langchain/multi-agent/index
  - https://github.com/superagent-ai/superagent
  - https://superagi.com/designing-multi-agent-systems/
  - https://datarekha.com/blog/sub-agents-vs-handoffs-vs-supervisors/
---

# 工作区调度 Agent 接入开源 Super-agent 架构方案（v2）

> 一句话结论：**不要引任何包**。本方案从 6 类开源 Super-agent / 多智能体方案里抽象出
> **三种正交拓扑**，选定一种主拓扑，把缺的能力用"最小改动"补进现有工作区调度 Agent，
> 并让每一处设计都**受本项目 L0–L3 治理、变更单闭环、受限补丁、ArchUnit 分层**约束。

---

## 0. 上一版错在哪（先说清，避免重蹈）

上一版是"名词映射"：把 DeepSeek Super-agent 的 Router/Worker/Ticket/Thread 一个个对齐到现有类，
但没有做**架构决策**，也没有回答"本项目的治理模型能不能被这套架构穿透"。结果读起来像翻译，不像方案。

本次的修正是三条：

1. **抽象拓扑，而不是翻译名词**。多智能体系统在 2026 年收敛成三种互不相同的拓扑：Sub-agents、
   Handoffs、Supervisor。三者的失败模式不同，混用会毁掉可调试性（见 §1）。
2. **把项目特性当硬约束，而不是"背景介绍"**。L0–L3 分级、变更单人工确认、受限补丁、ArchUnit 分层
   是方案的地基，每一条功能需求都必须"过一遍"这些约束。
3. **给出唯一决策 + 数据模型 + 落地文件**，而不是功能清单。方案落到"新增哪两张表、改哪个 Bean"。

---

## 1. 开源方案调研（6 类，各取一个可借鉴点）

| 方案 | 拓扑类型 | 核心机制 | 对本项目可借鉴 | 不采纳的部分 |
|---|---|---|---|---|
| **DeepSeek dsh-superagent-prototype** | Supervisor（常驻 Router/Worker） | 事件→Ticket→Worker、Fast/Slow Path、Thread Subscription、Inline&Spill、Watchdog | **事件驱动 + 常驻协调的骨架**，是最接近本项目诉求的参考 | 引包（TS/研究原型）、`danger-full-access` |
| **OpenAI Agents SDK（Swarm 后继）** | Handoff / agents-as-tools | triage→specialist 接力、guardrails、session | **"三选一拓扑"的清晰心智**；handoff=上下文接力不返回 | 引 OpenAI SDK，脱离本项目模型与治理 |
| **Microsoft AutoGen（Swarm 模式）** | Handoff（群聊+接力） | HandoffMessage 决定下一发言者 | 确认 handoff 是"上下文移交"而非"返回父级" | 群聊模型与 DevOps 单线程派单不匹配 |
| **LangChain multi-agent** | Handoff/Skills/Supervisor 三模式 | 用 token 成本对比三模式 | **用 KV 缓存成本选拓扑**（本项目对 token 敏感） | 引 LangChain 运行时 |
| **superagent.sh（SuperAgent）** | Agent-as-API | 把 agent 暴露成 API、RAG、workflow | "统一入口"思想（本项目已由 `/chat` + `/workspaces` 承担） | 偏通用助手，无 DevOps 治理、无分级 |
| **SuperAGI** | 自治多智能体 | 多 agent 编排 + Action Console（关键动作前人工确认） | **关键动作前人工确认**——本项目已有变更单闭环，无需另造 | 自治度过高，无 L0–L3 分级 |

### 1.1 三种拓扑（本方案的地基）

| 拓扑 | 语义 | 控制权 | 适合 |
|---|---|---|---|
| **Sub-agents** | 编排者拆解目标，委派给子智能体，**子智能体把结果返回父级** | 父级回收并汇总 | 并行、可拆解的独立子任务 |
| **Handoffs** | 接力棒：triage 路由到 specialist，**specialist 接管对话，不返回** | 控制权移交 | 需要专员直接面对用户、切换指令集 |
| **Supervisor** | 显式中心路由器，持续收事件、派单、汇总 | 中心常驻 | 多个长期并存、持续到来的任务 |

**关键决策（本项目）**：主拓扑选 **Supervisor + Sub-agents**——现有 `workspace_dispatch`（DAG）就是
Sub-agents，缺的只是给它上面加一个"常驻 Supervisor"。**Handoff 只在唯一一处使用**：同一 Ticket 的
后续事件直通已负责它的 Worker（即 Thread Subscription），其余一律不引入 handoff，避免"混用拓扑"。

---

## 2. 本项目特性（方案必须遵守的硬约束）

这些不是背景，是每条功能需求都要回踩的锚点：

1. **L0–L3 四级工具分级** + 环境策略矩阵（dev/test/prod × L0–L3）。→ Router 必须**只有 L0 只读 + 派发**，
   绝不给写工具（现状已由 `supervisorToolkit` 保证）；Worker 继承环境策略（现状 `AgentFactory` 已按环境装配）。
2. **变更单人工确认闭环**（`request → confirm → apply`，一次性令牌）。→ 这是本项目自带的
   human-in-the-loop，**不再造 Action Console / guardrails**；Worker 的写操作继续走它。
3. **受限补丁 `propose_patch`**。→ Worker 改代码唯一通道，Router 不能绕过。
4. **环境注入 + `secret://` + 部署回滚**。→ 事件接入与主动模式不得引入新的凭据通道。
5. **ArchUnit 分层 + 禁令扫描**（gateway→runtime→tools/governance→domain←executor/infra）。→ 新增代码必须
   落在既有分层内，domain 零框架、executor 无 LLM、tools 无 ProcessBuilder。
6. **SQLite 单机 → PostgreSQL 多副本；Flyway 迁移**。→ 新增表走 Flyway V4，不靠 `ddl-auto`。
7. **单机内存事件总线** `WorkspaceEventBus`。→ 常驻 Router 状态必须落库，不能只存内存（多副本会丢）。

---

## 3. 目标态架构

```
                 ┌──────────────────────────────────────────────────────────┐
                 │  Supervisor / Router（常驻，每个工作区 × 用户）            │
                 │  工具面 = L0 只读 + 派发（现状 supervisorToolkit）          │
                 └───────▲──────────────────────────┬───────────────────────┘
                         │ Trigger（事件）           │ 派发（现有 DAG）
                 ┌───────┴───────┐                  │
   Gitee webhook │  CI 状态      │  对话消息          ▼
                 └───────┬───────┘         ┌─────────────────────────────────┐
                         │ Fast Path       │ Worker 子会话（Sub-agents）      │
                    确定性规则过滤           │ 继承环境策略 + 变更单 + 受限补丁   │
                     （零 LLM 成本）         │ 由 WorkspaceDispatchScheduler 跑 │
                         │                 └─────────────────────────────────┘
                         │ Slow Path（仅放行项，模型判断）
                         ▼
                 建 Ticket / 关联已有 Ticket / 忽略  ──► 回写 Thread 关联
```

### 3.1 数据模型（复用为主，仅新增两张表）

| 对象 | 现有承载 | 变更 |
|---|---|---|
| Trigger（事件） | 无 | **新增 `trigger` 表** |
| Thread（议题） | 无 | **新增 `thread` 表** |
| Ticket（任务） | `WorkspaceTask` / `GitIssue` | 加 `thread_id` 列（可空） |
| Assignment（执行） | `DispatchNode` / `ConversationRun` | 复用，不改 |
| Session（会话） | `Conversation` | 复用，不改 |
| Router 状态 | 无 | 复用 `Conversation`(SUPERVISOR) + 新增关联表，见 §5 |

Flyway 新增 **V4__superagent_thread_trigger.sql**：

- `thread(id, project_id, subject, status, created_at, updated_at)`
- `trigger(id, project_id, source, type, external_ref, dedup_key, thread_id, ticket_id, status, created_at)`
- `workspace_task` 加 `thread_id` 可空列（或 `trigger` 表里挂 `ticket_id`，二选一，见 §5 步骤 1）

`trigger.dedup_key` 承接现有 Gitee webhook 的「同 commit 幂等」思路，保证重复事件不重复建 Ticket。

---

## 4. 功能需求（每条绑定项目约束）

### P0 — 常驻 Supervisor + 事件接入（Continuous 的入口）

- **R1 常驻 Router 状态**：每个工作区（project × user）一个持久化的 Supervisor 状态，跨多轮交互记住
  「活跃 Ticket → 归属 Worker → 状态」。落在 `Conversation`(SUPERVISOR) + `workspace_task.thread_id`，
  **不依赖内存**（约束 7）。Router 工具面**保持 L0 只读 + 派发**，绝不下放写工具（约束 1）。
- **R2 统一 Trigger 模型**：Gitee webhook、CI 状态、对话消息三类来源收敛为 `trigger` 记录（约束 3/4：
  事件载荷不进命令、secret 只以 `secret://` 引用）。
- **R3 Fast Path（确定性过滤，零 LLM）**：来源白名单、事件类型、授权范围、`dedup_key` 幂等、项目归属，
  命中即丢弃。承接现有 Gitee webhook 幂等逻辑（约束 1：Router 是 L0，过滤不产生任何写副作用）。
- **R4 Slow Path（仅放行项）**：Router 用模型判断「建 Ticket / 关联已有 / 忽略」。**建 Ticket 不直接执行**，
  写操作仍走子会话 + 变更单（约束 2/3）。

### P1 — Thread 直通（唯一用 Handoff 的地方）+ 上下文管理 + 主动模式

- **R5 Thread Subscription（Handoff）**：同一 `thread` 的后续事件，若已有 Worker 负责，**直接投递给该
  Worker 会话**（复用 `conversation_send`），Router 不二次语义判断。仅当 Worker 已结束或归属不明才回 Router。
  这是全方案**唯一**引入 handoff 语义处（约束：避免混用拓扑）。
- **R6 Inline & Spill**：Router 上下文只放「活跃 Ticket 摘要 + 近期 Thread + 反馈」，全量落库、上下文中只留
  引用 id。缓解多独立 Session 抬升的 KV 缓存成本（§1 LangChain 的成本视角）。
- **R7 Chain of Recall 关联链**：`workspace_recall` 从关键词搜索升级为沿 `trigger→thread→ticket→run→session`
  关联链回溯（复用 §3.1 的关联表，不新起检索系统）。
- **R8 主动模式 off/observe/auto**：默认 `observe`。`auto` 仅对满足**确定性启用规则**的事件自动建 Ticket
  并派发；**生产环境写操作仍需人工确认**（约束 2）。`observe` 只给建议不接活。

### P2 — 有效性监督（后续观察）

- **R9 Watchdog 有效性信号**：在现有 `WorkspaceRunWatchdog`（超时收敛）之上，观察「连续 N 轮无工具产出 /
  重复调用同一工具 / 偏离目标」，标记疑似无效并提示人工。**只观察，不自动杀**。

### 贯穿性要求

- **R10 安全红线不变**：Router 无写工具；Worker 严格环境策略 + 变更单 + 受限补丁（约束 1/2/3）。Super-agent
  官方明示"Session 隔离 ≠ 权限隔离"，本方案**不为 Router 开任何特权通道**。
- **R11 归属不可伪造**：Trigger 的 `projectId`/`ownerUserId` 由服务端注入（对齐现有 `ProjectContext`）。

---

## 5. 落地路径（具体到文件 + 分层合规）

| 步骤 | 改动 | 分层合规 |
|---|---|---|
| 1 | `agent-domain`：新增 `Trigger`/`Thread` 实体 + `TriggerRepository`/`ThreadRepository` 端口；`WorkspaceTask` 加 `threadId` | domain 零框架 ✅ |
| 2 | `agent-infra`：Flyway **V4** 建 `trigger`/`thread` 表 + 索引；SQLite/PostgreSQL 双实现 | infra 持久化 ✅ |
| 3 | `agent-runtime`：新增 `RouterState`（常驻协调态，落库）+ `TriggerRouter`（Fast/Slow Path 分两阶段）+ `ThreadForwarder`（R5 直通） | runtime 编排层 ✅ |
| 4 | `agent-gateway`：Gitee webhook 在 `verify` 之外按规则把 issue/PR/push/CI 事件转成 `trigger` 入库 | gateway 入口 ✅ |
| 5 | `agent-tools`：`workspace_recall` 走关联链；新增 `thread_list`/`trigger_accept` 工具；`supervisorSystemPrompt` 增补 Router 职责段 | tools 无 ProcessBuilder ✅ |
| 6 | 前端：工作区首页暴露 Router 状态面板 + 主动模式开关（`observe`/`off`/`auto`） | Vue3 组件 ✅ |
| 7 | `spec-checks`：补一条 ArchUnit 断言"Router 工具面不含任何 L1+ 写工具"（防回退） | 规约检查 ✅ |

> 步骤 3/4/5 是核心，1/2 是数据底座，6/7 是可见性与护栏。

---

## 6. 验收契约（可测，回踩项目约束）

- 同一工作区跨多轮对话，Router 能持续更新「活跃 Ticket → Worker → 状态」，Worker 完成后结果回注，**重启后状态不丢**（落库，非内存）。
- 一条 Gitee 事件经 Fast Path 后，无关事件**零 LLM 调用**（用调用日志验证）；放行项经 Slow Path 落到「建/关联/忽略」三态之一。
- 同一 `thread` 后续事件直通已负责的 Worker，Router 不二次路由（调用日志验证无重复语义决策）。
- Router 上下文在多 Ticket 长任务下不随历史线性膨胀（有 Spill 上限）。
- `mvn verify` 通过（含新增 ArchUnit 断言 + Flyway 迁移 + 现有单测不回归）。
- 主动模式默认 `observe`；`auto` 仅对启用规则生效；**生产写操作仍必须人工确认**（约束 2 不破）。

---

## 7. 风险与边界

1. **多独立 Session 抬升 token/KV 成本**：常驻 Router 会放大缓存读取，靠 R3 Fast Path（零 LLM 过滤）+ R6 Spill 控制；不做则成本失控。
2. **单机事件总线 + 内存 Router 态**：多副本部署必须走 PostgreSQL（`agent.db.url`），Router 状态与事件都得落库，否则跨实例丢事件。
3. **主动模式的误触发面**：`auto` 会放大风险，首期**默认 `observe`**，且生产环境写操作仍人工确认。
4. **拓扑纪律**：全方案只在 R5 用 handoff，其余一律 Supervisor+Sub-agents；新增能力若想"顺手加个 handoff"要显式评审。
5. **范围收窄（刻意不做）**：不引入跨设备 BYOM、不重写 Worker 执行面（现有 DAG/执行器已足够）、不另造 human-in-the-loop（复用变更单）。
