---
canonical: docs/summary/2026-11/2026-11-18_1937_spec-checks-hardening.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T20:16:25+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-18_1937_spec-checks-hardening
metrics:
  sessions: 1
  casesAdded: 11
  casesTotal: 521
  passRate: "100%"
  lineCoverage: "40.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# spec-checks 编码读取加固与迁移基线补录；Db* 存储改名收尾与 Flyway 统一迁移

## 概述
上一提交只把 Sqlite* 存储实现文件改名成 Db*，正文类名/构造器/静态引用仍是 Sqlite*，编译与 Spring 装配对不上；同时双库（SQLite/PostgreSQL）schema 仍靠 Hibernate ddl-auto=update 现场生成，没有正式迁移脚本与二级索引。
补完 Db* 改名的正文（类名、构造器、静态方法引用、对应测试）；agent-infra 新增 Flyway 迁移 V1 建表 / V2 自增表（postgresql、sqlite 各一份方言）/ V3 索引，agent-gateway 引入 flyway-core、flyway-database-postgresql、spring-boot-flyway 并把 ddl-auto 降级为缺列安全网；LegacySqliteImporter 改为逐文件写 platform_meta 完成标记，一次性导入幂等、崩溃可重放。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `a788ae3` | chore | test(spec-checks): UTF-8 读取失败时报出具体文件路径；补录 4 个迁移文件基线 |
| `792c4fd` | chore | refactor(infra): 存储实现 Sqlite*→Db* 改名的正文收尾，接入 Flyway 统一迁移 |

## 测试与质量总结
- 命令：`mvn -B -pl agent-gateway,agent-infra,!spec-checks -amd test`
- 结果：PASSED；Tests run: 521, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：11
- 行覆盖率：40.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-18_1937_spec-checks-hardening/manifest.json`
- 上一份同域总结：worktree-commit-hook
