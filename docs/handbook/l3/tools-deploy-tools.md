---
type: handbook-l3
level: L3
module: agent-tools
title: 部署环境状态
summary: 只读查询某环境的配方版本、证据运行状态与可回放信息
---

# DeployTools 环境状态

<!-- generated:begin（由 tools/handbook/build.py 生成，勿手改） -->
| 行为单元 | 入口 | 源码位置 | 模块 |
|---|---|---|---|
| 部署环境状态只读查询 | `DeployTools#envStatus` | `platform/agent-tools/src/main/java/top/rongx/agent/tools/DeployTools.java:74-93` | agent-tools |
<!-- generated:end -->

## 行为拆解

1. `env` 规范化后读取该环境当前配方；没有配方时明确告知"尚未固化"。
2. 有配方时返回版本号、证据运行 id 与状态、git sha、历史版本数。
3. 探索前先调它能避免重复劳动：已有版本且证据成功，直接重放即可。
4. 只读工具（`readOnly = true`），不触发任何部署或写操作。

## 状态与副作用

- 只读：不写配方、不改流水线、不产生运行记录。
- 证据状态来自 `PipelineEngine#status`，与部署门禁使用同一事实来源。

## 失败与降级

- 环境非法按规范化规则处理；项目不存在时配方查询返回空而不是抛错。
- 生产环境允许查询（只读），任何写操作都在其它工具里被门禁拦住。

## 相关测试

`platform/agent-tools/src/test/java/top/rongx/agent/tools/DeployToolsConfigTest.java`。
