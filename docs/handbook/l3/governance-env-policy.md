---
type: handbook-l3
level: L3
module: agent-governance
title: 环境策略拒绝
summary: 按 dev/test/prod 的 L0-L3 矩阵裁决动作，DENY 时抛出带上下文的拒绝
---

# EnvPolicyService 环境策略

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 环境策略拒绝违规动作 | `EnvPolicyService#requireNotDenied` | `platform/agent-governance/src/main/java/top/rongx/agent/governance/EnvPolicyService.java:52-60` | agent-governance |
<!-- generated:end -->

## 行为拆解

1. `policy(env)` 取环境策略；未知环境回退到 `prod`（宁可更严）。
2. `decide(env, level)` 查该动作级别的裁决：`ALLOW` / `CONFIRM` / `DENY`。
3. `requireNotDenied` 只在 `DENY` 时抛 `PolicyDeniedException(E-707)`，异常信息带环境、动作与级别；
   `CONFIRM` 不在这里放行，由调用方走变更单/确认流程。
4. 默认矩阵：dev/test 的 L0-L2 允许、L3 拒绝；prod 全部收紧（具体值见 `defaults()`）。

## 状态与副作用

- 策略矩阵是进程内不可变映射，没有持久化副作用。
- `view()` 供设置页展示当前矩阵；`canAutoApply(env)` 供 dry-run 后自动应用的判断。

## 失败与降级

- 拒绝是 fail-fast：抛异常，不执行任何动作。
- 环境名缺失/未知一律按最严策略处理，绝不因"没传环境"而放宽。

## 相关测试

`platform/agent-governance/src/test/java/top/rongx/agent/governance/EnvPolicyServiceTest.java`、
`DangerousActionPolicyTest.java`。
