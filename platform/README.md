# 平台骨架（AgentScope Java + Vue3）

> 依据：[项目规约 v1.0](../docs/项目规约-v1.0.md) ｜ [PRD v1.6](../docs/PRD-DevOps-Agent平台-v1.0.md) ｜ [执行器契约](../docs/规约-流水线执行器-v1.0.md)

已完成的是**可运行的纵向切片**：浏览器 → SSE → Agent → 工具（只读/变更请求）→ 确认令牌。
流水线执行器、部署器、迁移器是**接口 + 空实现**，按契约补实现即可，不用改上层。

---

## 目录

```
platform/
├── pom.xml                 父 POM（Boot 4.0.4 + AgentScope 2.0.0 BOM）
├── agent-domain/           实体与端口（零框架依赖）
├── agent-infra/            仓储实现（当前内存版）
├── agent-governance/       动作门禁 + 确认令牌 + 变更登记
├── agent-executor/         执行面（PipelineEngine 接口 + Noop 实现）
├── agent-tools/            @Tool 实现 + Toolkit 装配
├── agent-runtime/          Agent 工厂 + 模型装配 + 对话服务
├── agent-gateway/          Spring Boot 入口 + SSE + 确认接口
├── spec-checks/            规约自动化检查（随 mvn verify 执行）
└── web/                    Vue3 + Vite + Element Plus
```

依赖方向严格单向，且 `agent-domain` 不 import 任何框架——由 `spec-checks` 的 ArchUnit 规则卡住（见下）。

---

## 跑起来

**前置**：JDK 21、Maven 3.9+、Node 20+。

### 后端

```bash
cd platform
export AGENT_MODEL_API_KEY=sk-xxx          # Windows: $env:AGENT_MODEL_API_KEY="sk-xxx"
export AGENT_MODEL_NAME=qwen-plus          # 可选，默认 qwen-plus

mvn -q verify                              # 编译 + 规约检查 + 单测（推荐先跑这个）
mvn -q -DskipTests package                 # 只打包

java -jar agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT-exec.jar
```

> 注意 jar 名带 `-exec`：`repackage` 用了 `classifier`，主 artifact 保持普通 jar（否则 ArchUnit 读不到 gateway 的类）。

模型走 **OpenAI 兼容端点**，默认阿里云 DashScope；换 DeepSeek / 自建 vLLM 只改 `AGENT_MODEL_BASE_URL`。

### 前端

```bash
cd platform/web
npm install        # 或 pnpm install
npm run dev        # http://127.0.0.1:5173
```

Vite 已把 `/api` 代理到 `127.0.0.1:8090`。

---

## 动作 HTTP API（不依赖模型，可直接 curl）

```bash
# 1) 看这个项目能做什么
curl -H "X-User-Id: dev" "http://127.0.0.1:8090/api/v1/actions?projectId=RY&env=test"

# 2) 看某个动作会执行什么（命令原文）
curl -H "X-User-Id: dev" "http://127.0.0.1:8090/api/v1/actions/backend_build/plan?projectId=RY"

# 3) 执行 L2 动作（构建类，生产环境会被拒）
curl -X POST -H "X-User-Id: dev" \
  "http://127.0.0.1:8090/api/v1/actions/backend_build/run?projectId=RY&env=test"

# 4) L3 动作三步走：登记 → 确认 → 执行
CHG=$(curl -s -X POST -H "X-User-Id: dev" \
  "http://127.0.0.1:8090/api/v1/actions/stack_deploy/request?projectId=RY&env=test" | jq -r .changeId)
TOKEN=$(curl -s -X POST -H "X-User-Id: dev" -H "Content-Type: application/json" -d '{}' \
  "http://127.0.0.1:8090/api/v1/changes/$CHG/confirm" | jq -r .confirmToken)
curl -X POST -H "X-User-Id: dev" -H "X-Confirm-Token: $TOKEN" \
  "http://127.0.0.1:8090/api/v1/changes/$CHG/apply"
```

> 配方放在项目工作区 `<workspaceRoot>/<projectId>/ci/actions.yml`（默认 `./data/workspaces`）。

---

## 当前能做到什么

> ✅ 下表已在 **JDK 21.0.2 + Maven 3.9.16 + AgentScope 2.0.0 + Spring Boot 4.0.4** 实际编译并启动验证。

| 能力 | 状态 |
|---|---|
| 8 个模块编译 + 可执行 jar（53 MB） | ✅ `mvn -DskipTests package` 全绿 |
| 启动 + `/actuator/health` = UP | ✅ 已验证 |
| 对话 + SSE 流式 | ✅ 可用（`POST /api/v1/chat`），实测能收到 `AgentStartEvent` / `ModelCallStartEvent` |
| 项目上下文服务端绑定 | ✅ 可用（`ProjectContext` 经 `RuntimeContext` 注入，模型无法提供 `projectId`） |
| 只读工具 | ✅ `project_info`、`list_envs`、`ci_list_pipelines`、`ci_status` |
| **方案 D：动作契约 + 仓库内配方** | ✅ `list_actions` / `plan_action` / `run_action`；配方解析顺序 `ci/actions.yml → Taskfile.yml → Makefile → 内置默认`；实测跑通"读配方 → 执行 → 产物 sha256" |
| **动作 HTTP 接口（不经模型也能用）** | ✅ `GET /api/v1/actions`、`…/{action}/plan`、`POST …/{action}/run`、`POST …/{action}/request` |
| **L3 审批闭环** | ✅ `request` → `/changes/{id}/confirm` 拿令牌 → `/changes/{id}/apply` 执行；令牌一次性、5 分钟、绑定 `planHash` |
| **可用性测试** | ✅ 11 例（列表/计划/成功/失败/L3 拦截/令牌复用/配方变更/跨单令牌），`mvn verify` 全绿 |
| **容器沙箱执行** | ✅ 非 root（uid 1000）/ `--network none` 断网 / `--cap-drop ALL` / 内存·CPU·pids 限额；`auto` 自动探测，无 Docker 时回退宿主并告警 |
| 变更请求（不能自确认） | ✅ `request_confirmation` 产出 `changeId` + `planHash` |
| 人工确认 → 一次性令牌 | ✅ `POST /api/v1/changes/{id}/confirm`（5 分钟、绑定 planHash、用后失效） |
| 统一错误响应（含 traceId） | ✅ 已验证：`{code, message, traceId}` |
| 模型调用链路 | ✅ 已验证到 provider：填错 key 会收到 DashScope 的 401，并以 `event:error` 优雅返回 |
| 流水线执行器（旧 `PipelineEngine`） | ⛔ `NoopPipelineEngine`，只服务 `ci_run` 工具；真实执行走 `run_action` |
| 账号体系 / 登录 | ⛔ 骨架用 `X-User-Id` 头；高危确认失败关闭 |

### 编译/启动时踩到的两个坑（已修复）

| 坑 | 现象 | 修复 |
|---|---|---|
| **AgentScope 2.0.0 没有 `Toolkit.addToolToGroup`** | 编译报"找不到符号"（本地 2.1.0-SNAPSHOT 有这个方法，2.0.0 没有） | 改用官方文档的流式注册：`toolkit.registration().tool(obj).group("write").apply()` |
| **Spring Boot 4 默认 Jackson 3，容器里没有 Jackson 2 的 `ObjectMapper`** | 启动失败：`No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'` | 显式定义 `Jackson2Config#jackson2ObjectMapper`（AgentScope 事件按 Jackson 2 注解，必须用 Jackson 2 序列化） |

> 第二个坑正是之前分析里预警过的"Jackson 2 / 3 并存"——**Boot 4 下 AgentScope 的 starter 不能想当然直接注入 `ObjectMapper`**。

---

## 规约检查（已接入构建）

`mvn verify` 会跑 `spec-checks` 模块的 10 个测试（ArchUnit 7 条架构规则 + 禁令扫描 + 凭证扫描 + 迁移锁）。
任一违规**直接让构建失败**，输出形如：

```
[R06] agent-domain/src/main/java/.../OrderBo.java:42  金额/费用禁止用 double：必须用 BigDecimal
        规约条款见《规约自动化检查》§2；如为误报请加 `// spec-ignore: R06 <原因>` 并登记项目规约 §13
```

详见 [spec-checks/README.md](spec-checks/README.md)。CI 流水线见 [`.workflow/build.yml`](../.workflow/build.yml)（Gitee Go；本质只是跑一条 `mvn -B verify`，换任何 CI 都能用）。

## 刻意留白（不是遗漏）

1. **没有 `confirm` 工具**——确认只能由人在 HTTP 接口完成（规约 §4.10 第一道守卫）；
2. **`PipelineEngine` 是空实现**——`ci_run` 走的是它；真正的执行走 `run_action`（方案 D）；
3. **沙箱不注入凭据**——私服/DB 凭据还没挂进容器，需要时挂只读 `settings.xml`（下一阶段）；
4. **`stack_deploy` / `stack_rollback` 没有配方**——它们要接窄控制器，属下一阶段；
5. **高危密码校验失败关闭**——账号体系未接入时 `FailClosedPasswordVerifier` 一律拒绝，而不是放行；
6. **事件映射用反射**——骨架阶段够用；正式版应按 32 类事件映射成稳定枚举；
7. **`changeId` 由前端从文本里正则提取**——临时手段，应由后端发专用 SSE 事件。

---

## 下一步（按优先级）

| # | 任务 | 对应规约 |
|---|---|---|
| 1 | 依赖缓存卷（构建期断网也能跑 `mvn`/`pnpm`） | 流水线设计 §13 |
| 2 | 前端把动作面板接上（接口已就绪，见上文 curl） | PRD §4.10 |
| 3 | 账号体系（本地账号 + BCrypt）替换 `X-User-Id` | PRD §4.10 |
| 4 | 会话与消息落库（替换内存） | 项目规约 §5 |
| 5 | 容器内注入私服凭据（只读挂载 settings.xml） | 执行器契约 §1.7 |
