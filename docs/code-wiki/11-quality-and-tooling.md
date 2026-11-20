---
type: code-wiki-quality-tooling
summary: 规约检查（ArchUnit/禁令/错误码/迁移/文档/归档/工具预算）与工程脚本、CI、文档规范
---

# 质量门禁与工程工具

这个仓库把"架构与文档约束"做成可执行检查，而不是靠评审记忆。核心入口：

```bash
cd platform && mvn -q verify
```

## 1. spec-checks 概览

模块只有 `src/test`，不产出运行时依赖。包含两类辅助类（`ForbiddenPatternScan`、`MigrationBaseline`）与 11 个测试类。

### 1.1 架构规则（ArchitectureRulesTest）

| 规则 | 内容 |
|---|---|
| A1 | 分层依赖单向，禁止反向/环 |
| A2 | `agent-domain` 零框架依赖（无 Spring/MyBatis/AgentScope） |
| A3 | `agent-executor` 不依赖模型 SDK |
| A4 | 决策面（runtime/governance）不依赖容器客户端 |
| A6 | 禁止 `util` 包 |
| A7 | `agent-tools` 禁止 `ProcessBuilder`（执行必须走 executor） |
| A9 | 禁止字段注入（必须构造器注入） |

规则即代码：新增模块或包时，先看这个测试，避免"写了才发现依赖方向错"。

### 1.2 禁令扫描（ForbiddenPatternScanTest，R01–R13）

扫描源码中的已知危险模式，例如：

- 金额/额度用 `double`（应使用整数最小单位或 `BigDecimal`）。
- `Runtime.exec` / 自行拼 shell。
- `new Thread`（应使用虚拟线程/线程池）。
- 可疑的 `System.out`、吞异常等。

`RuleFixtureSelfTest` 保证扫描器本身对正/反样例判定正确。

### 1.3 错误码契约

- `ErrorCodeUniquenessTest`：错误码不得重复。
- `ErrorCodeContractTest`：业务代码不得裸抛字面量错误码，必须引用 `ErrorCode` 常量；码段必须合法。

### 1.4 迁移锁（MigrationGuardTest + MigrationBaseline）

- 已发布迁移文件不可修改（内容哈希登记在 `MigrationBaseline`）。
- 新增 schema 变更必须新增版本号，不能改旧文件。

### 1.5 文档与归档

| 测试 | 检查 |
|---|---|
| `HandbookDriftTest` | Handbook L3 的"行为 → 源码行号"与代码锚点一致 |
| `ProcessArchiveTest` | 会话归档的必需章节/结构完整 |

### 1.6 工具面预算

`ToolSchemaBudgetTest`：把工具 schema 的 token 预算纳入构建。新增工具若让常驻 schema 超预算，必须放进插件或调整披露策略。

### 1.7 API 契约

`ApiDocContractTest`：控制器与实际 OpenAPI 快照一致，防止接口改了文档没改。

### 1.8 凭证扫描

`ConfigCredentialScanTest`：阻止明文密钥写入配置/代码（对应规约 §8）。

## 2. Handbook

`docs/handbook/` 是"行为 → 源码位置"的索引，由代码锚点注释生成：

```java
// @handbook runtime-chat-service/stream | 聊天主流程：校验输入 → 建会话 → 事件流
```

工具：[tools/handbook/build.py](file:///workspace/tools/handbook/build.py)

```bash
python tools/handbook/build.py list     # 锚点清单
python tools/handbook/build.py sync     # 刷新 L3 文档与 manifest.json（只改 generated 区段）
python tools/handbook/build.py check    # 校验锚点/行号/生成区/文档漂移（CI 用）
```

`HandbookDriftTest` 与 `doc-marshal check --all` 共同保证文档不腐化。人工段落不会被 `sync` 覆盖。

## 3. 会话归档门禁

`docs/ledger/<YYYY-MM>.jsonl` 保存会话留痕（每行一份 manifest，追加式 + 哈希链）；
`docs/views/` 是由 `tools/docs/build.py sync` 生成的读视图（近期索引/变更日志/模块档案）；
[tools/session-archive/gate.py](file:///workspace/tools/session-archive/gate.py) 负责校验。

```bash
python tools/session-archive/gate.py start     # 开新会话记录
python tools/session-archive/gate.py finish    # 追加 ledger 行 + 刷新 views
python tools/session-archive/gate.py validate  # 校验完整性
python tools/session-archive/gate.py self-test # 自检
python tools/session-archive/diag-chain.py bug # 归档断链诊断（分类，默认 bug）
python tools/docs/find.py "<关键词>"            # 检索留痕与 skills（勿整读 ledger）
```

`.githooks/pre-commit` / `pre-push` 挂接该门禁（`install-hooks.ps1` 安装）。规范见 `docs/session-archive-spec-v1.md`。

每个分类的归档是一条哈希链（`chain.prev` → `chain.self`），按 `(createdAt, id)` 排序。**rebase / 并行会话合并后必然断链**，`validate` 只报“chain.prev 断链”，`diag-chain.py` 会逐份打印指针指到具体哪两份对不上；修完要重算后续每份的 `self`。

## 4. 任务看板脚本

`tools/task-board/`（操作默认库 `data/workspaces/_platform/platform.db`）：

| 脚本 | 作用 |
|---|---|
| `dump.py [db] [--full]` | 逐条查看任务编码、优先级、分类、状态、标题、描述 |
| `verify.py [db]` | 一致性：编码唯一、前缀与分类一致、同类编码连续 |
| `freshness-probe.py` | 任务新鲜度探测 |
| `runtime-probe.py` | 运行时探测 |

退出码 0/1，适合挂 CI 或本地复核。

## 5. API 文档 MCP

[tools/api-docs-mcp](file:///workspace/tools/api-docs-mcp) 提供两件事：

### 5.1 只读 MCP 服务器（server.js）

把 OpenAPI 与工具目录暴露给外部 Agent：

```bash
node tools/api-docs-mcp/server.js --file ./data/api/openapi.json [--tools ./data/api/agent-tools.json]
node tools/api-docs-mcp/server.js --url http://127.0.0.1:8090/v3/api-docs --token <token>
```

- stdio 传输，可直接配进平台「设置 → MCP」或 Claude Code / Cursor。
- 每次响应带 provenance（数据源、时间、规范版本、接口数）。
- 只读，不调用业务接口。

### 5.2 前端契约审计（audit.mjs）

把 `platform/web/src/api/*.ts` 的请求与 OpenAPI 逐条比对：

```bash
node tools/api-docs-mcp/audit.mjs --strict
```

- 解析 TS 语法树，确定性判断。
- 每个问题带 `file:line`、方法、路径、错误码。
- `--strict` 有 error 即退出 1，CI 使用。

另有 `mock.mjs`（生成前端 mock）、`audit.test.mjs` / `mock.test.mjs`（自测）、`smoke-test.mjs`。

## 6. CI 流水线

[.workflow/build.yml](file:///workspace/.workflow/build.yml)（Gitee Go）：

```text
1. docs_check            pip install doc-marshal==0.6.1
                         doc-marshal check --all
2. api_contract_audit    npm ci --prefix tools/api-docs-mcp
                         node --test audit.test.mjs mock.test.mjs
                         node audit.mjs --strict
3. maven_verify          cd platform && mvn -B -q verify
artifacts                agent-gateway/target、spec-checks/surefire-reports
cache                    ~/.m2/repository
```

四个稳定事实：文档门禁、前端契约、平台构建、产物目录固定。可平移到任意 CI。

## 7. 文档规范

仓库对文档有明确约束（`doc-marshal` 校验）：

- 文件名为 ASCII kebab-case；不新建 `README.md`。
- 每篇带 front-matter：

```yaml
---
type: <文档类型>
summary: <一句话摘要>
---
```

- 标题层级、链接必须可解析；代码锚点与行号由工具维护。
- 本 Wiki 的每篇文件均遵循上述 front-matter 与命名规则。

## 8. 提交前建议

```bash
# 后端
cd platform && mvn -q verify

# 文档（若安装 doc-marshal）
doc-marshal check --all

# 前端类型与契约
cd platform/web && npm run typecheck
node ../../tools/api-docs-mcp/audit.mjs --strict

# 任务/归档（按需）
python tools/handbook/build.py check
python tools/session-archive/gate.py validate
```

这些检查覆盖了本仓库最容易出问题的四类：**依赖方向、错误码/迁移契约、文档漂移、工具预算**。
