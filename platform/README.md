# 平台代码（AgentScope Java 2.0 + Vue 3）

内网研发运维智能体平台的实现。**不是骨架**：项目接入、动作契约、流水线、环境注入、部署与回滚、受限补丁、失败恢复均已落地。

> 依据：[项目规约 v1.0](../docs/项目规约-v1.0.md) ｜ [PRD v1.6](../docs/PRD-DevOps-Agent平台-v1.0.md) ｜ [执行器契约](../docs/规约-流水线执行器-v1.0.md) ｜ [Code Wiki](../docs/Code-Wiki.md)

---

## 模块

```
platform/
├── pom.xml                 父 POM（Boot 4.0.4 + AgentScope 2.0.0 BOM）
├── agent-domain/           实体、枚举与端口（零框架依赖）
├── agent-infra/            配方解析 / git worktree / SQLite / 加密 / 补丁
├── agent-governance/       动作门禁 + 确认令牌 + 变更/补丁登记
├── agent-executor/         执行面（动作执行器 / 流水线引擎 / 沙箱 / 部署器 / 环境供给）
├── agent-tools/            31 个 @Tool + 门禁编排（ActionService）
├── agent-runtime/          Agent 工厂 + 模型装配 + 对话服务
├── agent-gateway/          Spring Boot 入口 + HTTP/SSE + Sa-Token
├── spec-checks/            规约自动化检查（仅 src/test，随 mvn verify 执行）
└── web/                    Vue3 + Vite + Element Plus
```

依赖方向严格单向（Gateway → Runtime → Tools/Governance → Domain ← Executor/Infra），由 `spec-checks` 的 ArchUnit 规则在 `mvn verify` 时强制。

---

## 跑起来

**前置**：JDK 21、Maven 3.9+、Node 20+；Docker 可选（沙箱构建、容器/中间件部署；无 Docker 时 L2 可回退宿主，L3 与容器部署 fail-closed）。

### 后端

```bash
cd platform
export AGENT_MODEL_API_KEY=sk-xxx          # Windows: $env:AGENT_MODEL_API_KEY="sk-xxx"
export AGENT_MODEL_NAME=qwen-plus          # 可选，默认 qwen-plus
# 也可写入 platform/.env：应用启动时自动加载，真实环境变量优先

mvn -q verify                              # 编译 + 规约检查 + 单测（推荐先跑）
mvn -q -DskipTests package                 # 只打包

java -jar agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT-exec.jar
# 健康检查：http://127.0.0.1:8090/actuator/health
```

> jar 名带 `-exec`：`repackage` 用了 `classifier`，主 artifact 保持普通 jar（否则 ArchUnit 读不到 gateway 的类）。

模型走 **OpenAI 兼容端点**，默认阿里云 DashScope；换 DeepSeek / 自建 vLLM 可在设置页热切换，或改 `AGENT_MODEL_BASE_URL`。

### 前端

```bash
cd platform/web
npm install
npm run dev        # http://localhost:5173（/api 代理到 127.0.0.1:8090）
npm run build      # vue-tsc strict + 产物
```

> Vite 默认绑 `::1`，用 `127.0.0.1` 打不开属正常；需 IPv4 用 `npm run dev -- --host 127.0.0.1`。

登录种子账号：`admin / admin-2026`（管理员）、`devops / devops-2026`（运维开发）、`viewer / viewer-2026`（只读）。

### 运行数据

默认根目录 `./data/workspaces`（`agent.workspace.root` 可覆盖）：

| 路径 | 内容 |
|---|---|
| `<项目>/repo`、`<项目>/worktrees/*` | 接入项目的源码 / git 工作树 |
| `<项目>/recipes/<env>/vN/` | 某环境跑通后固化的脚本（可重放 / 回滚） |
| `<项目>/_history/<runId>.yml` | 构建历史（重启可恢复） |
| `_platform/projects.db` | 项目、环境模板/资源/变量、设置等 SQLite |
| `_platform/deploy/<project>-<env>/` | HOST 部署状态：`releases/<sha>/`、`current`、`previous`、`app.pid`、`app.log` |
| `_platform/local/` | dev 本机前后端进程日志（`app_logs` 读取） |
| `data/agent-state/` | Agent 会话状态（按 userId + sessionId 续聊） |

---

## 当前能力

| 能力 | 状态 |
|---|---|
| 对话 + SSE 流式 | ✅ `POST /api/v1/chat`；三套 Toolkit 按环境切换（生产只读 / 非生产全量 / 发布 Agent） |
| 动作契约（方案 D） | ✅ `list_actions` / `plan_action` / `run_action`；配方优先级 `ci/actions.yml → Taskfile.yml → Makefile → 内置默认`，命令 argv 直 exec |
| L3 审批闭环 | ✅ `request` → `/changes/{id}/confirm` 拿一次性令牌（5 分钟、绑定 planHash）→ `/changes/{id}/apply` |
| 流水线引擎 | ✅ checkout 指定 sha → 构建/测试 → 镜像 → 部署 → 健康 → 失败 `on-failure`；命令级预览、**dry-run 预检门控（E-708/E-706）**、失败续跑、产物下载、历史删除 |
| dev 本机部署 | ✅ dev **固定本机**（后端 jar + 前端 dist），显式传 `deployTarget=docker` 也不生效；可一键停止交还 IDE |
| test / prod 部署 | ✅ 容器替换式发布 / SSH 远程发布；部署前中间件按需供给、Docker 网络确保、端口租约 |
| 环境子系统 | ✅ 环境模板、中间件发现/登记/启停、项目覆盖、有效视图；**项目密钥库（`secret://` 引用、轮换、缺失 E-704 拦截、运行日志值级脱敏）**；**配置解析（项目环境优先 → 仓库 `.env` → 全量扫描，缺失主动询问，候选可人工采纳）**；部署前 E-509 必填 / E-701 资源 / E-702 供给预检 |
| 分环境策略 | ✅ `dev/test/prod × L0–L3` 矩阵（dev L3 禁止、test L3 需确认、prod L2 禁止/L3 需确认），`GET /api/v1/policies` 只读查看 |
| 项目接入 | ✅ 本地 DIRECT（不复制仓库）/ 远程 clone（bare + worktree）；配方自检 + 平台侧生成 `ci/` 定义 |
| 受限补丁 | ✅ `propose_patch` 只登记变更单；人工确认后提交到 `patch/<projectId>` 独立分支，永不 push |
| 失败恢复 | ✅ 规则化 `RunRecoveryAdvisor` 给出根因/候选动作/是否可补丁修复；`POST /deploy/{projectId}/agent` 一键自动部署（仅 dev/test） |
| 运行时取证 | ✅ `env_runtime_status`（容器/进程事实）、`app_logs`（docker logs / HOST `app.log` / dev 进程日志）、`port_check` |
| 认证与授权 | ✅ Sa-Token（Bearer，30 天）、角色权限码、设置面板读写与明文回显（`settings:reveal` + 审计） |
| Gitee | ✅ OAuth / PAT 绑定、仓库浏览、push webhook 触发 verify（Token/HMAC + 同 commit 幂等） |
| 设置 | ✅ 服务器与模型连接 CRUD/测试/默认切换，密钥 AES-GCM 加密落库、接口只回掩码；**项目密钥库 CRUD/轮换** |

## HTTP API 与工具

- 完整接口清单见 [Code Wiki §6](../docs/Code-Wiki.md#6-http-api-一览)；前缀 `/api/v1`，错误统一 `{code, message, traceId}`。
- 主要入口：`/chat`（SSE）、`/actions`、`/changes`、`/ci/*`、`/deploy/*`、`/projects/*`、`/settings/*`、`/gitee/*`、`/webhooks/gitee/*`。
- 模型工具：33 个 `@Tool` 方法 / 12 个工具类，分 L0 只读、L1 计划、L2 隔离执行、L3 生产四级；新增只读工具 `config_resolve`（配置解析/主动询问）与 `ci_dry_run`（部署预检）；**没有 `confirm` 工具**，确认只能由人经 HTTP 完成。

## 规约检查（已接入构建）

`mvn verify` 会跑 `spec-checks` 的 4 个测试类：ArchUnit 架构规则（A1 分层、A2 domain 零框架、A3 executor 无 LLM、A4 决策面无容器客户端、A6 禁 util 包、A7 tools 禁 ProcessBuilder、A9 禁字段注入）+ 禁令扫描（R01–R13）+ 凭证扫描 + 迁移锁。

任一违规**直接让构建失败**，输出形如：

```
[R06] agent-domain/src/main/java/.../OrderBo.java:42  金额/费用禁止用 double：必须用 BigDecimal
        规约条款见《规约自动化检查》§2；如为误报请加 `// spec-ignore: R06 <原因>` 并登记项目规约 §13
```

详见 [spec-checks/README.md](spec-checks/README.md)。CI 流水线见 [`.workflow/build.yml`](../.workflow/build.yml)（Gitee Go；本质只是跑一条 `mvn -B verify`，换任何 CI 都能用）。

## 已知边界（不是遗漏）

1. **没有 `confirm` 工具**——确认只能由人在 HTTP 接口完成（规约 §4.10 第一道守卫）；
2. **变更单 / 待确认补丁为内存版**——重启后未应用的提案丢失，生产需落库；
3. **用户体系为内存版**（3 个种子账号），未接数据库；多租户未做；
4. **`GET /api/v1/fs/read` 未实现**——前端文件预览调用该端点，后端只有 ls/probe/roots；
5. **事件映射用反射**——正式版应把 AgentScope 事件映射成稳定枚举；
6. **`changeId` 由前端正则提取**——应由后端发专用 SSE `change` 事件；
7. **沙箱不注入私服凭据**——需要时挂只读 `settings.xml`；
8. **窄控制器 8 步滚动发布未做**——当前 deploy 由平台部署器 / 仓库配方提供。

## 下一步（按优先级）

| # | 任务 | 对应规约 |
|---|---|---|
| 1 | 变更单 / 补丁 / 用户体系落库 | 项目规约 §5 |
| 2 | 用户管理界面与多租户 | PRD §4.10 |
| 3 | 专用 `build-net` + 防火墙规则限制构建容器访问内网 | 执行器契约 §网络策略 |
| 4 | 后端发专用 `change` SSE 事件，去掉前端正则提取 | PRD §4.10 |
| 5 | 窄控制器 8 步滚动发布 | 窄控制器设计 |
