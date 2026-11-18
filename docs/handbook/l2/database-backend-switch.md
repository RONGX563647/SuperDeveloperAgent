# 数据库后端切换与 SQLite → PostgreSQL 迁移

> 入口：**设置 → 数据库**（`/api/v1/settings/database`）。
> 适用：单机 SQLite 想换成 PostgreSQL，或想在换库前先确认数据能完整搬过去。

---

## 1. 为什么切换要重启

连接池（Hikari）、方言（Hibernate `PostgreSQLDialect` vs `SQLiteDialect`）和 Flyway 的
`{vendor}` 迁移目录都在**进程启动时**按 `agent.db.*` 装配。运行中改值不会重建连接池，
所以设置页只做两件事：

1. 把目标连接**加密落盘**到 `<sqlite 目录>/db-backend.yml`；
2. 返回 `restartRequired=true`，前端明确提示"重启后生效"。

下次启动时 `AgentApplication.loadDbBackendConfig()` 在 Spring 绑定 properties **之前**
把配置注入系统属性（`AGENT_DB_URL` / `AGENT_DB_USERNAME` / `AGENT_DB_PASSWORD`），
`AgentDbProperties` 从而读到新值。

**优先级**：真实环境变量 / 命令行 > `db-backend.yml` > `application.yml` 默认值。
已存在的键不被覆盖，所以临时用环境变量排障仍然有效。

---

## 2. 操作顺序

| 步骤 | 动作 | 判定 |
|---|---|---|
| 1 | 填 JDBC URL / 用户名 / 密码 → **测试连接** | 返回服务端版本与延迟；失败给出可读原因 |
| 2 | **一键迁移 SQLite 数据** | 目标库建表 + 逐表复制；返回逐表「源行数 / 新增 / 跳过」 |
| 3 | **保存（重启生效）** | 写入 `db-backend.yml`，密码为 AES-256-GCM 密文 |
| 4 | 重启平台 | 本页后端从 `SQLITE` 变为 `POSTGRES` |
| 5 | 回退（可选） | **回到 SQLite** 删除配置并重启；SQLite 文件始终保留 |

---

## 3. 迁移做了什么

`SqliteToTargetCopier`（infra，与后端无关）+ `SqliteToPostgresMigrator`（gateway，负责 Flyway）：

1. **建 schema**：对目标 PG 跑 `db/migration/common` + `db/migration/postgresql`，
   与主库同一套脚本、同一版本；
2. **逐表复制**：只复制「目标库列 ∩ 源库列」；每行 `INSERT ... ON CONFLICT DO NOTHING`；
3. **计数**：用「复制前后目标表行数差」算新增，而不是批量返回值——
   `ON CONFLICT DO NOTHING` 命中时部分驱动返回 `SUCCESS_NO_INFO(-2)`，
   按它计数会把"没插入"报成"插入了"，让幂等断言在负载高时随机失败。

**特性**：

- **幂等**：可重复执行，第二次复制新增 0 行；不覆盖 PG 已有行；
- **不停机**：源库只读打开，业务可继续写 SQLite，迁移完再重启切换；
- **半失败可见**：单表失败不中断整体，报告里逐表标注「跳过 / 失败原因」，
  整体结论降级为「部分完成」；
- **排除项**：`sqlite_*` 内部表与 `flyway_schema_history` 不参与复制。

---

## 4. 已知边界

- **只支持 SQLite → PostgreSQL**：反向（PG → SQLite）与 PG → PG 不在本次范围；
- **当前非 SQLite 时迁移入口禁用**：`migratable=false`，UI 置灰并说明原因；
- **大表**：单表读取上限 100 万行（`LIMIT 1000000`），超出需分批或改用 `pgloader`；
- **迁移期间的写入**：复制过程中 SQLite 新增的行不会进入本次快照，
  建议在迁移完成到重启之间保持低写入，或迁移后重跑一次（幂等，只会补齐新行）；
- **密码**：落盘只存密文；主密钥丢失（`platform.key` 被删）会导致解密失败，
  此时按空密码尝试连接，需重新填写。

---

## 5. 相关代码

| 件 | 位置 |
|---|---|
| 配置落盘与注入 | `platform/agent-infra/.../persistence/config/DbBackendConfig.java` |
| 逐表复制 | `platform/agent-infra/.../persistence/SqliteToTargetCopier.java` |
| 迁移编排（Flyway） | `platform/agent-gateway/.../gateway/settings/SqliteToPostgresMigrator.java` |
| REST 接口 | `platform/agent-gateway/.../gateway/web/DatabaseSettingsController.java` |
| 设置页面板 | `platform/web/src/components/settings/DatabasePanel.vue` |
| 启动期注入点 | `platform/agent-gateway/.../gateway/AgentApplication.java` |

测试：`DbBackendConfigTest`（8 项）、`SqliteToTargetCopierTest`（4 项）。
