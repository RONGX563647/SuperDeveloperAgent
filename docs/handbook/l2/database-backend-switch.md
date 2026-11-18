# 数据库后端切换、SQLite → PostgreSQL 迁移与旧库导入

> 入口：**设置 → 数据库**（`/api/v1/settings/database`）。
> 适用：单机 SQLite 想换成 PostgreSQL，或想在换库前先确认数据能完整搬过去；
> 也用于把改造前的 8 个分域 `.db` 显式导入统一库。

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

## 3.5 旧库导入（历史 8 个分域 `.db`）

改造前平台按域拆成 8 个文件（`projects` / `automation` / `conversations` / `run_ledger` /
`token_usage` / `users` / `governance` / `rate_limit`），统一到 `platform.db`（或 PostgreSQL）。

**它曾经是启动期自动行为，现在不是了。** 旧实现用 `@Bean(initMethod = "run")`，每次启动都会
静默比对并导入，日志里打"新增 N 行"——用户既没点过、也无法选择目标库、看不到逐表明细。
现在拆成两件事：

| 动作 | 接口 | 是否写数据 |
|---|---|---|
| 巡检 | `GET /api/v1/settings/database/legacy` | **只读**：列出文件、大小、是否已导入、上次导入行数 |
| 导入 | `POST /api/v1/settings/database/legacy/import`（`{force:false}`） | 写数据 + 写完成标记 |

启动链路只保留**不改数据**的收尾动作（`SqliteWalCheckpointer` 的 `wal_checkpoint`）。
`DataSourceConfig` 里那个 Bean 没有 `initMethod`，并有单测把这一点钉死
（`DataSourceConfigTest#legacyImporterHasNoInitMethod` / `#wiringAloneImportsNothing`）。

**语义**：

- 逐文件在 `platform_meta` 写 `legacy-import:<文件名>` 标记；有标记即跳过；
- 幂等：`INSERT OR IGNORE`（SQLite 目标）/ `ON CONFLICT DO NOTHING`（PG 目标），
  重放不产生重复行；
- `force=true` 忽略标记重放，用于「导入后旧库又新增了数据、想补一遍」；
- 单文件失败不写标记，下次触发自动重试；失败原因逐文件回传前端；
- SQLite 目标走 `ATTACH` 原地复制；PG 目标走 SQLite JDBC 读 + 批量写；
- `agent.db.import-legacy=false` 关闭设置页的导入入口（接口返回明确错误）。

---

## 4. 已知边界

- **只支持 SQLite → PostgreSQL**：反向（PG → SQLite）与 PG → PG 不在本次范围；
- **当前非 SQLite 时迁移入口禁用**：`migratable=false`，UI 置灰并说明原因；
- **大表**：单表读取上限 100 万行（`LIMIT 1000000`），超出需分批或改用 `pgloader`；
- **迁移期间的写入**：复制过程中 SQLite 新增的行不会进入本次快照，
  建议在迁移完成到重启之间保持低写入，或迁移后重跑一次（幂等，只会补齐新行）；
- **密码**：落盘只存密文；主密钥丢失（`platform.key` 被删）会导致解密失败，
  此时按空密码尝试连接，需重新填写。
- **旧库导入不在启动时执行**：启动只做只读巡检，必须由设置页显式触发；
  旧库目录随统一库位置推导（SQLite 取同目录，PG 取 `agent.db.sqlite-file` 所在目录）。

---

## 5. 相关代码

| 件 | 位置 |
|---|---|
| 配置落盘与注入 | `platform/agent-infra/.../persistence/config/DbBackendConfig.java` |
| 逐表复制（统一库 → PG） | `platform/agent-infra/.../persistence/SqliteToTargetCopier.java` |
| 旧库巡检与导入 | `platform/agent-infra/.../persistence/LegacySqliteImporter.java` |
| 迁移编排（Flyway） | `platform/agent-gateway/.../gateway/settings/SqliteToPostgresMigrator.java` |
| REST 接口 | `platform/agent-gateway/.../gateway/web/DatabaseSettingsController.java` |
| 设置页面板 | `platform/web/src/components/settings/DatabasePanel.vue` |
| 启动期注入点 | `platform/agent-gateway/.../gateway/AgentApplication.java` |

测试：`DbBackendConfigTest`（8 项）、`SqliteToTargetCopierTest`（4 项）、
`LegacySqliteImporterTest`（7 项）、`DataSourceConfigTest`（3 项，含"启动不导入"回归）。
