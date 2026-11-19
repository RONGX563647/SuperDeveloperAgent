---
canonical: docs/summary/2026-11/2026-11-18_2023_postgres-settings-and-sqlite-migration.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T21:04:45+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_2023_postgres-settings-and-sqlite-migration
metrics:
  sessions: 1
  casesAdded: 12
  casesTotal: 246
  passRate: "100%"
  lineCoverage: "40.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 设置页配置 PostgreSQL 并一键迁移 SQLite 数据

## 概述
设置页无法配置 PostgreSQL，SQLite 数据也没有迁移通道：切库只能改环境变量重启，历史数据要靠手工导出导入。
新增 db-backend.yml 后端配置（密码 AES-256-GCM 密文落盘，启动时解密注入系统属性，环境变量优先）；SqliteToTargetCopier 按表结构交集幂等搬运、表级失败隔离；SqliteToPostgresMigrator 在目标库先跑 Flyway 再只读打开 SQLite 源；DatabaseSettingsController 提供状态/测试/迁移/保存/重置五个接口，前端新增数据库分区。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `82a5708` | feat | feat(db): 设置页配置 PostgreSQL 并一键迁移 SQLite 数据 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,agent-infra,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway,agent-infra test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 246, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：12
- 行覆盖率：40.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_2023_postgres-settings-and-sqlite-migration/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
