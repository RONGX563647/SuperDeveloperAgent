---
name: bugfix-loop
description: BugFix 自动闭环：触发、阶段机、补丁应用、输出校验、脱敏与通知
covers: [BugFixOrchestrator, BugFixStageHandler, BugFixTriggerService, PatchApplier, BugFixOutputValidation, BugFixRedactor]
when_to_read: BugFix 流水线/自动修复不触发/补丁应用失败/输出校验/通知/bugfix 表
budget: 100
---

# BugFix 自动闭环（bugfix-loop）

## 关键文件（`platform/agent-gateway/.../bugfix/`）

| 文件 | 角色 |
|---|---|
| `BugFixOrchestrator.java` | 闭环主编排：触发 → 阶段机 → 补丁 → 验证 → 通知 |
| `BugFixStageHandler.java` | 阶段处理（bugfix_stage_runs 的每一步） |
| `BugFixTriggerService.java` | 触发源（issue/失败回放等） |
| `PatchApplier.java` / `BugFixOutputValidation.java` | 补丁落地与产出校验 |
| `BugFixRedactor.java` | 日志/产出脱敏（密钥不落盘） |
| `GitIssueNotifier.java` | 回写 issue 评论 |

## 关键事实

- 状态在 `bugfix_runs` / `bugfix_stage_runs` / `bugfix_events`（V1 建表）；
  并发靠 `bugfix_leases` / `bugfix_env_leases` 租约，别再加全局锁。
- 策略分层：`bugfix_policies`（全局）与 `bugfix_project_policy`（项目覆盖）。
- 编排器构造 `RunRecord` 时参数与 `worker_role`/`trigger_ref` 维度对齐
  （0.2 线引入，见 chat-runtime 卡）——动记录结构先 grep 全部构造点。
- 输出校验（`BugFixOutputValidation`）是闭环不回退的闸门：校验不过不得合入。

## 怎么验证

- `BugFixOrchestrator` 相关测试（gateway）；`PatchApplierTest`
- 修复类改动按仓库规矩补回归用例（先红后绿，`tests.evidence` 记对比）

## 找历史

`python tools/docs/find.py --impact BugFixOrchestrator`；`docs/views/modules/agent-gateway.md`。
