---
name: governance-changes
description: 治理与变更确认：变更单、确认令牌、环境策略、补丁登记、权限边界
covers: [ActionGate, ChangeRegistry, ConfirmTokenService, EnvPolicyService, PatchRegistry]
when_to_read: 变更确认卡/确认令牌/环境策略拒绝/补丁应用/权限矩阵/生产操作被拦
budget: 100
---

# 治理与变更确认（governance-changes）

## 关键文件

| 文件 | 角色 |
|---|---|
| `platform/agent-governance/.../ActionGate.java` | 动作放行判定（角色 × 环境 × 策略） |
| `platform/agent-governance/.../ChangeRegistry.java` | 变更单登记（双构造器：GovernanceDb 或文件目录） |
| `platform/agent-governance/.../ConfirmTokenService.java` | 确认令牌：出示 planHash、有效期、确认人 |
| `platform/agent-governance/.../EnvPolicyService.java` | 环境策略矩阵（`ENV_POLICY_DENIED` 的来源） |
| `platform/agent-governance/.../PatchRegistry.java` | 补丁登记（code_patches / code_patch_edits） |

## 关键事实

- 确认令牌是「出示 planHash + 有效期 + 确认人」的三元组，前端确认卡见
  `ApprovalRequestCard.vue`；令牌过期/planHash 不符一律拒绝，**不可降级放行**。
- 环境策略：dev/test/prod 语义固定（`normalizeEnv` 缺省落 test，绝不落 prod）；
  被策略拒绝时错误码是 `ENV_POLICY_DENIED`(E-707)。
- `ChangeRegistry` 文件型/DB 型双实现——无库单测用文件型，别在测试里起数据库。
- 权限矩阵与工具面预算有 spec-checks 守护（`SupervisorToolkitWiringTest`、
  `ToolSchemaBudgetTest`）；改权限先读 `docs/code-wiki/05-tools-and-governance.md`。

## 怎么验证

- `ConfirmTokenServiceTest`、`EnvPolicyServiceTest`、`ActionGate` 相关测试
- 错误码契约：spec-checks 的 `ErrorCodeUniquenessTest`（E-xxx 唯一且分组正确）

## 找历史

`python tools/docs/find.py --impact ChangeRegistry`；`docs/views/modules/agent-governance.md`。
