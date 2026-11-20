---
type: code-wiki-build-run
summary: 环境要求、后端/前端构建与启动、Docker 部署、关键环境变量、配置项速查与常见故障
---

# 构建与运行

## 1. 环境要求

| 依赖 | 版本 / 说明 |
|---|---|
| JDK | 21（`mvn -v` 自检；目标字节码默认 21，可用 `-Djava.release=25` 覆盖） |
| Maven | 3.9+（项目无 wrapper，需本机安装） |
| Node.js | 22+（前端工具链要求） |
| 包管理 | npm 或 pnpm 9.15.4（`packageManager` 已声明） |
| Docker | 可选但**默认必需**（`agent.sandbox.mode=docker`，Docker 不可用则启动失败） |
| 数据库 | 默认 SQLite（零运维）；生产建议 PostgreSQL |
| Python 3 | 会话归档门禁与 git hooks 依赖（钩子自动探测 `python`/`python3`/`py`） |
| git | `core.hooksPath=.githooks`；迁移守卫/CI 需要完整历史与 tag |

首次准备（克隆后）：

```bash
python tools/session-archive/gate.py install-hooks   # 或 tools/session-archive/install-hooks.ps1
python tools/session-archive/gate.py doctor          # 环境自检
```

门禁会自动探测 JDK21+（`JAVA_HOME` 优先，否则扫常见安装位并缓存到 `.git/session-archive/jdk.json`）。

## 2. 后端

```bash
cd platform

# 完整校验：编译 + ArchUnit/禁令/凭证/迁移锁 + 单测
mvn -q verify

# 只打包
mvn -q -DskipTests package

# 运行（JDK 24+ 需 native-access，sqlite-jdbc 加载本地库）
java --enable-native-access=ALL-UNNAMED \
  -jar agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT-exec.jar
```

- 端口：`8090`。
- 健康检查：`http://127.0.0.1:8090/actuator/health`。
- jar 带 `-exec` 后缀：`repackage` 使用 classifier，主 artifact 保持普通 jar（否则 ArchUnit 读不到类）。
- 也可直接 `mvn -pl agent-gateway -am spring-boot:run` 开发调试。

### 2.1 模型配置

模型不是启动前置：

```text
设置页「模型连接」（AES-GCM 加密落库、热切换）
  → AGENT_MODEL_API_KEY 环境变量 / platform/.env
  → 都没有也能启动，仅调用模型时提示去配置
```

默认：`AGENT_MODEL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`、`AGENT_MODEL_NAME=qwen-plus`。OpenAI 兼容端点均可（DeepSeek、vLLM 等）。

`.env` 加载规则：应用启动时读取 `platform/.env`，真实环境变量优先。

## 3. 前端

```bash
cd platform/web
npm install          # 或 pnpm install
npm run dev          # http://localhost:5173，/api 代理到 127.0.0.1:8090
npm run build        # vue-tsc -b && vite build → dist/
```

> Vite 默认绑 `::1`；用 `127.0.0.1` 打不开时加 `-- --host 127.0.0.1`。

生产可由网关 `StaticWebConfig` 托管 `dist/`（`AGENT_WEB_DIST`），实现单端口部署。

## 4. 登录与初始账号

首次启动 seed 三个账号：

| 账号 | 角色 | 初始密码 |
|---|---|---|
| `admin` | 管理员 | `admin-2026` |
| `devops` | 运维开发 | `admin-2026` |
| `viewer` | 只读 | `admin-2026` |

- 登录后到「设置 → 账号安全」修改。
- 覆盖：`AGENT_SEED_PASSWORDS=admin=...,devops=...,viewer=...`。
- 仅在 `users` 表为空（首次启动）时生效。

## 5. Docker 部署

[Dockerfile](file:///workspace/Dockerfile)：

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY platform/agent-gateway/target/*-exec.jar /app/app.jar
COPY platform/web/dist/ /app/web/
ENV AGENT_WEB_DIST=/app/web
ENV SERVER_PORT=8090
EXPOSE 8090
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

> **为什么是 `*-exec.jar`**：gateway 的 `spring-boot-maven-plugin` repackage 声明了
> `<classifier>exec</classifier>`，`target` 下同时存在普通包（无 `Main-Class`）与
> `*-exec.jar`（可执行包）。写成 `target/*.jar` 会拷到普通包，容器启动即报
> `no main manifest attribute, in /app/app.jar`；两个都匹配时 Docker 还会因
> "多源复制到文件目标"直接构建失败。

构建前置：先完成 `mvn package` 与 `npm run build`（镜像只 COPY 产物）。

```bash
docker build -t agent-platform .
docker run -d --name agent-platform \
  -p 8090:8090 \
  -v $(pwd)/data:/app/data \
  -e AGENT_SECURITY_MASTER_KEY=<Base64-32B> \
  -e AGENT_MODEL_API_KEY=<key> \
  agent-platform
```

注意：

- `/app/data` 必须持久化（库、工作区、会话状态、部署产物）。
- 若容器内要执行构建，需要挂载 Docker socket 或改用 remote buildkitd（`AGENT_SANDBOX_DOCKER_BINARY` 可指向 podman）。
- `AGENT_SECURITY_MASTER_KEY` 丢失将无法解密历史密文。

## 6. 关键环境变量

### 数据库 / 安全

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_DB_URL` | 空（SQLite） | PostgreSQL JDBC URL |
| `AGENT_DB_USERNAME` / `AGENT_DB_PASSWORD` | 空 | 数据库账号 |
| `AGENT_SQLITE_FILE` | `./data/workspaces/_platform/platform.db` | SQLite 路径 |
| `AGENT_SECURITY_MASTER_KEY` | 自动生成文件 | AES-256 主密钥（Base64 32B） |
| `AGENT_SEED_PASSWORDS` | 空 | 初始账号密码覆盖 |

### 模型

| 变量 | 默认 |
|---|---|
| `AGENT_MODEL_BASE_URL` | DashScope 兼容端点 |
| `AGENT_MODEL_API_KEY` | 空 |
| `AGENT_MODEL_NAME` | `qwen-plus` |
| `AGENT_MODEL_MAX_ITERS` | `12` |
| `AGENT_MODEL_PREFIX_CACHE` | `true` |

### 沙箱 / 流水线

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_SANDBOX_MODE` | `docker` | `docker` 或 `process`（仅本地开发） |
| `AGENT_SANDBOX_IMAGE` | `maven:3.9-eclipse-temurin-21` | 工具链镜像 |
| `AGENT_SANDBOX_NETWORK` | `bridge` | `bridge` / `build-net` / `none` |
| `AGENT_SANDBOX_RUNTIME` | 空 | 如 gVisor `runsc` |
| `AGENT_SANDBOX_NPM_REGISTRY` | `https://registry.npmmirror.com` | npm 源 |
| `AGENT_SANDBOX_CACHE_VOLUME` | `agent-cache` | 依赖缓存卷前缀 |
| `AGENT_SANDBOX_PULL_TIMEOUT_SECONDS` | `900` | 镜像拉取总时长上限（秒） |
| `AGENT_SANDBOX_PULL_IDLE_TIMEOUT_SECONDS` | `60` | 拉取停滞上限（秒无输出即判停滞） |
| `AGENT_SANDBOX_PULL_ATTEMPTS` | `2` | 每次供给的尝试次数 |
| `AGENT_SANDBOX_PULL_BACKOFF_SECONDS` | `60` | 失败退避起始值（按镜像指数增长） |
| `AGENT_SANDBOX_PULL_BACKOFF_MAX_SECONDS` | `900` | 退避上限；窗口内本机直接失败返回 |
| `AGENT_SANDBOX_PULL_MIRRORS` | 空 | 拉取加速源（逗号分隔，只对 Docker Hub 镜像生效） |
| `AGENT_SANDBOX_HOST` | 空 | 镜像就绪状态的主机维度键，多机共用一库时用来区分 |
| `AGENT_PIPELINE_AUTO_REPAIR` | `true` | 失败自动修复 |
| `AGENT_PIPELINE_AUTO_REPAIR_ATTEMPTS` | `2` | 修复轮次上限 |
| `AGENT_PIPELINE_AUTO_RESUME` | `true` | 修复后自动续跑 |

### 对话 / 上下文

| 变量 | 默认 | 说明 |
|---|---|---|
| `AGENT_CHAT_LIMITER` | `memory` | `memory` / `jdbc`（多实例） |
| `AGENT_CHAT_MAX_CONCURRENT` | `8` | 单用户并发流 |
| `AGENT_CHAT_TIMEOUT_MS` | `180000` | 普通流超时 |
| `AGENT_CHAT_SUPER_TIMEOUT_MS` | `600000` | 调度流超时 |
| `AGENT_CHAT_HEARTBEAT_SECONDS` | `15` | SSE 心跳 |
| `AGENT_CONTEXT_MAX_CHARS` | `80000` | 上下文预算 |
| `AGENT_TOOLS_MAX_OUTPUT_CHARS` | `8000` | 工具输出预算 |
| `AGENT_TOOLS_AUTO_ACTIVATE` | `session` | 插件自动激活策略 |

### Git / 接入

| 变量 | 说明 |
|---|---|
| `AGENT_INTAKE_SHARED_ROOT` | 接入目录浏览边界（留空=全盘） |
| `AGENT_GITEE_TOKEN` | 平台兜底 Gitee token |
| `GITEE_CLIENT_ID` / `GITEE_CLIENT_SECRET` | Gitee OAuth |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | GitHub OAuth |
| `GITLAB_CLIENT_ID` / `GITLAB_CLIENT_SECRET` | GitLab OAuth |

## 7. 运行数据

默认根目录 `./data/workspaces`：

| 路径 | 内容 |
|---|---|
| `<项目>/repo`、`<项目>/worktrees/*` | 源码 / git 工作树 |
| `<项目>/recipes/<env>/vN/` | 跑通后固化的环境配方 |
| `<项目>/_history/<runId>.yml` | 构建历史 |
| `_platform/platform.db` | 统一库（SQLite） |
| `_platform/deploy/<project>-<env>/` | HOST 发布状态 |
| `_platform/local/` | dev 本机进程日志 |
| `_platform/agent-state/` | 会话状态密文 |

## 8. 常见故障

| 现象 | 原因 / 处理 |
|---|---|
| 启动即失败，日志提示 Docker 不可用 | 默认 fail-closed。启动 Docker，或本地显式 `AGENT_SANDBOX_MODE=process` |
| 模型调用 401/超时 | 未配置 Key 或 base-url 不对；去设置页测试连接 |
| SQLite 报 `NoSuchMethod` / native 错误 | JDK 24+ 缺少 `--enable-native-access=ALL-UNNAMED` |
| 前端 401 不断跳登录 | Token 过期或后端重启；重新登录 |
| 构建阶段镜像缺失 | 平台会预检并自动拉取；离线环境先 `docker pull` 或改镜像配置 |
| 部署提示 `E-505` | 目标部署器未配置（如 SERVER 未接 SSH） |
| 确认后仍提示 `E-602` | 令牌过期（5 分钟）或 `planHash` 变了（配方改动），需重新确认 |
| 迁移校验失败 | 有人改了已发布迁移文件；恢复原文件或新增版本 |
| 端口冲突 | 改 `server.port` 或 `SERVER_PORT` |

## 9. CI

两条流水线详见 [质量门禁 §6](11-quality-and-tooling.md)：GitHub Actions（路径过滤 + backend/frontend/docs/contract/security 五 job，backend 带真 PG16 容器）与 Gitee Go（doc-marshal + 契约审计 + `mvn verify`，四稳定事实可平移）。本地 `verify` 会执行 spec-checks 的全部规约测试。
