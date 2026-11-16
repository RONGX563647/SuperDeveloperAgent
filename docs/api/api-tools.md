---
type: reference
summary: API 文档快照、Agent 查询工具与 MCP 服务
code_refs:
  - platform/agent-gateway/src/main/java/top/rongx/agent/gateway/apidocs
  - platform/agent-tools/src/main/java/top/rongx/agent/tools/ApiDocTools.java
  - platform/agent-tools/src/main/java/top/rongx/agent/tools/AgentToolCatalog.java
  - platform/agent-tools/src/main/java/top/rongx/agent/tools/ToolCatalogTools.java
---

# API 文档工具与 MCP

## 1. 规范从哪里来

| 项 | 事实 |
|---|---|
| 唯一规范源 | springdoc 运行时扫描控制器生成的 OpenAPI 3 |
| 规范端点 | `GET /v3/api-docs`（JSON） |
| 文档 UI | `GET /doc.html`（springdoc 自带 Swagger UI，保留 Knife4j 习惯路径） |
| 为什么不是 Knife4j | Knife4j 4.5 绑定 springdoc 2.x，与 Spring Boot 4 / springdoc 3.x 不兼容 |
| 访问控制 | 默认要求登录 + `settings:read`；`AGENT_API_DOCS_PUBLIC=true` 仅本地联调放开 |
| 可审阅快照 | `platform/agent-gateway/src/main/resources/api/openapi.json`（构建期 golden file） |
| 运行时快照 | `data/api/openapi.json`（启动完成时刷新，Agent 工具默认读取） |
| 导出接口 | `GET /api/v1/api-docs/snapshot[?refresh=true]`（登录 + `settings:read`） |

`openapi.json` 不手工编辑。控制器变化后重新生成：

```bash
cd platform
mvn -pl agent-gateway -Dtest=ApiDocsSnapshotTest -Dapi.docs.update=true test
```

`ApiDocsSnapshotTest` 在普通 `mvn verify` 中比较生成结果与提交的快照，不一致即失败——文档与代码不会各说各话。

## 2. Agent 工具

`ToolCatalog` 把下列工具注册进只读工具面（生产会话、调度 Agent、发布 Agent 均可用）：

| 工具 | 入参 | 返回 |
|---|---|---|
| `api_search` | `keyword`、可选 `tag`、`limit` | method + path + 摘要 + tag；附 provenance |
| `api_get` | `method`、`path` 模板 | 参数、请求体、响应、状态码 |
| `api_schema` | 模型名 | `components.schemas` 字段定义 |
| `tool_search` | `keyword`、可选 `read_only`、`limit` | 工具名、readOnly、所属工具面 |
| `tool_get` | `name` | 工具完整参数定义 |

约束：

- 只读：不调用任何业务接口，查询文档不会产生副作用；
- 数据源优先级：`agent.api-docs.snapshot`（默认 `./data/api/openapi.json`）→ classpath `api/openapi.json`；
- 每次返回 `source` / `generatedAt` / `spec version` / `operations`，便于判断答案是否基于过期快照；
- 性能：按文件 mtime + 大小缓存解析结果；`tool_get` 的目录来自运行时真实 Toolkit，不是源码扫描。

## 3. 工具目录快照

`AgentToolCatalog` 在应用就绪后把四套 Toolkit（`readOnlyToolkit` / `agentToolkit` / `supervisorToolkit` / `deployToolkit`）导出为 `data/api/agent-tools.json`：

- `name`、`description`、`parameters`、`readOnly`、`toolkits`；
- 写盘失败只告警，不影响启动；
- 外部 MCP 与 `tool_search` / `tool_get` 读同一份结构。

## 4. MCP 服务器

位置：`tools/api-docs-mcp/`（Node 20+，`@modelcontextprotocol/sdk`）。

工具：`search_api`、`get_api`、`api_schema`、`list_tags`、`tool_search`、`tool_get`。

数据源二选一：

```bash
# 1) 读快照（离线、零网络）
node server.js --file ../../platform/agent-gateway/src/main/resources/api/openapi.json \
               --tools ../../platform/data/api/agent-tools.json

# 2) 读运行中的平台（文档端点需要 token）
node server.js --url http://127.0.0.1:8090/v3/api-docs --token <Sa-Token>
```

等价环境变量：`API_DOCS_FILE`、`API_DOCS_URL`、`API_DOCS_TOKEN`、`AGENT_TOOLS_FILE`。

边界：只读规范文件或文档端点，**不调用业务接口、不缓存业务数据**；每次响应带 provenance。
可直接填入平台「设置 → MCP」的 stdio 配置，或 Claude Code / Cursor 的 MCP 配置。

## 5. 文档契约

`spec-checks` 的 `ApiDocContractTest` 要求每个映射方法有 Javadoc 或 `@Operation(summary=...)`，用 `api-doc-baseline.txt` 做存量棘轮：

- 新增接口没有文档 → 构建失败；
- 基线接口补齐文档 → 必须删除基线行，否则构建失败。

基线只减不增；当前存量缺口在 `platform/spec-checks/api-doc-baseline.txt`。
