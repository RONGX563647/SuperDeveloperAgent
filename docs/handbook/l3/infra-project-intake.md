---
type: handbook-l3
level: L3
module: agent-infra
title: 项目接入入口
summary: 校验请求、拦截重复接入，并在虚拟线程里推进接入状态机
---

# ProjectIntakeService 接入入口

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 项目接入异步入口与状态机 | `ProjectIntakeService#submit` | `platform/agent-infra/src/main/java/top/rongx/agent/infra/git/ProjectIntakeService.java:141-157` | agent-infra |
<!-- generated:end -->

## 行为拆解

1. `validate` 校验 localPath 与 repoUrl 二选一、URL 只允许 http(s)。
2. 只有"正在跑"的接入（VALIDATING/LINKING/RECIPE_CHECK）会被拦截；
   `REGISTERED` / `INTAKE_FAILED` 允许重提交（重新收编或重试）。
3. 先落一个 `VALIDATING` 状态，再用虚拟线程跑 `runIntake`，请求立即返回。
4. 状态机后续推进由 `runIntake` 完成：收编/克隆 → 配方自检 → 持久化项目 → 终态。

## 状态与副作用

- 接入状态保存在 `intakes`（进程内）；项目元数据由 `persistProject` 落库。
- 失败保留 `INTAKE_FAILED` 与诊断信息，供前端展示与重试。

## 失败与降级

- 参数非法抛 `IllegalArgumentException`，不创建接入记录。
- 同名项目正在接入抛 `IllegalStateException`，避免并发写同一目录。

## 相关测试

`platform/agent-infra/src/test/java/top/rongx/agent/infra/git/IntakeScaffoldTest.java`、
`platform/agent-gateway/src/test/java/top/rongx/agent/gateway/IntakeE2ETest.java`。
