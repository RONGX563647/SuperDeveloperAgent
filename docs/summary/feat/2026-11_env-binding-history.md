---
canonical: docs/summary/2026-11/2026-11-19_0010_env-binding-history.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T00:23:20+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0010_env-binding-history
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 327
  passRate: "100%"
  lineCoverage: "40.3%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 环境绑定历史可查询，解绑保留记录

## 概述
task-2ee7375068：环境卡片只回当前绑定，历史绑定记录既没持久化查询接口、也没有解绑留痕——用户切换或解绑一次后，就再也看不到上次绑的是谁、什么时候绑的、为什么失效。
EnvironmentStore 增加 bindings(projectId, env, limit) 全量查询（含草稿/已确认/过期/已解绑，按创建时间倒序）与 revokeBinding(id, actor)；EnvBinding 增加 REVOKED 状态与统一的 usable() 口径；网关新增 GET /settings/projects/{id}/env-bindings（返回 currentBindingId 与 bindings[]）与 POST /env-bindings/{id}/revoke（只标记不删除）；前端补 listEnvBindings / revokeEnvBinding。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e7a886e` | feat | feat(env): 环境绑定历史可查，解绑标记 REVOKED 而不删记录 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-gateway,agent-infra,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-gateway,agent-infra test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 327, Failures: 0, Errors: 0, Skipped: 3
- 新增用例：5
- 行覆盖率：40.3%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0010_env-binding-history/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
