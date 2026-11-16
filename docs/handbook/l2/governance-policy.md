---
type: handbook-l2
level: L2
module: agent-governance
title: 动作分级与分环境治理
summary: 负责 L0-L3 动作分级、dev/test/prod 裁决矩阵，以及服务器操作前的证据与确认令牌。
refs:
  - ../l3/governance-env-policy.md
  - ../l3/gateway-deploy-gate.md
  - ../l3/tools-deploy-tools.md
---

# 动作分级与分环境治理

## 职责边界

定义 L0-L3 动作级别、dev/test/prod 裁决矩阵、拒绝语义，以及服务器操作前的证据与确认令牌。策略是唯一出口：工具、HTTP 控制器与流水线引擎都调用同一个 `PolicyPort`，避免各写一套矩阵。环境与用户来自服务端上下文，不依赖模型输入。不负责工具 schema 装配，也不负责流水线内部阶段重试。

## 输入

- `ProjectContext#env`：dev/test/prod；未知值按 prod 处理。
- `ToolLevel`：`L0_READ`、`L1_PROPOSE`、`L2_EXECUTE_ISOLATED`、`L3_EXECUTE_PROD`。
- `operation`：动作名，用于拒绝文案。
- 部署入口：`projectId`、`env`、`gitSha`、`confirmToken`。

## 输出

- `PolicyDecision`：ALLOW、CONFIRM、DENY。
- 拒绝异常：E-707（环境策略）、E-601（证据不足）、E-602（缺少人工确认）。
- 一次性确认令牌：绑定 userId、changeId、planHash，5 分钟有效、用过即废。

## 关键状态与流转

1. `EnvPolicyService#policy` 取矩阵，未知环境回落到 prod。策略判定是纯计算，不产生副作用；生产 L3 永远不能自动放行。
2. 默认矩阵：
   - dev：L0/L1/L2 ALLOW，L3 DENY，dry-run 后自动 apply。
   - test：L0/L1/L2 ALLOW，L3 CONFIRM，dry-run 后自动 apply。
   - prod：L0/L1 ALLOW，L2 DENY，L3 CONFIRM，不自动 apply。
3. `EnvPolicy` 构造时拒绝 prod 的 L3=ALLOW，防止配置写错。
4. `EnvPolicyService#requireNotDenied` 只处理 DENY；CONFIRM 交由调用方走审批，不能静默放行。
5. `DeployGate#requireIfNeeded`：仅 prod 需要测试证据；`EnvironmentPromotion#problem` 校验测试脚本存在、证据运行 SUCCEEDED、提交一致。
6. 证据通过后仍必须有 `confirmToken`；`ConfirmTokenService#consume` 校验存在、未使用、未过期、changeId 与 planHash 匹配。
7. `DeployController` 与 `PipelineController` 共用同一 `DeployGate`，避免换入口绕过。

两者关系：证据回答“这个提交在测试环境跑通过”，令牌回答“某人此刻批准在服务器上执行这次发布”，缺一不可。`planHash` 绑定项目、环境、动作与提交，防止确认 A 执行 B。

## 失败与降级

- 未知环境：按 prod，fail-closed。
- prod 的 L2：直接拒绝，不进入执行器。
- 缺测试脚本、证据未成功、提交不一致：E-601，不签发令牌。
- 令牌缺失：E-602；过期、复用、计划不匹配：消费失败。
- 令牌签发只能由已登录 HTTP 链路触发，工具层无法自我批准。
- 拒绝信息包含环境、动作与级别，便于定位；令牌消费失败不会回退到免确认执行。

## 实现单元

| 行为 | 入口 | 详见 |
|---|---|---|
| 分环境裁决 | `EnvPolicyService#decide` | [governance-env-policy](../l3/governance-env-policy.md) |
| 统一拒绝门禁 | `EnvPolicyService#requireNotDenied` | [governance-env-policy](../l3/governance-env-policy.md) |
| 测试证据校验 | `DeployGate#requireIfNeeded` | [gateway-deploy-gate](../l3/gateway-deploy-gate.md) |
| 令牌签发与消费 | `ConfirmTokenService#issue` / `#consume` | [gateway-deploy-gate](../l3/gateway-deploy-gate.md) |
| L3 动作申请与执行 | `ActionService#requestApproval` / `#apply` | [governance-env-policy](../l3/governance-env-policy.md) |

## 修改指引

- 改策略矩阵：先看 `platform/agent-governance/src/main/java/top/rongx/agent/governance/EnvPolicyService.java`、`platform/agent-domain/src/main/java/top/rongx/agent/domain/policy/EnvPolicy.java`，对应 `EnvPolicyServiceTest.java`。
- 改证据规则：先看 `platform/agent-domain/src/main/java/top/rongx/agent/domain/deploy/EnvironmentPromotion.java`，对应 `platform/agent-executor/src/test/java/top/rongx/agent/executor/DeployRecipeFlowTest.java`。
- 改令牌与部署门禁：先看 `ConfirmTokenService.java`、`DeployGate.java`，对应 `ConfirmTokenServiceTest.java`、`platform/agent-gateway/src/test/java/top/rongx/agent/gateway/DeployStageTest.java`。
- 改动作门禁：先看 `ActionService.java`，对应 `ChangeRegistryTest.java`、`DangerousActionPolicyTest.java`。
