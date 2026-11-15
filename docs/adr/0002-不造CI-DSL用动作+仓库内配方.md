# ADR-0002：不造 CI DSL，用 5 个动作 + 仓库内配方

- **状态**：通过
- **日期**：2026-11-06
- **后续修订（2026-11-15）**：动作已从 5 个扩为 7 个（`backend_build`/`backend_test`/`frontend_build`/`frontend_test`/`image_package`/`stack_deploy`/`stack_rollback`）；配方来源枚举现为 `CI_ACTIONS`/`TASKFILE`/`MAKEFILE`/`BUILTIN`（`ActionSource.Kind`，正文的 COMPOSE/INLINE 已不成立），解析顺序为 `ci/actions.yml` → `Taskfile.yml` → `Makefile` → 内置默认。本文的决策（不造 DSL、动作 + 仓库配方）仍有效，原文保留以记录决策时点；现行实现见 [Code-Wiki §5.1](../Code-Wiki.md) 与 [CODE-MAP](../CODE-MAP.md)。
- **关联**：[CICD 决策](../CICD方案替代与自创-决策-v1.md)（调研与验收原文已删除，Git 历史可追溯）

## 背景与痛点

「自研流水线」最容易滑向「再写一个 Jenkinsfile」。同时调研发现：主流开源 CI 都与特定 SCM 绑定（Gitee 场景下无法直接用），复用成本高于预期。

## 问题陈述

要不要定义一套自己的流水线 DSL？还是只提供动作，把流程留在仓库里？

## 候选方案

| 方案 | 收益 | 风险 / 成本 |
|---|---|---|
| A. 复用开源 CI | 生态成熟 | SCM 绑定、私有化部署重、Agent 无法安全调用 |
| B. 自研 YAML DSL + 解释器 | 表达力强、平台可控 | 语言与生态都要养；DSL 版本兼容是长期负担 |
| C. 5 个动作 + 仓库内配方 | 无新语言；配方随代码走、可评审；平台只做执行与治理 | 复杂编排需用户自己写 Makefile/Taskfile |

## 决策

选 **C**。动作固定为 `build / test / image / deploy / rollback`，配方来源只允许 `MAKEFILE`、`TASKFILE`、`COMPOSE`、`INLINE` 四类，放在仓库 `ci/**`；平台执行前必须 `dry-run` 预检（E-708），配方变更以 `specHash` 绑定确认令牌。

## 影响范围

- 代码：`agent-executor`（`BuiltInPipelineEngine`、Stage 执行器契约见[规约](../规约-流水线执行器-v1.0.md)）；
- 文档：使用方式进[工作台指南](../流水线工作台-使用指南-v1.md)；
- 运维：执行仍需要宿主/容器运行时，部署语义见[发布编排](../发布编排与平台部署器-v1.md)。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 用户把密钥写进配方 | 配方扫描 + 密钥引用 `secret://`（ADR-0003）；`dry-run` 阻断 |
| 配方绕过平台直接改生产 | 生产动作 L3 需人工确认 + 令牌一次性；平台外变更不在本 ADR 承诺范围 |

## 后果

平台不背 DSL 兼容包袱；代价是复杂流水线需要仓库内工程化能力（Makefile/Taskfile），已在指南中给出模板。
