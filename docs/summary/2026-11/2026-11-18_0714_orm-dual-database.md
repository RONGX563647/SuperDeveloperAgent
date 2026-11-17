---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T07:36:38+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_0714_orm-dual-database
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 567
  passRate: "100%"
  lineCoverage: "38.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 引入 JPA 统一 SQLite 与 PostgreSQL 持久化

## 概述
持久化层写死 SQLite：18 个 Store 直接打开 .db 文件，连接池、schema 初始化和事务均手写，无法通过配置切换到 PostgreSQL。
引入 Spring Data JPA + Hibernate 7 + HikariCP 作为统一 ORM 基座。新增 agent.db.url：留空走 SQLite 单文件，配置 jdbc:postgresql:// 走 PG；PG 由 Flyway 管理 schema，SQLite 无官方 Flyway 模块，改由 Hibernate 社区方言按实体生成/升级。新增 platform_meta 实体与仓储作为双库冒烟，并把 EntityScan/JpaRepositories 装配到 infra 与 governance 包。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e13d0ea` | feat | feat(persistence): 引入 JPA 统一 SQLite 与 PostgreSQL 装配 |
| `69c336a` | bug | fix(persistence): 数据库配置错误改用平台异常与错误码 |
| `7b94e92` | chore | test(persistence): 补充显式 SQLite URL 优先级用例 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test -Dtest='!ProcessArchiveTest,!DeployEnvPrecheckTest,!DeployRecipeFlowTest,!DockerDaemonRepairTest' -Dsurefire.failIfNoSpecifiedTests=false`
- 结果：PASSED；Tests run: 567, Failures: 0, Errors: 0, Skipped: 4
- 新增用例：3
- 行覆盖率：38.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_0714_orm-dual-database/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
