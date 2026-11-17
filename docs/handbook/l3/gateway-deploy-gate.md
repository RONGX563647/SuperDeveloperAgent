---
type: handbook-l3
level: L3
module: agent-gateway
title: 部署门禁
summary: 服务器操作前校验测试证据与计划哈希，并消费一次性确认令牌
---

# DeployGate 部署门禁

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 部署前证据校验与确认令牌 | `DeployGate#requireIfNeeded` | `platform/agent-gateway/src/main/java/top/rongx/agent/gateway/deploy/DeployGate.java:46-59` | agent-gateway |
<!-- generated:end -->

## 行为拆解

1. `EnvironmentPromotion#requiresTestEvidence(env)` 判定该环境是否需要证据；不需要则直接放行。
2. 需要时先算证据问题：测试环境跑通并固化的配方 + 证据运行成功；不满足抛 `ENV_NOT_PROMOTED`。
3. 缺确认令牌抛 `CONFIRM_REQUIRED`，提示先去部署面板点「确认发布」。
4. 令牌消费时绑定 `changeId`（项目/环境/sha 前 7 位）与 `planHash`，防止"确认 A、执行 B"。

## 状态与副作用

- 令牌是一次性的：`ConfirmTokenService#consume` 成功即失效。
- `issue` 只签发，不校验证据；调用方必须先自行校验。

## 失败与降级

- 证据不足、令牌缺失/不匹配、令牌过期：全部 fail-fast，不降级执行。
- 非服务器类环境直接返回，不做多余校验。

## 相关测试

`platform/agent-gateway/src/test/` 下的 Controller/护栏测试，以及
`platform/agent-governance/src/test/.../DangerousActionPolicyTest`。
