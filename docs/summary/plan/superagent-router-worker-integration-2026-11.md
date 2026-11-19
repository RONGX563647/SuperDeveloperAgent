---
schema: session-plan/v1
kind: plan
slug: superagent-router-worker-integration
period: 2026-11
createdAt: 2026-11-19T10:00:00+08:00
updatedAt: 2026-11-19T18:00:00+08:00
status: done
owner: TBD
verdict: PASS
supersedes: null
supersededBy: null
refs: []
canonical: docs/summary/2026-11/2026-11-19_superagent-router-worker-integration.md
---

# 工作区角色 Worker 与事件接入（v4）

## 概述

Super-agent 的「Router + Worker」思想对本项目真正有价值的只有三点：角色 Worker 的统一抽象、
事件自动变成任务、运行追踪补角色与触发来源。本方案不推倒现有调度与工具链，只补这三个缺口，
全部在现有 Java/AgentScope 栈内落地。前几版被否的宏观蓝图（Thread 订阅、三张新表、独立审计接口）
已删除。

## 现状与证据

| 能力 | 已有实现 | 证据 |
|---|---|---|
| 子 Agent 调度 | DAG 并行派发、依赖/重试/超时 | `agent-runtime/.../workspace/WorkspaceDispatchScheduler.java` |
| 异步执行与取消 | 工作区虚拟线程池 + SSE 事件流 | `WorkspaceRunExecutor.submit/execute/finish` |
| 兜底监督 | 卡死运行超时收敛 + 重启恢复 | `WorkspaceRunWatchdog` |
| 写工具 | 61 个 `@Tool`：补丁/部署/CI/环境 | `agent-tools/.../ToolCatalog.java` |
| 权限分级与人工确认 | L0–L3、环境策略、变更单与部署门禁 | `PolicyPort`、`ToolLevel`、`ChangeTools` |
| 运行追踪 | `run_ledger` + `RunLedgerRepository` | `agent-infra/.../run/DbRunLedgerRepository.java` |
| 事件入口 | Gitee push webhook（鉴权 + 幂等） | `agent-gateway/.../web/GiteeWebhookController.java` |

结论：写工具、权限门禁、追踪底座都已存在，**缺的是维度而不是系统**。

## 缺口清单

| 编号 | 等级 | 缺口 | 证据 | 后果 |
|---|---|---|---|---|
| G1 | P0 | 角色 Worker 无统一抽象 | `createChat` / `createDeploy` / `createSupervisor` 三个散方法 | 新增 Product/Test 角色只能复制粘贴 |
| G2 | P0 | 事件不会自动变成任务 | webhook 只有 push → verify 分支 | issue/PR 无法进入任务板，需人工转述 |
| G3 | P1 | 追踪缺「角色」与「触发来源」 | `run_ledger` 无 `worker_role` / `trigger_ref` | 无法回答「谁、因何触发」 |

## 优化方案

| 阶段 | 改动 | 落点 |
|---|---|---|
| 1 | 新增 `WorkerRole` 枚举（PRODUCT/DEV/TEST/OPS）与解析 | `agent-domain/.../conversation/WorkerRole.java` |
| 2 | 任务与账本加可空维度：`source_ref`/`trigger_type`、`worker_role`/`trigger_ref` | `WorkspaceTask`、`RunRecord`、`V5__worker_role_and_trigger.sql`、两个 Entity 与 Store |
| 3 | `AgentFactory.createWorker(ctx, role)`：角色 →（工具面、提示、权限）映射；`createDeploy` 成为 OPS 特例 | `agent-runtime/.../AgentFactory.java` |
| 4 | 会话按角色路由到 Worker | `ChatService` |
| 5 | Gitee 事件 Fast Path（来源+类型+动作白名单、去重、零 LLM）→ 建任务 | `GiteeWebhookController#event` |
| 6 | 运行账本写入工人角色与票据来源 | `WorkspaceRunExecutor` |
| 7 | 防回退：调度 Agent 工具面只允许只读 + supervisor 工具 | `spec-checks/.../SupervisorToolkitWiringTest.java` |

明确不做：`trigger`/`thread`/`audit_trace` 三张新表、Thread 订阅直通、Inline & Spill、
主动 off/observe/auto 模式、独立审计接口与成本看板。事件用任务上的 `source_ref` 追溯，
审计用现有 `RunLedger`，成本用 `UsagePanel` 分组。

## 验收契约

1. **角色 Worker**：`AgentFactory.createWorker(ctx, DEV/TEST/OPS/PRODUCT)` 返回四个可运行 Agent；
   `OPS` 与 `createDeploy` 行为一致。反证：任一角色拿到生产写权限或 `createDeploy` 签名回归。
   验收命令：`mvn -pl agent-runtime test -Dtest=AgentFactoryTest`（编译期 + 单测）。
2. **事件 Fast Path**：issue/PR 的 open/reopen 建任务并写 `source_ref`/`trigger_type`；
   白名单外事件返回 `ignored` 且不建任务；重复投递返回 `duplicate` 且只建一个任务。
   反证：任何分支调用了 LLM 或对 close/merge 事件建任务。
   验收命令：`mvn -pl agent-gateway test -Dtest=GiteeWebhookControllerTest`。
3. **账本维度**：工作区运行记录可读出 `worker_role` 与 `trigger_ref`；SQLite 重启后仍可读。
   反证：字段为空或反序列化报错。
   验收命令：`mvn -pl agent-infra test -Dtest=DbConversationStoreTest`。
4. **迁移安全**：V5 只做 `ALTER TABLE ADD COLUMN`，已发布迁移未被改动。
   验收命令：`mvn -pl spec-checks test -Dtest=MigrationGuardTest`。
5. **权限不倒退**：调度 Agent 工具面无任何写工具类。
   验收命令：`mvn -pl spec-checks test -Dtest=SupervisorToolkitWiringTest`。
6. **全量构建**：`mvn verify` 在本机环境通过；Docker 缺失导致的既有部署用例失败需单独注明，
   与本方案改动无关。

## 执行状态

1. G1 角色 Worker —— 已完成：`WorkerRole` + `createWorker`；`createDeploy` 保留为 OPS 入口。
2. G2 事件接入 —— 已完成：`POST /api/v1/webhooks/gitee/event` Fast Path 建任务，零 LLM。
3. G3 追踪维度 —— 已完成：`run_record` 写入 `workerRole`/`triggerRef`，`workspace_tasks` 记录事件来源。
4. 防回退护栏 —— 已完成：`SupervisorToolkitWiringTest`。
5. 验证 —— 相关模块单测与 `mvn verify` 已执行；agent-executor 的 Docker 依赖用例在无 Docker
   沙箱中失败，已在干净 HEAD 上复现，属环境问题而非本方案回归。
6. 提交与归档 —— 本会话 feat 归档 `feat/2026-11-19_1527_superagent-role-workers`，代码提交 `19bd56e`。

## 变更记录

- 2026-11-19 17:00 收敛为 v4 最小增量方案（删除三张新表与宏观概念）。
- 2026-11-19 18:00 按 v4 实施完成并补验收证据；状态改为 done，verdict=PASS。

## 链接

- 会话归档规格：`docs/session-archive-spec-v1.md` §4.6
- 研究原型：https://github.com/deepseek-ai/dsh-superagent-prototype
- 参考文章：https://developer.aliyun.com/article/1779111
