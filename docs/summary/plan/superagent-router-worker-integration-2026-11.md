---
canonical: docs/summary/2026-11/2026-11-19_superagent-router-worker-integration.md
schema: session-plan/v1
kind: plan
slug: superagent-router-worker-integration
period: 2026-11
createdAt: 2026-11-19T10:00:00+08:00
updatedAt: 2026-11-19T16:20:00+08:00
status: reviewing
owner: TBD
verdict: null
supersedes: null
supersededBy: null
refs:
  - https://github.com/deepseek-ai/dsh-superagent-prototype
  - https://developer.aliyun.com/article/1779111
  - https://datarekha.com/blog/sub-agents-vs-handoffs-vs-supervisors/
---

# 工作区调度 Agent 升级为「虚拟研发部操作系统」方案（v3）

> 一句话结论：**Super-agent（Router+Worker）是调度层，不是"一个万能 Agent"，更不是替代多 Agent**。
> 本方案把它架在已有的执行层（AgentScope 工具 + CI/部署/环境）之上，落地成一个面向 99 人以下小团队的
> **虚拟研发部**：用户只面对一个入口，背后是 Router 调度 Product/Dev/Test/Ops 四类角色 Worker，
> **Worker 有写工具，但每一处写操作都绑定角色权限 + 环境门禁 + 全链路追踪**。

---

## 0. 定位重述（这一版改了什么）

前两版讲的是"调度层长什么样"，这版回答"业务怎么跑通、谁能写、写了的每一笔怎么追"。

三个不可省的判断：

1. **Super-agent 是调度层，不替代多 Agent**。它解决的是"多个 Agent 同时跑时，谁调度、谁隔离、谁负责上下文、谁兜底"，
   不解决"测试 Agent 会不会写用例、部署 Agent 会不会安全回滚"——这些是执行层的能力，得自己做。
2. **Worker 必须有写工具**，否则虚拟研发部是假的；但写工具的代价是**完整权限 + 追踪体系**，两者缺一不可。
3. **默认策略 = Agent 执行、人工确认；高风险强制审批**。小团队最怕的是"AI 改坏线上"，所以权限模型必须是
   保守默认（fail-closed），而不是"能写就放行"。

最终形态是三层：

```
调度层（本方案新增/增强）  Router：常驻、统一入口、任务分派、上下文、对外回复
执行层（现有，按角色裁剪）  Product / Dev / Test / Ops 四类 Worker，各持写工具
基础设施层（现有，复用）    CI/构建 · 部署/回滚 · 环境/secret · Gitee · 沙箱
```

---

## 1. 业务场景（虚拟研发部）

用户只看到**一个"虚拟研发同事"**（Router 统一入口），不需要理解背后有几个 Agent。
主链路：**需求 → 任务卡 → 改代码 → PR → 测试 → 部署 → 回滚**，可拆成多个 Worker 接力。

| 场景 | 触发事件 | 角色 Worker | 产出 | 门禁 |
|---|---|---|---|---|
| 新需求落地 | 对话/任务卡 | Product → Dev → Test → Ops | 验收标准 + PR + 测试证据 + 部署 | 需求范围需用户确认 |
| Bug 修复 | Gitee issue/评论 | Dev → Test | 补丁 + 回写评论 | `propose_patch` 人工确认 |
| CI 失败自愈 | CI 状态事件 | Dev(诊断) / Ops(环境) | 根因 + 补丁/配置修正 | 修改仍需确认 |
| 发布/回滚 | 对话/合并事件 | Ops | 发布说明 + 部署/回滚 | dev/test 自动，prod 强制审批 |
| 线上问题排查 | 对话/告警 | 只读 Worker(取证) | 结论 + 证据定位 | 只读，无写 |

这些场景不是新造的 Agent，而是把**现有 61 个 `@Tool` 按角色裁剪 + 现有 CI/部署/环境能力编排**起来。

---

## 2. 角色 Worker 定义（带写工具 + 权限 + 验收标准）

核心原则：**不为角色造新工具**，用现有工具按角色裁剪成四个 Worker。每个 Worker = 独立 Session + 独立上下文 +
受限工具面 + 明确验收标准 + 独立追踪。

| 角色 | 职责 | 工具面（复用现有） | 写能力 | 环境权限 | 验收标准 |
|---|---|---|---|---|---|
| **Product** | 需求→任务卡+验收标准 | `workspace_plan` / `task_*` / `comment_git_issue` + 只读查询 | 只写任务卡/工单/评论，**无代码写权限** | 全环境只读+任务卡 | 验收标准可执行、可判定 |
| **Dev** | 改代码、提 PR | `EditorTools` + `propose_patch`（唯一改代码通道）+ git 操作 | 代码走受限补丁→独立分支，**永不 push** | dev/test 可改，prod 只读 | 补丁过测试、PR 可评审 |
| **Test** | 生成用例、跑关键测试 | `ci_run` / `run_action` / `read_run_log` + `propose_patch`(写用例) | 写用例走补丁，跑测试可自动 | dev/test 跑测试，prod 只读 | 关键测试通过、证据留痕 |
| **Ops** | 部署预览、发布说明、回滚 | `DeployTools` + `env_*` + `deploy_run` + `deploy_save_recipe` + 回滚 | 部署/回滚/环境供给 | dev 本机自动；test 自动；**prod 强制审批** | 健康检查 2xx、回滚点留存 |

> 说明：现有 `AgentFactory.createDeploy` 已经是"Ops 单角色"的雏形，`createSupervisor` 是 Router 的雏形；
> 本方案是把"单角色"推广为"四角色 Worker + 常驻 Router"，并统一权限与追踪，而非从零重写。

---

## 3. 权限体系（完整，不是单一"变更单"）

三层叠加，任一不满足即拒绝（fail-closed）：

**① 角色 × 环境 × L 级矩阵**（复用现有 `PolicyPort` + `ToolLevel` L0–L3）

| 环境 | Product | Dev | Test | Ops |
|---|---|---|---|---|
| dev | 只读+任务卡 | L0–L2（改代码需确认） | L0–L2 | L0–L3（本机自动） |
| test | 只读+任务卡 | L0–L2（改代码需确认） | L0–L2 | L0–L3（自动，L3 需确认） |
| prod | 只读 | L0 只读 | L0 只读 | L0–L1 只读；部署/回滚 L3 强制审批 |

**② 写操作门禁分级**（复用现有变更单闭环 `request→confirm→apply` + 一次性令牌）

| 风险级 | 动作 | 门禁 |
|---|---|---|
| 低 | 只读/查询/状态 | 自动 |
| 中 | 改代码（`propose_patch`）、改配置 | 人工确认后应用 |
| 高 | prod 部署/回滚/数据库变更/secret 轮换 | 强制审批，不可自动 |

**③ 默认策略**：**Agent 执行，人工确认；高风险强制审批**。Router 与 Worker 都无特权通道，
  写工具本身拒绝 prod（`DEFAULT` 权限模式），部署要 `binding_id`（人确认的环境绑定）。

---

## 4. 追踪/审计体系（完整）

每个 Worker 的每一次活动都要能回答：**是谁、为什么、改了什么、花了多少、怎么回滚**。

| 追踪维度 | 现有承载 | 本方案增强 |
|---|---|---|
| 输入/输出 | `ConversationRun` + 时间线 | 角色 Worker 标注 + 决策理由摘要 |
| 工具调用序列 | AgentScope 事件 + `WorkspaceEventBus` | 稳定事件枚举（README 已知边界 #5） |
| 文件变更 | `ChangeWire` 变更单 + 受限补丁登记 | 关联到 Worker + 触发事件 |
| 回滚点 | `_platform/deploy/<p>-<env>/releases/<sha>` + `current`/`previous` | 每次部署/回滚写审计行 |
| Token 成本 | `TokenUsageRecorder` + 前端 `UsagePanel` | **按 Worker 维度聚合的成本看板** |
| 运行账本 | `RunLedger`（`RunRecord`） | 补 `workerRole` 字段 + 关联 `threadId` |

新增一表 `audit_trace`（Flyway V4，与 §5 数据模型合并）统一记录：`runId → workerRole → triggerId → toolCalls → tokenCost → changeIds → rollbackPoint`。
审计查询走现有 `/api/v1` 统一错误契约，权限 `settings:reveal` 之上新增只读 `audit:read` 码。

---

## 5. 目标态架构（三层）

```
                 ┌──────────────────────────────────────────────────────────┐
                 │  调度层 Router（常驻，工作区 × 用户一个）                    │
                 │  工具面 = L0 只读 + 派发（无写工具，无特权）                  │
                 │  职责：统一入口 · 事件分类 · 任务分派 · 上下文 · 对外回复      │
                 └───────▲───────────────────────────┬──────────────────────┘
                 Trigger（Gitee/CI/对话）            │ 派发（现有 DAG）
                 Fast Path 规则过滤 → Slow Path 语义   ▼
                 ┌──────────────────────────────────────────────────────────┐
                 │  执行层（四类角色 Worker，独立 Session/上下文）              │
                 │  Product / Dev / Test / Ops，各持裁剪后的写工具            │
                 │  写操作 → 角色权限 × 环境门禁 × 人工确认（§3）               │
                 └──────────────────────────────────────────────────────────┘
                 ┌──────────────────────────────────────────────────────────┐
                 │  基础设施层（复用现有）CI/构建 · 部署回滚 · 环境/secret ·      │
                 │  Gitee · 沙箱 · SQLite/PostgreSQL                          │
                 └──────────────────────────────────────────────────────────┘
                        ▲ 贯穿：Watchdog（超时/死循环/跑偏）+ 审计追踪（§4）
```

### 数据模型（新增最小集）

Flyway **V4__superagent_router_worker.sql**：

- `trigger(id, project_id, source, type, external_ref, dedup_key, thread_id, status, created_at)`
- `thread(id, project_id, subject, status, created_at, updated_at)`
- `workspace_task` 加 `thread_id` 可空列
- `run_record` 加 `worker_role` 可空列（Product/Dev/Test/Ops）
- `audit_trace(id, run_id, worker_role, trigger_id, tool_calls_json, token_cost, change_ids, rollback_point, created_at)`

复用：`Conversation`(Session)、`ConversationRun`(Assignment)、`WorkspaceTask`(Ticket)、`RunLedger`、`ChangeWire`。

---

## 6. 功能需求（每条绑定「场景 + 权限 + 追踪」）

### P0 — 角色 Worker 落地 + 常驻 Router

- **R1 四类角色 Worker**：`AgentFactory` 从"单 deploy 角色"推广为 `createWorker(ctx, Role)`，
  按 §2 裁剪工具面与系统提示，每个角色绑定 §3 权限。**这是写工具存在的唯一形态**。
- **R2 常驻 Router 状态**：工作区（project × user）一个持久化 Supervisor，跨轮记住
  「活跃 Ticket → 归属 Worker(角色) → 状态」；落库不靠内存。
- **R3 Trigger 统一接入 + Fast/Slow Path**：Gitee issue/PR/CI、对话消息收敛为 `trigger`；
  确定性规则先过滤（零 LLM），放行项由 Router 语义判断「建 Ticket / 关联 / 忽略」。

### P1 — 场景编排 + 订阅直通 + 主动模式

- **R4 主链路编排**：需求→任务卡→改代码→PR→测试→部署→回滚，由 Router 把 §1 五类场景
  映射为「角色 Worker 接力」（DAG 依赖 + `depends_on`），每棒产出与证据交给下一棒。
- **R5 Thread 订阅直通**：同一 `thread` 后续事件直接投递到已负责的 Worker（唯一 handoff 处），
  Router 不二次决策。
- **R6 主动模式 off/observe/auto**：默认 `observe`；`auto` 仅对确定性启用规则的事件自动建 Ticket；
  **生产写操作始终人工确认**。

### P1 — 追踪体系

- **R7 审计追踪**：`audit_trace` 记录 §4 六维（谁/为什么/改了什么/花了多少/怎么回滚），
  Worker 维度成本进看板。
- **R8 可观测**：稳定事件枚举替代反射（README 边界 #5），Worker 输入/输出/工具调用/Tx成本单独可查。

### P2 — 有效性监督

- **R9 Watchdog 有效性信号**：现有超时收敛之上，观察「连续无产出 / 重复调用 / 偏离目标」，标记疑似无效，**只观察不自动杀**。

### 贯穿性硬约束

- **R10 安全红线不变**：Router 无写工具、无特权；Worker 写操作过 §3 三层门禁；
  `propose_patch` 仍是改代码唯一通道、永不 push；prod 写操作不可自动。
- **R11 归属不可伪造**：Trigger/Worker 的 `projectId`/`ownerUserId` 由服务端注入。

---

## 7. 落地路径（MVP 优先，逐层解锁写权限）

| 阶段 | 改动 | 交付物 |
|---|---|---|
| M1 底座 | `agent-domain` 加 `Trigger/Thread` 实体+端口；`agent-infra` Flyway V4；`run_record` 加 `worker_role` | 数据模型可迁移 |
| M2 角色 Worker | `agent-runtime` 的 `AgentFactory.createWorker(ctx, Role)`，四角色工具面/提示/权限裁剪；`PolicyPort` 扩展角色维度 | 四角色可构造、权限生效 |
| M3 常驻 Router | `RouterState` + `TriggerRouter`(Fast/Slow) + `ThreadForwarder`；`agent-tools` 加 `thread_*`/`trigger_accept`，`workspace_recall` 走关联链 | 事件→Ticket→派发闭环 |
| M4 追踪 | `audit_trace` 落库 + 成本看板 + 审计查询接口；事件映射稳定化 | 六维追踪可查 |
| M5 前端 | 工作区首页：虚拟研发同事入口 + 角色/任务/成本/审计面板 + 主动模式开关 | 用户只面对一个入口 |

> 安全顺序：**先 M2 的权限裁剪，再给 Worker 解锁写工具**；权限没落地前，写工具保持默认拒绝。

---

## 8. 验收契约（可测）

- 一个需求从任务卡走到部署回滚，全程由 Router 调度四角色 Worker 接力完成，每棒有产出与证据。
- Worker 写操作：dev/test 中风险需人工确认、prod 高风险强制审批；无任何路径绕过门禁。
- 一次 Gitee 事件经 Fast Path 后无关事件零 LLM 调用；放行项落到「建/关联/忽略」三态。
- 任意一次 Worker 活动，可经 `audit_trace` 查回「谁、为什么、改了什么、token 成本、回滚点」。
- `mvn verify` 通过（ArchUnit 断言 Router 工具面无 L1+ 写工具 + Flyway + 现有单测不回归）。
- 主动模式默认 `observe`，`auto` 仅对启用规则生效；prod 写操作不可自动。

---

## 9. 风险与边界（含"研究原型不能当生产基建"）

1. **Super-agent 是研究原型**：只采纳 Router+Worker 架构思想，全部在 AgentScope/Java 栈自建，不引包、不照搬 `danger-full-access`。
2. **Worker 能力要自己做**：调度层不解决"测试会不会写用例、部署会不会安全回滚"——这些靠现有 61 个工具 + 验收标准约束，需持续补工具能力。
3. **成本更高**：Router + 多 Worker + Watchdog + 事件过滤会抬升 token；靠 Fast Path 零 LLM + Spill 上下文裁剪 + 模型路由控制，看板必须可见。
4. **权限与审批不可省**：小团队最怕"AI 改坏线上"，默认「Agent 执行、人工确认」是底线，不为效率破例。
5. **单机事件总线 + 内存 Router 态**：多副本前必须先落库 + PostgreSQL，否则跨实例丢事件。
6. **刻意不做**：跨设备 BYOM、通用企业级多租户、重写 CI/部署基础设施（复用现有）、另造 human-in-the-loop（复用变更单）。
