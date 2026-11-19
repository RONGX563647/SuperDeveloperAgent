---
canonical: docs/summary/2026-11/2026-11-19_superagent-router-worker-integration.md
schema: session-plan/v1
kind: plan
slug: superagent-router-worker-integration
period: 2026-11
createdAt: 2026-11-19T10:00:00+08:00
updatedAt: 2026-11-19T17:00:00+08:00
status: reviewing
owner: TBD
verdict: null
supersedes: null
supersededBy: null
refs:
  - https://github.com/deepseek-ai/dsh-superagent-prototype
  - https://developer.aliyun.com/article/1779111
---

# 工作区调度 Agent 的最小增量方案（v4）

> 一句话结论：本项目**已经具备**"子 Agent 调度 + 写工具 + 权限门禁 + 追踪"的绝大部分能力，
> 缺的只有三件事——**① 角色 Worker 的统一抽象、② 事件→任务的自动接纳、③ 追踪补两个维度**。
> 本方案只补这三个点，不推翻重来、不搬研究原型的概念。

---

## 0. 自我诊断：前几版错在哪

| 问题 | 表现 | 后果 |
|---|---|---|
| **把研究原型概念当需求** | 搬进 Thread 订阅、Inline & Spill、Chain of Recall、off/observe/auto 主动模式 | 方案膨胀，却无一条论证"对 99 人小团队是刚需" |
| **没做真 gap 分析** | 把 L0–L3、变更单、受限补丁、RunLedger、部署历史、Token 统计当成"要新增" | 重复建设，方案与实际代码脱节 |
| **背离最小 diff 原则** | 四类独立 Worker、三张新表、审计接口、成本看板越堆越多 | 变成大蓝图，不是可落地的增量 |

修正原则（回到仓库的 lazy-senior-dev 纪律）：**先盘点已有，再只补缺口；角色是"配置"不是"新类"；一张表能解决的不建三张。**

---

## 1. 现状盘点（已有，不要再造）

| 诉求 | 已有实现 | 位置 |
|---|---|---|
| 子 Agent 调度 | DAG 并行派发（依赖/熔断/重试/超时） | `WorkspaceDispatchScheduler` |
| 异步执行 + 取消 | 每工作区虚拟线程池 + SSE 事件流 | `WorkspaceRunExecutor` |
| 兜底监督 | 卡死运行超时收敛 + 重启恢复 | `WorkspaceRunWatchdog` |
| 写工具 | 61 个 `@Tool`：`propose_patch`/`deploy_run`/`ci_run`/`env_*`… | `agent-tools` |
| 权限分级 | L0–L3 + 环境策略矩阵 + 只读角色 | `PolicyPort` / `ToolLevel` |
| 人工确认 | 变更单 `request→confirm→apply` + 部署 `binding_id` | 门禁体系 |
| 改代码唯一通道 | `propose_patch` → 独立分支 → 永不 push | 受限补丁 |
| 追踪 | `RunLedger`(run 级) + `ChangeWire`(文件变更) + 部署历史(回滚点) | `agent-infra` |
| 成本 | `TokenUsageRecorder` + 前端 `UsagePanel` | 已有 |
| 事件入口 | Gitee webhook（OAuth/PAT + 幂等） | `agent-gateway` |

结论：**"写工具 + 权限 + 追踪"不是要新建，而是要补两个维度、加一个入口。**

---

## 2. 真正的缺口（只有 3 个）

- **G1 角色 Worker 没有统一抽象**：现在 `createChat`(通用)、`createDeploy`(部署)、`createSupervisor`(调度)
  是三个散的方法。要加 Product/Test 角色就得再复制一份。缺一个 `createWorker(ctx, WorkerRole)`，
  让"角色"成为**一个枚举 + 一份裁剪配置**，而不是一个新类。

- **G2 事件不会自动变成任务**：Gitee webhook 目前只触发 `verify`（构建校验），不会把 issue/PR/CI
  事件转成 Ticket 交给调度 Agent。这是"持续在线、事件驱动"的唯一真实缺口。

- **G3 追踪缺"角色"和"触发事件"两个维度**：现有 `RunLedger` 只能回答"某次运行干了什么、花了多少"，
  回答不了"是哪个角色干的、因哪个事件触发的"。补两个字段即可，不需要独立审计表。

---

## 3. 最小增量方案

### 3.1 角色 Worker = 一个枚举 + 一份裁剪配置（补 G1）

`agent-domain` 新增枚举 `WorkerRole { PRODUCT, DEV, TEST, OPS }`；`agent-runtime` 新增
`AgentFactory.createWorker(ctx, role)`，内部就是一张**角色→(工具面, 系统提示, 权限模式)** 的映射，
复用现有的 `createChat`/`createDeploy` 逻辑，不复制代码。

| 角色 | 工具面（复用现有） | 写能力 | 权限 |
|---|---|---|---|
| PRODUCT | `workspace_plan`/`task_*`/`comment_git_issue` + 只读 | 只写任务卡/工单 | 全环境只读+任务卡 |
| DEV | `EditorTools` + `propose_patch` + git | 改代码走补丁，永不 push | dev/test 可改，prod 只读 |
| TEST | `ci_run`/`run_action`/`read_run_log` + `propose_patch`(用例) | 跑测试、写用例走补丁 | dev/test 跑，prod 只读 |
| OPS | `DeployTools` + `env_*` + `deploy_run` + 回滚 | 部署/回滚/环境 | dev 本机自动；test 自动；**prod 强制审批** |

`createDeploy` 收敛为 `createWorker(ctx, OPS)` 的特例，`createSupervisor` 仍是 Router（无写工具、L0 只读+派发）。

### 3.2 事件→任务：复用现有 webhook 入口（补 G2）

不改动现有 Gitee webhook 的 `verify` 逻辑，**新增一条旁路**：

1. issue/PR/CI 事件到达 → **Fast Path**（确定性规则：来源白名单 + 事件类型 + `dedup_key` 幂等）过滤，
   无关事件零 LLM 调用；
2. 放行项 → **Slow Path**（调度 Agent 语义判断「建 Ticket / 关联已有 / 忽略」）；
3. 建 Ticket = 复用现有 `WorkspaceTask`，加一个 `source_ref` 列记录事件来源；派发复用现有 `workspace_dispatch`。

数据底座：`workspace_task` 加 `source_ref` 可空列 + `trigger_type`；`run_record` 加 `worker_role` + `trigger_ref` 可空列。
**不建 `trigger`/`thread`/`audit_trace` 三张表**——事件用 `source_ref` 追溯、议题用 Ticket 自身承载、审计用现有 `RunLedger`。

### 3.3 追踪补两个维度（补 G3）

`RunLedger` 的 `RunRecord` 加 `workerRole`、`triggerRef` 两个字段；`WorkspaceRunExecutor` 派发时写入。
前端 `UsagePanel` 按 `workerRole` 分组即可得到"角色成本"，无需新看板系统。

---

## 4. 明确不做的事（从 v3 删掉）

- ❌ 四类独立 Worker 类 → ✅ 一个枚举 + 一份配置
- ❌ `trigger`/`thread`/`audit_trace` 三张表 → ✅ 两个列 + 复用 `WorkspaceTask`/`RunLedger`
- ❌ Thread 订阅直通、Inline & Spill、Chain of Recall → 这些是研究原型概念，99 人小团队 MVP 不需要；
  后续若真有"同一议题持续追问"的痛点，再在 `WorkspaceTask` 上加 `thread_id` 列，属增量扩展
- ❌ off/observe/auto 主动模式 → 默认就是"被动+事件驱动"（webhook 触发），主动接活属高风险，先不做
- ❌ 独立审计查询接口/成本看板 → 复用现有 `/run-ledger` + `UsagePanel` 分组
- ❌ 跨设备 BYOM、通用多租户、重写 CI/部署基础设施

---

## 5. 落地路径（最小 diff，安全顺序）

| 步 | 改动 | 新增代码量 |
|---|---|---|
| 1 | `agent-domain`：`WorkerRole` 枚举；`RunRecord`/`WorkspaceTask` 加字段 | 一个枚举 + 两处字段 |
| 2 | `agent-infra`：Flyway V4 加 `worker_role`/`trigger_ref`/`source_ref`/`trigger_type` 列 | 一张迁移文件 |
| 3 | `agent-runtime`：`AgentFactory.createWorker(ctx, role)` 收敛三个 create 方法；执行器写新字段 | 一个方法 + 映射 |
| 4 | `agent-gateway`：Gitee webhook 旁路做 Fast Path → 建 Ticket（Slow Path 交调度 Agent） | 一个判定 + 复用派发 |
| 5 | `agent-tools`：`supervisorSystemPrompt` 增补"事件→建 Ticket"职责段 | 提示词几行 |
| 6 | `spec-checks`：ArchUnit 断言 Router 工具面无 L1+ 写工具（防回退） | 一条断言 |

> 安全顺序：**先做 1/2/6（字段 + 护栏），再做 3（角色裁剪），最后做 4（放开事件入口）**。
> 权限没落地前，事件旁路只建 Ticket 不自动派发写操作。

---

## 6. 验收契约（可测）

- `AgentFactory.createWorker(ctx, DEV/TEST/OPS/PRODUCT)` 能按角色裁剪出四个 Worker，权限矩阵正确；
  现有 `createDeploy` 行为不回归（收敛为 OPS 特例）。
- 一条 Gitee issue 事件经 Fast Path 后，无关事件零 LLM 调用；放行项落到「建/关联/忽略」三态。
- `RunLedger` 任一条 `WORKSPACE` 运行记录能查到 `workerRole` 与 `triggerRef`，回答"谁、因何触发"。
- `mvn verify` 通过（含新增 ArchUnit 断言 + Flyway + 现有单测不回归）。
- prod 写操作仍强制人工确认，无任何路径绕过。

---

## 7. 风险与边界

1. **Super-agent 是研究原型**：本方案只采纳"Router 调度 + Worker 隔离"思想，全部在 AgentScope/Java 栈自建，不引包。
2. **Worker 能力要自己做**：调度层不解决"测试会不会写用例、部署会不会安全回滚"，靠现有 61 个工具 + 验收标准约束，能力不足时再补工具。
3. **成本**：事件驱动的 Slow Path 会引入额外 LLM 调用，靠 Fast Path 零 LLM 过滤控制；角色成本靠 `UsagePanel` 分组可见。
4. **单机事件总线**：多副本部署前 Router 状态需落库（`RunLedger` 已落库，`WorkspaceEventBus` 是内存的），跨实例事件投递待 PostgreSQL 化后再谈。
5. **范围收窄是刻意为之**：主动模式、Thread 直通、链式召回都是"后续若真有痛点再增量加"，不在此 MVP。
