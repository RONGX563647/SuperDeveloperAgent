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
| Python 3 | 会话归档门禁 / git hooks 依赖（`tools/session-archive/gate.py`，自动探测 `python` / `python3` / `py`） |
| git | `core.hooksPath=.githooks`（pre-commit / commit-msg / pre-push）；CI 与迁移守卫需要完整历史与 tag |

> **JDK 构建校验**：门禁 `tools/session-archive/gate.py` 在每次构建前探测满足 **JDK ≥ 21** 的 `JAVA_HOME`——环境变量优先，否则扫描常见安装位并选最贴近基线（release 21）的一个，探测结果缓存到 `.git/session-archive/jdk.json` 后续复用；只把结果注入构建子进程环境（`JAVA_HOME` + `PATH` 前置 bin），**不把 JDK 路径写进项目配置**。本机 JDK 低于 21 或缺失时门禁直接报错。

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
- surefire 并行度默认 `forkCount=2`（`platform/pom.xml` 的 `surefire.forkCount`），可用 `-Dsurefire.forkCount=N` 覆盖；会话归档门禁在核数 ≥8 的机器上显式提到 4。测试类各自用 `@TempDir`，可安全并发；但若 fork 出的 JVM 与编译目标 JDK 版本不一致（如旧 JVM 加载 class file 65.0），会直接崩，所以门禁统一先注入探测到的 JDK ≥21 再跑构建。
- 依赖安全：Spring Boot 4.0.4 BOM 默认带 Jackson 2.21.1（命中 GHSA-r7wm-3cxj-wff9 / GHSA-j3rv-43j4-c7qm 等），项目以 `jackson-bom.version=2.21.4` 覆盖（只影响 Jackson 2 的 `com.fasterxml.jackson.*`，Jackson 3 不受影响），保持 minor 线内兼容。
- Flyway（Boot 4）：迁移依赖需显式声明 `spring-boot-flyway`（配合 `flyway-core` 与 PG 的 `flyway-database-postgresql`，Flyway 10+ 按库拆模块）。迁移脚本在 `platform/agent-infra/src/main/resources/db/migration/{common,postgresql,sqlite}/`，V5-V8（`audit_event` / `image_readiness` / `drop_project_env_app_url` / `token_usage_attribution`）已补录进基线；改动已发布迁移会被迁移校验门禁拦截（含 CI 的 tag 守卫）。

### 2.1 模型配置

模型不是启动前置：

```text
设置页「模型连接」（AES-GCM 加密落库、热切换）
  → AGENT_MODEL_API_KEY 环境变量 / platform/.env
  → 都没有也能启动，仅调用模型时提示去配置
```

默认：`AGENT_MODEL_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`、`AGENT_MODEL_NAME=qwen-plus`。OpenAI 兼容端点均可（DeepSeek、vLLM 等）。

`.env` 加载规则：应用启动前由 `AgentApplication` 向上最多 3 层查找并读取 `.env`（K=V 每行一条，文件已 gitignore），**只在环境变量未设置时注入**——优先级为「真实环境变量 / CLI 系统属性 > `.env` > `application.yml` 默认值」，故 CLI 参数能强制覆盖 `.env` 里的同名（含空）值。PostgreSQL 连接可用 `AGENT_DB_URL` 直接配置，也可由设置页写入 `<workspace>/_platform/db-backend.yml`（密码 AES-GCM 密文、明文不入盘），启动时注入系统属性；已有真实环境变量 / CLI 参数不被覆盖。

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
- 镜像内置 `HEALTHCHECK`：用 curl 探 `/actuator/health`（30s 间隔、3s 超时、90s start-period、3 次重试，覆盖 Spring Boot + JPA + Flyway 冷启动）。`AGENT_WEB_DIST=/app/web` 指向内置前端产物（`COPY platform/web/dist/ /app/web/`），由网关 `StaticWebConfig` 单端口托管。

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

- **GitHub Actions** [`.github/workflows/ci.yml`](file:///workspace/.github/workflows/ci.yml)：五个 job — `backend`（mvn verify，带 PG16 容器在 CI 上也跑端到端用例）、`frontend`（typecheck + `pnpm check:api-types` 前端契约 + vitest）、`docs`（doc-marshal / 会话归档 / 视图一致性 / Handbook 漂移）、`contract`（api-docs-mcp 严格审计）、`security`（gitleaks + osv-scanner）。所有 checkout 用 `actions/checkout@v7.0.1`；`backend` job 显式 `fetch-depth: 0` + `fetch-tags: true`，因为迁移守卫要读已发布 tag（x.y.z）判断哪些迁移算已发布，浅克隆缺 tag 会退化成「全部按已发布」、未发布迁移被迫冻结。
- **Gitee Go** [`.workflow/build.yml`](file:///workspace/.workflow/build.yml)：构建兜底，只依赖四个稳定事实——`doc-marshal check --all`、`audit.mjs --strict` 前端契约审计、`mvn -B verify`、固定产物 `platform/agent-gateway/target/*-exec.jar`，可平移到任意 CI。
- 本地 `mvn -q verify` 会执行 spec-checks 的全部规约测试，与 CI 同口径。
