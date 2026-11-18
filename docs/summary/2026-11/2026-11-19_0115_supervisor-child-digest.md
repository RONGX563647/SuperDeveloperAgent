---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T01:21:04+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0115_supervisor-child-digest
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 194
  passRate: "100%"
  lineCoverage: "39.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 调度会话可汇总子会话结论

## 概述
task-de956c65b7（工作区助手 super agent 搭建，实为 FEAT）：supervisor 已经能 conversation_create 子会话并做 DAG 派发（无依赖并行、依赖满足后派发、失败重试、连续失败熔断、票据登记与关闭、独立 conversationId），但父会话拿不到「子会话各做完了什么」——只有节点状态字符串，汇报时说不清结论。
新增 ChildDigest 记录（会话 id / 标题 / 会话状态 / 最近运行 / 结论 / 错误 / 目标 / 更新时间），一行摘要优先取结论、其次错误、最后给占位；WorkspaceSupervisorPort 增加 childDigests(projectId, owner, limit)，实现里排除调度会话自身、按最近活跃倒序、严格按项目与 owner 过滤；新增只读工具 workspace_children 挂到 dispatch 插件，父会话一次拿到全部子会话结论并可直接渲染。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c8d8b71` | feat | feat(supervisor): 调度会话可汇总子会话结论 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-runtime,agent-tools,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-runtime,agent-tools test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 194, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：5
- 行覆盖率：39.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0115_supervisor-child-digest/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
