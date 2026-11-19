---
canonical: docs/summary/2026-11/2026-11-19_superagent-router-worker-integration.md
schema: session-plan/v1
kind: plan
slug: superagent-router-worker-integration
period: 2026-11
createdAt: 2026-11-19T10:00:00+08:00
updatedAt: 2026-11-19T10:00:00+08:00
status: reviewing
owner: TBD
verdict: null
supersedes: null
supersededBy: null
refs:
  - https://github.com/deepseek-ai/dsh-superagent-prototype
  - https://developer.aliyun.com/article/1779111
---

# 工作区调度 Agent 接入 DeepSeek Super-agent 架构的功能需求方案

> 研究目标：把 DeepSeek 开源的 Super-agent（`dsh-superagent-prototype`，MIT）架构思想接入本系统
> 现有的「工作区调度 Agent」，让它从"每次对话临时派发一次 DAG 子会话"升级为"持续在线、
> 多任务并发、事件驱动、能主动接活的长期任务协调层"。
> 结论先行：**Super-agent 不是可 `import` 的库，而是一套 Router/Worker 协调架构**；本系统已用
> AgentScope 自建了其中的约六成机制（DAG 派发、异步执行、看门狗、票据持久化）。真正的缺口是
> **常驻 Router、事件接入与过滤、Thread 订阅直通、上下文管理（Inline & Spill / Chain of Recall）、
> 主动模式**这五块。方案是"补缺口"，不是"换框架"。

---

## 1. 研究结论：DeepSeek Super-agent 是什么

`dsh-superagent-prototype`（2026-10-31 发布，基于 DeepSeek Harness，MIT）是一套面向**长期运行、
多任务并发、主动协作**的 Agent 协调架构，目标四要素：

| 目标 | 含义 |
|---|---|
| Continuous | 持续在线接收事件，不靠人逐条派活 |
| Productive | 多任务并发，互不阻塞 |
| Proactive | 主动发现工作（但受确定性规则约束） |
| Persistent | 长期稳定运行，异常可恢复 |

核心是 **Router / Worker 双层结构**：

- **Router**：常驻的全局协调节点，负责接收事件、任务识别、任务分派、对外沟通与结果汇总；**不执行耗时工作**。
- **Worker**：独立 Session + 工具环境，执行具体任务，可并发，必要时内部再起 Subagent。

配套机制（本方案要"串起来"的关键点）：

| 机制 | 作用 |
|---|---|
| Trigger / Thread / Ticket / Assignment / Session | 事件、议题、任务、执行安排、会话五类对象的持久化模型 |
| Fast Path / Slow Path | 确定性规则过滤噪声 → 语义判断是否建任务 |
| Thread Subscription | 同一议题的后续事件直通负责它的 Worker，绕开 Router 重复决策 |
| Inline & Spill | 上下文当高速缓存：热点入上下文，全量落外存 |
| Chain of Recall | Trigger→Thread→Ticket→Session 关联链，按引用逐步回溯历史 |
| 异步执行 | 长任务后台跑，完成后结果回注上下文 |
| Watchdog | 系统外独立监督：既查基础设施，也评估 Worker 是否"有效进展" |

**重要边界（官方明示）**：公开原型是**研究原型，非生产级产品**；`danger-full-access`，Session 隔离
≠ 文件系统/权限隔离；跨设备 BYOM、独立智能 Watchdog 尚未完整公开实现。因此"接入"应理解为
**采纳其架构思想，在本系统已有的安全边界上自建**，而非直接引包。

---

## 2. 现状盘点（本系统已具备，无需重写）

| Super-agent 概念 | 本系统现状 | 位置 |
|---|---|---|
| Worker 独立 Session | 子会话 `Conversation` + `ConversationRun`，按项目/用户隔离 | `ConversationManager` |
| Assignment 调度 | **DAG 并行派发**：就绪即派发、依赖、熔断、重试、超时、提前终止 | `WorkspaceDispatchScheduler` |
| 异步执行 | 每工作区虚拟线程池 + 取消 + SSE 事件流 | `WorkspaceRunExecutor` |
| Watchdog（基础） | 卡死运行超时收敛 + 进程重启兜底 | `WorkspaceRunWatchdog` |
| Ticket | 任务板 `WorkspaceTask` + 派发票据 openTicket/closeTicket | `ConversationManager` |
| 持久化 | 全部落 SQLite（可切 PostgreSQL），重启可恢复 | `agent-infra` |
| 事件通道 | 工作区 SSE 事件总线（内存单实例） | `WorkspaceEventBus` |
| 安全边界 | 环境策略（生产只读）、变更单人工确认、受限补丁、沙箱 | 现有门禁体系 |

结论：**执行面（Worker 怎么跑）已经很强**，缺的是 **协调面（Router 怎么长期在线、事件怎么进来、
上下文怎么管）**。

---

## 3. 概念映射与缺口

| Super-agent 概念 | 映射到本系统 | 现状 |
|---|---|---|
| Router | `workspace-supervisor`（`AgentFactory.createSupervisor`） | 部分：有，但**非长期常驻**，靠人触发 |
| Worker | 子会话（NORMAL kind） | ✅ 已有 |
| Trigger | Gitee webhook / CI 失败 / 对话消息 | 部分：Gitee webhook 目前只触发 `verify`，未转 Ticket |
| Thread | 无对应 | ❌ 缺 |
| Ticket | `WorkspaceTask` / `GitIssue` | 部分：有，但无事件→Ticket 的自动接纳 |
| Assignment | `DispatchNode` / `workspace_dispatch` | ✅ 已有 |
| Fast/Slow Path | 无对应 | ❌ 缺 |
| Thread Subscription | 无对应 | ❌ 缺 |
| Inline & Spill | 单会话状态存储（无外溢分层） | ❌ 缺 |
| Chain of Recall | `workspace_recall`（关键词搜索） | 部分：无对象关联图 |
| Proactive 模式 | 无（完全被动） | ❌ 缺 |
| Watchdog（有效性） | 只做超时收敛 | 部分 |

---

## 4. 功能需求

> 优先级：P0 = 首期必做，P1 = 二期，P2 = 后续/仅观察。

### P0 — 让 Router 变成"常驻协调节点"（Continuous）

- **R1** 每个工作区维护一个**常驻 Router 状态**（与用户/项目绑定），不再"每次对话临时构造、派发完即弃"。
  Router 持有全局任务关系表（有哪些 Ticket、各自状态、归属 Worker），可跨多次用户交互连续决策。
- **R2** Router 支持**异步收尾**：派发 DAG 后不阻塞，Worker 完成通过事件回调把结果回注 Router 上下文
  （复用 `WorkspaceEventBus` 的 `run.status` 事件 + 落库状态），Router 据此更新任务关系。
- **R3** 复用现有 `WorkspaceDispatchScheduler` 作为 Router 的派发执行器（不改），仅把"派发一次即结束"
  改为"派发是 Router 生命周期中的一个动作"。

### P0 — 事件接入 + 快慢路径过滤（Continuous / Proactive 的入口）

- **R4** 统一 **Trigger 模型**：把三类来源收敛为 Trigger（`source`、`type`、`ref`、`payload`、`authScope`）：
  1. Gitee webhook（issue / PR / push / CI 状态）；2. 对话消息（人发）；3. CI 失败事件。
- **R5** **Fast Path**（确定性规则，零 LLM 成本）：按来源白名单、事件类型、授权范围、项目归属、重复事件幂等
  过滤，命中即丢弃。重复事件去重沿用现有 Gitee webhook 的「同 commit 幂等」思路。
- **R6** **Slow Path**（仅对 Fast Path 放行的候选事件）：由 Router 用模型判断"建新 Ticket / 关联已有 Ticket / 忽略"。
  关联判断依赖 R12 的关联图。

### P1 — Thread 与订阅直通（减少重复路由成本）

- **R7** 引入 **Thread**：把同一工作事项的相关事件（原始 issue、后续评论、CI 结果、对话追问）归到同一 Thread。
- **R8** **Thread Subscription**：某 Worker 已负责的 Ticket 后续事件，直接投递给该 Worker 会话（复用
  `conversation_send`），不再每次经 Router 语义判断；仅当 Worker 已结束或归属不明时才回 Router。

### P1 — 上下文管理：Inline & Spill + Chain of Recall（Persistent 的前提）

- **R9** **Inline & Spill**：Router 上下文只保留"当前活跃 Ticket 摘要 + 近期 Thread + 反馈"（Inline）；
  完整历史、低频信息落外存，上下文中只留摘要与引用 id（Spill）。
- **R10** **Chain of Recall 关联图**：持久化 Trigger→Thread→Ticket→Assignment→Session 的外键关系，
  `workspace_recall` 从"关键词全文搜索"升级为"沿关联链回溯"（先命中对象，再沿引用取历史）。
- **R11** 关联图与现有 `WorkspaceTask` / `Conversation` / `ConversationRun` 复用同一存储，新增一张
  `thread` + `trigger` 关联表（Flyway 迁移），不另起炉灶。

### P1 — 主动模式（Proactive，受规则约束）

- **R12** 引入推断式任务接纳模式 `off / observe / auto`（对齐 Super-agent），默认 `observe`：
  - `off`：完全被动；`observe`：评估事件但不自动接活，只给出建议；`auto`：仅满足**确定性启用规则**的事件才自动建 Ticket 并派发。
- **R13** 主动触发**必须有明确边界**：生产环境默认 `off`（或 `observe`），写操作仍需人工确认（沿用现有门禁）。

### P2 — Watchdog 有效性评估（后续观察）

- **R14** 在现有 `WorkspaceRunWatchdog`（超时收敛）之上，增加"进展有效性"信号：连续 N 轮无工具产出 /
  偏离目标 / 重复调用同一工具，标记为疑似无效并提示人工介入。**仅观察，不自动杀**。

### 贯穿性要求（安全边界，不因架构升级而放宽）

- **R15** Worker 仍严格走**环境策略 + 人工确认**：生产只读、写操作变更单确认、受限补丁、沙箱——Super-agent
  官方明确"Session 隔离 ≠ 权限隔离"，本系统的既有门禁必须原样保留，不得为 Router 开特权通道。
- **R16** 模型无法伪造归属：Trigger 的 `projectId`/`ownerUserId` 由服务端注入（对齐现有 `ProjectContext` 注入方式）。

---

## 5. 落地路径（按现有模块，最小改动）

| 步骤 | 改动点 | 依赖需求 |
|---|---|---|
| 1 | `agent-domain`：新增 `Thread` / `Trigger` 实体与 Repository 端口；`Ticket` 关联 `threadId` | R4/R7/R10 |
| 2 | `agent-infra`：Flyway 新增 `thread` / `trigger` 表 + 索引；实现 Repository | R4/R7/R10/R11 |
| 3 | `agent-runtime`：新增 `RouterState`（常驻协调态）+ `TriggerRouter`（Fast/Slow Path）+ `ThreadSubscription` 投递 | R1/R2/R5/R6/R8 |
| 4 | `agent-gateway`：Gitee webhook 在 `verify` 之外，按规则把事件转成 Trigger 入 Router | R4/R5 |
| 5 | `agent-tools`：`workspace_recall` 升级为关联链回溯；新增 `thread_*` / `trigger_accept` 工具；调度 Agent 提示词增补 Router 职责 | R6/R9/R10/R12 |
| 6 | 前端：工作区首页暴露 Router 状态（活跃 Ticket / Thread / 主动模式开关） | R1/R12 |

---

## 6. 验收契约

- 工作区 Router 能跨多轮对话记住并更新"活跃 Ticket → Worker → 状态"关系，Worker 完成后结果能回注，不靠人追问。
- 一条 Gitee issue/PR 事件经 Fast Path（确定性规则）与 Slow Path（语义）后，能正确落到"建 Ticket / 关联 / 忽略"三态之一；无关事件零 LLM 调用。
- 同一 Ticket 的后续事件直接投递给已负责的 Worker，Router 不重复语义决策（可用调用日志验证 Router 未二次路由）。
- Router 上下文在长任务、多 Ticket 场景下不随历史线性膨胀（有 Spill 分层与上限）。
- 主动模式默认 `observe`，`auto` 仅对满足启用规则的事件生效；生产环境写操作仍需人工确认。
- 现有能力不回归：DAG 派发、看门狗、环境策略、变更单确认行为不变。

---

## 7. 风险与边界

1. **Super-agent 是研究原型**：不可直接引包/照搬实现，本方案只采纳其架构思想，落地全部基于现有 AgentScope/Java 栈。
2. **常驻 Router 的成本**：多独立 Session 会抬升 token 与 KV 缓存读取（官方实验 cache-read 23.2M vs 单 Session 6.1M），
   需用 R9 Inline & Spill 与 R6 Fast Path 控制。
3. **单机内存事件总线**：`WorkspaceEventBus` 是单实例内存实现，多副本部署时 Router 常驻态需落到 PostgreSQL
   （沿用现有 `agent.db.url` 切换路径），否则跨实例丢事件。
4. **安全隔离红线**：Router 常驻 + 主动模式会放大误触发面，R15/R16 是硬约束，首期主动模式默认 `observe`。
5. **范围收窄**：本方案不引入跨设备 BYOM、不重写 Worker 执行面（现有 DAG/执行器已足够），避免过度工程。
