# 数据库后端切换与 SQLite → PostgreSQL 迁移

> 入口：**设置 → 数据库**（`/api/v1/settings/database`）。
> 适用：单机 SQLite 想换成 PostgreSQL，且**不想重启进程**。

---

## 1. 热切换：不需要重启

连接池（Hikari）、方言（`PostgreSQLDialect` vs `SQLiteDialect`）和 Flyway 的 `{vendor}`
迁移目录本来都是启动期装配的。要做到运行中换库，靠的是把「后端」从 Bean 里挪到可变持有者：

- 25 个 Store 注入的都是同一个 `PlatformDb` 引用，**永不重建**；
- `PlatformDb` 内部由一个 `volatile Holder`（`DataSource` + `EntityManagerFactory`）承载真实后端；
- `read()` / `inTx()` 每次都从 volatile 引用取当前后端；
- 换库 = 造一套新 Holder + 原子替换引用，Store 完全无感。

`AgentDbProperties` 是**不可变 record**，它只描述启动时的连接参数。热切换不改它，
所以状态接口把两者分开返回：

| 字段 | 含义 |
|---|---|
| `backend` | **实际生效**的后端，取自连接池，热切换后立刻变化 |
| `configuredBackend` | 启动时配置的后端，只有重启才会与上者一致 |

界面上两者不一致时会显示「运行中的后端与启动配置不一致」提示——这是热切换的正常结果，
不是错误。

---

## 2. 切换顺序（顺序不可调整）

| 步骤 | 动作 | 失败后果 |
|---|---|---|
| 1 | **探活**：建一条目标库连接 | 立即失败，现有后端一行数据都不动 |
| 2 | **建 schema**：对目标库跑 Flyway（`common` + `postgresql`） | 同上 |
| 3 | **搬数据**：逐表幂等复制 | 同上 |
| 4 | **落配置**：写 `db-backend.yml`（密码 AES-256-GCM 密文） | 同上 |
| 5 | **原子替换**：`PlatformDb.swapTo` 换掉 Holder | 到这里才真正改运行状态 |

前四步任何一步失败，运行中的后端和配置文件都保持原样。这是整个设计的底线：
**宁可切换失败，也不能让平台失去数据库。**

---

## 3. `swapTo` 的归属规则（易错点）

`swapTo(next)` 返回「被换下来、需要被关闭」的实例。这条规则很容易写错，而且错了会静默出问题：

- **首次切换**（原来跑的是 Spring 装配的后端）：返回 `null`——那一套归 Spring 管，进程活多久它活多久。
- **再次切换**（原来跑的是上次换进来的自持实例）：返回该实例，调用方必须 `close()`，
  否则每切一次库就泄漏一个连接池。
- 返回值必须是**独立包装旧资源的新对象**，不能是 `this`——`this` 的 Holder 马上就要指向新后端，
  返回 `this` 会让调用方把刚切上去的新库关掉（自噬）。

`DataSourceConfigTest#platformDbSupportsHotSwap` 把这三条都钉成了断言。

---

## 4. 迁移做了什么

`SqliteToTargetCopier`（infra，与后端无关）：

1. 只复制「目标库列 ∩ 源库列」；
2. 每行 `INSERT ... ON CONFLICT DO NOTHING`；
3. 新增行数用「复制前后目标表行数差」算，而不是批量返回值——
   `ON CONFLICT DO NOTHING` 命中时部分驱动返回 `SUCCESS_NO_INFO(-2)`，
   按它计数会把"没插入"报成"插入了"，让幂等断言在负载高时随机失败。

**特性**：

- **幂等**：可重复执行，第二次复制新增 0 行；不覆盖 PG 已有行；
- **不停机**：源库只读打开，业务可继续写 SQLite；
- **半失败可见**：单表失败不中断整体，报告里逐表标注「跳过 / 失败原因」；
- **排除项**：`sqlite_*` 内部表与 `flyway_schema_history` 不参与复制。

---

## 5. 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/settings/database` | 状态（实际后端 / 配置后端 / 脱敏 URL） |
| POST | `/settings/database/test` | 连通性测试，失败回可读原因 |
| POST | `/settings/database/switch` | **热切换**，可选顺带搬数据（`migrate`） |
| POST | `/settings/database/migrate` | 只搬数据，不切换 |
| POST | `/settings/database` | 只落盘配置，不切换 |
| DELETE | `/settings/database` | 热切换回 SQLite（PG 数据保留） |

写操作要 `settings:write`，状态要 `settings:read`。

---

## 6. 已知边界

- **只支持 SQLite ⇄ PostgreSQL**：PG → PG（换另一台 PG）会走同一条热切换路径，
  但不会自动搬 PG 之间的数据（源不是 SQLite 时不搬）；
- **大表**：单表读取上限 100 万行（`LIMIT 1000000`），超出需分批或改用 `pgloader`；
- **迁移期间的写入**：复制过程中 SQLite 新增的行不会进入本次快照，
  建议迁移完成后重跑一次（幂等，只补齐新行）再切；
- **密码**：落盘只存密文；主密钥丢失（`platform.key` 被删）会导致解密失败，
  此时按空密码尝试连接，需重新填写；
- **旧库导入已移除**：改造前 8 个分域 `.db` 的导入需求已取消，`LegacySqliteImporter`
  及相关接口、前端卡片、配置项全部删除。启动链路只剩 `SqliteWalCheckpointer`
  这个不改数据行的 WAL 合并动作。

---

## 7. 相关代码

| 件 | 位置 |
|---|---|
| 后端持有者与热替换 | `platform/agent-infra/.../persistence/PlatformDb.java` |
| 热切换编排 | `platform/agent-gateway/.../gateway/settings/DatabaseHotSwapper.java` |
| 逐表复制 | `platform/agent-infra/.../persistence/SqliteToTargetCopier.java` |
| 配置落盘与注入 | `platform/agent-infra/.../persistence/config/DbBackendConfig.java` |
| REST 接口 | `platform/agent-gateway/.../gateway/web/DatabaseSettingsController.java` |
| 设置页面板 | `platform/web/src/components/settings/DatabasePanel.vue` |
| 启动期注入点 | `platform/agent-gateway/.../gateway/AgentApplication.java` |

测试：`DbBackendConfigTest`（8）、`SqliteToTargetCopierTest`（4）、
`DataSourceConfigTest`（3，含热切换归属规则）、`DatabaseSettingsControllerTest`（4）。
