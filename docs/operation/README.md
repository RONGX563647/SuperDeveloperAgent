# 运维手册（平台自身）

> 面向：部署/维护本平台的人。**不是**「用平台部署业务项目」的说明，那部分见[流水线工作台使用指南](../流水线工作台-使用指南-v1.md)。

## 1. 前置条件

| 项 | 要求 |
|---|---|
| JDK | 21 及以上（当前开发环境 25，建议加 `--enable-native-access=ALL-UNNAMED` 消除 sqlite-jdbc 告警） |
| 构建 | Maven 3.9+；前端 Node 20+ |
| 容器 | Docker（`AGENT_SANDBOX_MODE=docker`）；无 Docker 时用 `process` 模式做本机验证 |
| 变量 | `AGENT_MODEL_API_KEY`（必需）；`AGENT_MODEL_BASE_URL`/`AGENT_MODEL_NAME` 可覆盖 |
| 数据目录 | `AGENT_WORKSPACE_ROOT`（默认 `./data/workspaces`；本地演示环境为 `/workspace/demo-workspaces`） |

## 2. 启动与停止

```bash
# 后端（前台，便于看日志）
cd platform/agent-gateway
AGENT_SANDBOX_MODE=process AGENT_MODEL_API_KEY=sk-xxx \
  java --enable-native-access=ALL-UNNAMED \
  -jar target/agent-gateway-0.1.0-SNAPSHOT-exec.jar

# 前端（开发）
cd platform/web && npm install && npm run dev     # http://localhost:5173，代理 /api → 8090

# 健康检查
curl -s http://localhost:8090/actuator/health    # {"status":"UP"}
```

停止：前台 `Ctrl+C`；后台按 PID `kill <pid>`，**不要**用 `pkill java`（会误杀同机其他 Java）。

## 3. 端口与数据

| 端口 | 用途 |
|---|---|
| 8090 | 网关（HTTP + SSE） |
| 5173 | Vite 开发服务器（仅开发） |

数据目录（`AGENT_WORKSPACE_ROOT` 下）：

| 路径 | 内容 | 能否删 |
|---|---|---|
| `_platform/users.db` | 用户、口令散列、角色 | 删了会重置为种子账号 |
| `_platform/projects.db` | 项目与环境注册 | 删了需重新接入项目 |
| `_platform/governance.db` | 变更单、确认令牌、补丁登记 | 删了丢失待确认操作（安全上可接受，需重新发起） |
| `_platform/automation.db` | 自动化任务/触发配置 | 需重新配置 |
| `_platform/run_ledger.db` | 统一运行账本 | 可删，仅丢历史视图；终态保留 30 天自动清理 |
| `_platform/rate_limit.db` | `agent.chat.limiter=jdbc` 时的租约 | 可删，最坏是短暂放宽并发 |
| `<project>/` | 项目工作区、流水线历史、部署状态 | **不可随手删**（含运行历史与状态目录） |

备份建议：每日冷备 `_platform/*.db`（SQLite 用 `sqlite3 x.db ".backup 'x.bak'"`，不要在写入中直接 `cp`）+ 项目工作区目录。

## 4. SSE 与反向代理

工作区/对话依赖长连接，代理需：

```nginx
proxy_buffering off;
proxy_read_timeout 3600s;
add_header X-Accel-Buffering no;
```

若发现「状态不刷新」：先看浏览器 Network 里 `/events` 是否 200 且持续收包，再看代理是否缓冲；前端在事件流断开后会自动降级为 10 秒轮询，因此现象通常是「延迟变大」而非「完全不更新」。

## 5. 日志与排查

| 现象 | 先看 | 常见原因 |
|---|---|---|
| 启动即退出 | 控制台首段 `ERROR` | 缺 `AGENT_MODEL_API_KEY`；端口占用；Docker 模式但 daemon 未启动 |
| 登录 401 | `/actuator/health`、users.db | 种子账号密码被改；令牌过期（30 天） |
| 对话 429 | `agent.chat.max-concurrent` | 并发闸门打满；多实例未切 `limiter=jdbc` |
| 运行一直 RUNNING | `run_ledger` 表、引擎日志 | 进程被杀；重启后 5 分钟内会被恢复任务标记 INTERRUPTED |
| 工作区列表不刷新 | Network `/events` | 代理缓冲；或事件流频繁重连（看后端是否 500） |
| `database is locked` | 磁盘 IO、并发写 | 已有 `busy_timeout=5000`；持续出现说明有长事务，查慢查询 |
| 部署失败 E-703 | 变更单/绑定 | 未携带人工确认的 `binding_id`，或确认令牌过期 |

## 6. 回滚

1. **平台自身**：保留上一版 `agent-gateway-*-exec.jar`，停止当前进程 → 替换 jar → 重启 → `actuator/health` 验证；数据库向后兼容（只增表/列），回滚前建议冷备 `_platform`。
2. **业务项目**：用平台「回滚」动作（`rollback`），语义见[发布编排](../发布编排与平台部署器-v1.md)；HOST 部署保留上一版本目录与 `app.log`。

## 7. 安全运维

- 密钥只存于密钥库（AES-GCM 加密），接口只回掩码；**不要**把密钥写进 `.env` 提交；
- 种子账号（admin/devops/viewer）上线前必须改密；
- 高危操作（`settings:reveal`、生产部署）需二级密码；
- 变更确认令牌一次性、与配方哈希绑定，泄漏窗口有限，但仍应在审计日志中核对确认人。
