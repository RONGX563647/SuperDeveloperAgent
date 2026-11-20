---
name: db-migrations
description: Flyway 迁移与双数据库：SQLite/PG 方言、迁移锁、热切换、加密状态
covers: [V1__platform_schema, V2__identity_tables, V6__image_readiness, MigrationGuardTest, PersistenceConfig]
when_to_read: 改表结构/加迁移/数据库热切换/迁移校验和报错/PG 回切失败/JPA 实体
budget: 100
---

# 数据库迁移（db-migrations）

## 关键文件

- `platform/agent-infra/src/main/resources/db/migration/`：`common/`（V1、V3、V5、V6）+
  `sqlite/`、`postgresql/` 各自的 `V2`（自增方言），PG 另有 `V4`（sequence 重同步）。
- `MigrationGuardTest` + `MigrationBaseline`：**已发布迁移文件不可改**（校验和锁定）。
- `PersistenceConfig`：JPA 扫描范围 `top.rongx.agent.{infra,governance,gateway}`。

## 关键事实

- `flyway.locations = classpath:db/migration/common,classpath:db/migration/{vendor}`，按后端自动选方言。
- `baseline-on-migrate=true`、`baseline-version=1`，兼容存量库；Flyway 先于 JPA，`ddl-auto=update` 只是安全网。
- 新表要进 `docs/code-wiki/07-persistence.md` 的表清单（含"为什么这么设计"一行）。
- 热切换（SQLite↔PG）：探活→建表→搬数据→落配置→原子替换，见 `DatabasePanel` / `switchToPostgres`。
- 加密状态与密钥布局见 code-wiki 07 §5；**明文密钥禁止入配置**（`ConfigCredentialScanTest`）。

## 怎么验证

`mvn -pl agent-infra,spec-checks '-Djacoco.skip=true' test`（迁移锁在 spec-checks）；
新迁移后本地起一次后端看 Flyway 应用日志（如 `Migrating … to version 6`）。

## 找历史

`python tools/docs/find.py --impact migration`；`docs/views/modules/agent-infra.md`。
