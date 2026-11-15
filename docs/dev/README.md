# 开发指南

> 规约是硬约束，见[项目规约](../项目规约-v1.0.md)；机器可校验的部分由 `mvn verify` 强制执行。

## 1. 环境与启动

```bash
# 后端（编译 + 单测 + 规约检查）
cd platform && mvn -q verify

# 只启动网关（热重启靠手动；默认 agent.sandbox.mode=docker，本机无 Docker 时显式用 process）
mvn -q -pl agent-gateway -am -DskipTests package
AGENT_SANDBOX_MODE=process \
  java -jar agent-gateway/target/agent-gateway-0.1.0-SNAPSHOT-exec.jar

# 模型 API Key 无需在启动前注入：到「设置 → 模型连接」配置即可（或可选地注入 AGENT_MODEL_API_KEY）

# 前端
cd platform/web && npm install && npm run dev
```

## 2. 代码地图（按改动意图）

| 我要改… | 去这里 |
|---|---|
| 对话/工作区接口 | `agent-gateway/.../web/*Controller.java` |
| 模型可调用的工具 | `agent-tools/.../tools/*Tools.java`（按读写边界注册到 `ToolCatalog` 的对应 Toolkit，不再使用自定义分组） |
| 流水线阶段 | `agent-executor/.../PipelineDefinition.java`、`BuiltInPipelineEngine.java`、`PipelineDefinitionResolver.java` |
| 部署语义 | `agent-executor/.../Deployers.java`、`DockerDeployer.java`、`HostDeployer.java`、`ServerDeployer.java` |
| 沙箱与执行 | `agent-executor/.../DockerSandbox.java`、`DockerActionRunner.java`、`ProcessActionRunner.java` |
| 领域模型/端口 | `agent-domain/.../domain/**`（**禁止**引入 Spring） |
| SQLite 实现 | `agent-infra/.../infra/**` |
| 变更/令牌/补丁 | `agent-governance/**` |
| 前端页面 | `platform/web/src/views/*.vue`；接口只走 `src/api/*` |

## 3. 质量门禁

| 检查 | 命令 | 说明 |
|---|---|---|
| 架构与禁令 | `mvn -q verify` | ArchUnit A1/A2/A3/A4/A6/A7/A9、禁令 R01–R13、凭证扫描、迁移锁 |
| 单测 | 同上 | 失败即阻断；新增写工具需补门禁测试 |
| 前端类型 | `npm run typecheck` | |
| 前端构建 | `npm run build` | |
| 文档链接与锚点 | `node scripts/docs/check-links.mjs && node scripts/docs/check-docs.mjs` | 断链、未登记、CODE-MAP 锚点缺失均非 0 退出 |

误报处理：行尾加 `// spec-ignore: R06 <原因>`，并在[项目规约 §13](../项目规约-v1.0.md)登记；**不允许**为了过检查改规则。

## 4. 加一个工具的最小步骤

1. 新建/复用 `*Tools` 类，`@Tool(name=...)` + `@ToolParam`，明确级别（L0 只读 / L1 计划 / L2 隔离写 / L3 生产）；
2. 写操作**不得**直接执行，走 `ActionService`（plan → bindParams → run），否则规约测试会拦；
3. 注册到 `ToolCatalog` 对应 Toolkit：只读工具同时进 `readOnlyToolkit` 与 `supervisorToolkit`；**不要**给工具加自定义 group（会被整体过滤）；
4. 更新 Code Wiki §7 工具表与 [docs/README](../README.md) 健康看板；
5. 补一个「不可用路径」测试：缺参数/无权限/生产环境时应当拒绝。

## 5. 调试技巧

- 对话问题：先看 SSE 事件（`run.started` → `tool.started` → `tool.completed` → `run.completed`），再看 `run_ledger`；
- 流水线问题：`ci_dry_run` 先复现预检，再 `read_run_log` 拉日志尾部；
- 环境问题：`env_summary`（不返回值）+ `env_var_probe`（变量指向哪）；
- 前端状态不刷新：确认 `/events` 连接，再查 `useWorkspaceEvents` 的重连计数。

## 6. 常见坑

| 坑 | 说明 |
|---|---|
| 改共享 Toolkit 的激活组 | AgentScope 会话会缓存 `activatedGroups`，新会话写工具会「消失」；必须用独立 Toolkit（`ToolCatalog` 已按此设计） |
| 在 domain 里用 Spring 注解 | ArchUnit A2 直接失败 |
| 在 tools 里 `ProcessBuilder` | A7 失败；命令执行只在 executor 层 |
| 用 `double` 表示金额 | R06 失败，用 `BigDecimal` |
| 前端直接 `fetch('/api/...')` | 绕过统一错误处理与鉴权头，评审会打回 |
