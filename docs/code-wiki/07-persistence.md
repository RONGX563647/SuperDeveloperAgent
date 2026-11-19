---
type: code-wiki-persistence
summary: 双数据库策略、Flyway 迁移、实体与表清单、工作区目录布局、会话状态加密与保留策略
---

# 持久化与数据布局

## 1. 双数据库策略

平台只认两种后端（[DbBackend](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/persistence/config/DbBackend.java)）：`SQLITE`、`POSTGRES`。

| 场景 | 配置 | 说明 |
|---|---|---|
| 默认 | `agent.db.url` 为空 | 单文件 SQLite：`./data/workspaces/_platform/platform.db` |
| 生产 | `AGENT_DB_URL=jdbc:postgresql://...` | 所有持久化切换过去 |
| 运行中切换 | 设置页"热切换" | 替换连接池并写回 `db-backend.yml`，无需改 yml |

行为约定：

- **只认"未配置"时回落 SQLite**；配置了 PG 却连不上 → 启动失败，避免数据分裂。
- 密码支持密文：设置页写入的密文在启动时解密注入 `AGENT_DB_PASSWORD`；环境变量优先。
- SQLite 使用 WAL；迁移前用 `SqliteWalCheckpointer` 做检查点；`SqliteToTargetCopier` 支持 SQLite → PG 搬迁。

配置项（[application.yml](file:///workspace/platform/agent-gateway/src/main/resources/application.yml)）：

```yaml
agent.db:
  url / username / password
  sqlite-file: ./data/workspaces/_platform/platform.db
  pool-size: 8
```

## 2. Flyway 迁移

位置：`platform/agent-infra/src/main/resources/db/migration/`

```text
common/       V1__platform_schema.sql       建表
              V3__platform_indexes.sql      索引
sqlite/       V2__identity_tables.sql       自增表（SQLite 方言）
postgresql/   V2__identity_tables.sql       自增表（PG 方言）
              V4__identity_sequence_resync.sql
```

规则：

- `flyway.locations = classpath:db/migration/common,classpath:db/migration/{vendor}`，按后端自动选方言。
- `baseline-on-migrate=true`，`baseline-version=1`，兼容存量库。
- Flyway 先于 JPA 初始化；`ddl-auto=update` 仅作安全网。
- **已发布的迁移文件不可修改**（`MigrationGuardTest` 校验校验和/内容）。

## 3. 实体与表

持久化扫描范围：`top.rongx.agent.infra`、`top.rongx.agent.governance`、`top.rongx.agent.gateway`（[PersistenceConfig](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/persistence/config/PersistenceConfig.java)）。

| 主题 | 表 |
|---|---|
| 身份 | `users`、`gitee_accounts`、`git_hosting_accounts`、`git_oauth_apps`、`git_integrations` |
| 项目 | `projects`、`project_envs`、`project_env_vars`、`project_env_overrides`、`env_bindings`、`env_vars`、`env_templates`、`env_resources` |
| 变更 | `change_plans`、`confirm_tokens`、`code_patches`、`code_patch_edits` |
| 流水线/运行 | `run_ledger`、`token_usage`、`deploy_servers`、`deploy_port_leases`、`project_service_leases`、`platform_meta` |
| 会话/调度 | `conversations`、`conversation_messages`、`conversation_runs`、`conversation_timeline`、`workspace_tasks`、`stream_lease` |
| Issue | `git_issues`、`git_issue_comments`、`git_issue_events` |
| BugFix | `bugfix_runs`、`bugfix_stage_runs`、`bugfix_events`、`bugfix_policies`（`bugfix_policy` / `bugfix_project_policy`）、`bugfix_notifications`、`bugfix_leases`、`bugfix_env_leases` |
| 设置 | `model_catalog`、`model_connections`、`mcp_servers`、`mcp_disabled_tools`、`secrets` |

设计要点：

- 大量"文件型"实现同时提供 DB 版（如 `ChangeRegistry` 两个构造器：`GovernanceDb` 或 `workspaceRoot`），便于无库单测。
- `stream_lease` / `project_service_leases` 用于多实例场景下的并发租约。
- `run_ledger` 是跨引擎统一账本，`RunKind` 为分类维度。

## 4. 工作区目录布局

```text
./data/
├── workspaces/
│   ├── _platform/
│   │   ├── platform.db            # 统一 SQLite
│   │   ├── db-backend.yml         # 热切换写回
│   │   ├── deploy/<project>-<env>/ # HOST 发布：releases/<sha>、current、previous、app.pid、app.log
│   │   ├── local/                 # dev 本机前后端进程日志
│   │   └── agent-state/           # Agent 会话状态（700/600，AES-GCM）
│   ├── <project-a>/
│   │   ├── repo/                  # 接入仓库
│   │   ├── worktrees/<name>/      # 会话/流水线工作树
│   │   ├── recipes/<env>/vN/      # 环境配方（跑通后固化，可重放/回滚）
│   │   └── _history/<runId>.yml   # 构建历史（重启可恢复）
│   └── <project-b>/...
└── api/
    ├── openapi.json               # OpenAPI 快照（api_search 用）
    └── agent-tools.json           # 工具目录快照（tool_search 用）
```

相关配置：

| 键 | 默认 | 作用 |
|---|---|---|
| `agent.workspace.root` | `./data/workspaces` | 项目工作区根 |
| `agent.db.sqlite-file` | `./data/workspaces/_platform/platform.db` | 统一库 |
| `agent.api-docs.snapshot` | `./data/api/openapi.json` | API 快照 |
| `agent.tools.catalog-path` | `./data/api/agent-tools.json` | 工具目录快照 |
| `agent.intake.shared-root` | 空 | 接入目录浏览边界（留空=全盘） |

## 5. 加密与密钥

[SecretBox](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/security/SecretBox.java)：AES-256-GCM，用于 Gitee token、SSH 密码、模型 API Key、DB 密码。

主密钥来源优先级：

```text
环境变量 AGENT_SECURITY_MASTER_KEY（Base64，32 字节）
  → 工作区密钥文件（首次生成，权限 600）
```

[EncryptedAgentStateStore](file:///workspace/platform/agent-infra/src/main/java/top/rongx/agent/infra/state/EncryptedAgentStateStore.java)：

```java
save(userId, sessionId, key, State | List<State>)
<T> Optional<T> get(userId, sessionId, key, Class<T>)
<T> List<T> getList(...)
exists / delete / listSessionIds
```

特性：临时文件 + 原子移动；目录 700、文件 600；同一把主密钥；落盘前经 `ConversationContextTrimmer` 按预算裁剪（SYSTEM 提示永远保留）。

## 6. 上下文与缓存预算

| 配置 | 默认 | 作用 |
|---|---|---|
| `agent.context.max-chars` | 80000 | 落盘上下文预算（不含 SYSTEM） |
| `agent.context.keep-messages` | 24 | 最少保留消息数 |
| `agent.context.summary-chars` | 4000 | 旧历史折叠摘要上限（0=直接丢弃） |
| `agent.tools.max-output-chars` | 8000 | 单次工具输出上限 |
| `agent.tools.output-budgets` | 按工具覆盖 | 如 `git_diff=16000` |
| `agent.tools.cache.ttl-seconds` | 90 | 只读结果缓存 TTL |
| `agent.tools.cache.exclude` | 状态类工具 | 不缓存易变结果 |

## 7. 数据保留与清理

- `ChangeRegistry.purgeBefore` / `ConfirmTokenService.purgeExpired` / `PatchRegistry.purgeBefore` 由网关定时任务调用（`governance` 清理）。
- `agent.run.stale-seconds`（默认 300）：RUNNING 超时未更新的账本视为孤儿，可恢复为失败。
- `RunHistoryStore` 保留最近运行；镜像保留 5 个（`KEEP_IMAGES`）。

## 8. 迁移与备份建议

- **SQLite**：备份 `data/workspaces/_platform/platform.db`（含 `-wal` / `-shm`）与 `data/workspaces/<project>/`。
- **PostgreSQL**：`pg_dump`；工作区目录仍需单独备份（仓库、worktree、配方、会话状态不在库内）。
- **密钥**：`AGENT_SECURITY_MASTER_KEY` 必须与数据一起备份，否则密文不可解。
- 切换后端：先停写 → 检查点 → `SqliteToTargetCopier` → 校验行数 → 切换配置。
