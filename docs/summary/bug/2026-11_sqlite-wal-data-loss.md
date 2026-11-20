---
canonical: docs/summary/2026-11/2026-11-18_1751_sqlite-wal-data-loss.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T18:51:37+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_1751_sqlite-wal-data-loss
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 8
  passRate: "100%"
  lineCoverage: "40.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# SQLite 数据丢失：WAL 未落盘与旧库导入默认关闭

## 概述
SQLite 切库后旧库 WAL 未 checkpoint 落盘、且 importLegacy 默认关闭，导致历史数据（run_ledger/token_usage/conversations 等）在迁移后不可见，表现为数据丢失
新增 SqliteWalCheckpointer 启动即对统一库执行 wal_checkpoint(TRUNCATE)；LegacySqliteImporter 导入后 checkpoint 并对跳过表显式告警计数、保留 INSERT OR IGNORE 防覆盖；AgentDbProperties.importLegacy 默认改为 true；DataSourceConfig 注册 Bean；application.yml 支持 AGENT_DB_IMPORT_LEGACY 覆盖

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `b3bbbea` | chore | test(harness): pre-commit 改增量测试，ArchUnit 冻结基线并启用并行 |
| `de813e7` | docs | docs(process): 归档测试门禁优化会话 |
| `319ac71` | bug | fix(infra): 修复 SQLite 数据丢失：WAL 落盘检查点与旧库导入默认开启 |

## 测试与质量总结
- 命令：`mvn -B -pl agent-infra -am -Dtest=SqliteWalCheckpointerTest,LegacySqliteImporterTest,AgentDbPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：PASSED；Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：3
- 行覆盖率：40.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-18_1751_sqlite-wal-data-loss/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
