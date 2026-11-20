---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T11:00:00+08:00
refs:
  - bug/2026-11-20_1044_envbar-default-collapsed
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 9
  passRate: "100%"
  lineCoverage: "N/A"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-26/53 环境条默认折叠与抖动修复 + 0.1.0 全量 bug 复核归档

## 概述
PostgreSQL `agent_platform` 库中 version=0.1.0 的 BUG 共 11 条。本次会话逐条复核 9 条已关单修复在当前代码中仍然成立，修复剩余 2 条 OPEN（BUG-26/BUG-53，同一根因的双单），至此 0.1.0 的 BUG 全部 CLOSED。

## 0.1.0 BUG 全量复核结果（9 条 CLOSED，修复均留存）

| 编号 | 优先级 | 标题 | 复核证据 |
|---|---|---|---|
| BUG-21 | P0 | agent 无法写入 src/main（PATH_DENIED） | PatchTools 结构化拒绝 + 允许清单仍在 |
| BUG-19 | P0 | 流水线沙箱 | DockerSandboxDeniedTest（E-707 六类规则）在 |
| BUG-22 | P0 | 工作区环境 | EnvironmentController 全量 bindings + currentBindingId 在 |
| BUG-23 | P0 | 对话流中断 | SseHeartbeat + stall 看门狗在 ChatController |
| BUG-24 | P0 | 会话中断（工具调用卡死） | 同上：静默看门狗主动下发终态 |
| BUG-15 | P0 | 任务栏版本号/标签 | GitIssueController `/versions` 局部更新在 |
| BUG-16 | P2 | 变更单无状态字段 | `change_status` 只读工具在 ChangeTools |
| BUG-17 | P1 | 流水线 ui（日志滚动） | BuiltInPipelineEngine.logLines + LogLinePage 分页在 |
| BUG-18 | P2 | 流水线产物复用 | reusableBuild + deploy_run build_run_id 在 |

## 本次修复（BUG-26/53）

**根因**（与 BUG-53 逐行定位一致）：
1. `EnvironmentStatusBar.vue` 全局单 key `agent.envBar.open`，写过 '1' 永远展开；
2. `ChatView.vue` 未传 `:default-open`，默认折叠无落点；
3. 切环境 watch 先清空数据再加载，健康态三段跳变抖动。

**修复**：
- localStorage key 按 `projectId.env` 组合隔离（`agent.envBar.open.<projectId>.<env>`）；
- `ChatView` 显式传 `:default-open="false"`；
- watch 切换时保留旧数据就地刷新；health 仅在无旧数据时才显示 loading/failed；
- 无限脉冲动画只保留在 streaming 指示上。

## 测试与质量总结
- 命令：`pnpm test`（platform/web）+ `pnpm run typecheck`
- 结果：PASSED；9 个测试文件全部通过，新增 envBarState 5 例
- 质量门禁：**PASS**

## 工单闭环
- BUG-26（task-187d610d44）、BUG-53（task-c8f5f7693e）已在 PG 库写入修复记录并置 CLOSED
- 复核后 `git_issues where kind='BUG' and version='0.1.0'` → 全部 11 条 CLOSED

## 链接
- 归档：`docs/process/bug/2026-11-20_1044_envbar-default-collapsed/manifest.json`
