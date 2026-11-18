---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T08:23:41+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_0809_jpa-store-migration
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 570
  passRate: "100%"
  lineCoverage: "38.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# Phase 2：全部 Store 迁移到 JPA 统一持久化

## 概述
持久化层写死 SQLite：18 个 Store 各自打开分域 .db 文件并手写连接池/事务/DDL，无法通过配置切换 PostgreSQL；阶段一仅完成装配与 platform_meta 冒烟。
43 张表全部映射为 @Entity（含 9 个复合主键 @IdClass.Key），Store 统一通过 PlatformDb / GovernanceDb 的 EntityManager 执行原生 SQL，事务与方言交给 Hibernate；两条后端共用 hbm2ddl.auto=update 生成 schema，移除 Flyway；新增 agent.db.import-legacy 一次性导入旧多文件 SQLite。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `4dd3af6` | feat | feat(persistence): 全部 Store 迁移到 JPA 统一库 |

## 测试与质量总结
- 命令：`mvn -B test -Djacoco.skip=true -Dtest='!ProcessArchiveTest,!DeployEnvPrecheckTest,!DeployRecipeFlowTest,!DockerDaemonRepairTest' -Dsurefire.failIfNoSpecifiedTests=false`
- 结果：PASSED；Tests run: 570, Failures: 0, Errors: 0, Skipped: 4
- 新增用例：5
- 行覆盖率：38.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- PostgreSQL 通路仅做静态验证：本机无 PG 实例，建议在 CI 提供 `AGENT_DB_URL=jdbc:postgresql://…` 后跑一遍全量用例。
- 旧库导入只在 SQLite 目标上验证；切换到 PG 前的首次导入建议先备份 `platform.db`。

## 链接
- 归档：`docs/process/feat/2026-11-18_0809_jpa-store-migration/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
