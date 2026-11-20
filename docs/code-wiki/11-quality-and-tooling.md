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

模块只有 `src/test`，不产出运行时依赖。包含三类辅助类（`ForbiddenPatternScan`、`MigrationBaseline`、`PythonScripts`——把 Python 门禁脚本纳入 Java 测试调用）与 15 个测试类。

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

### 1.2 禁令扫描（ForbiddenPatternScanTest，R01–R15）

扫描**生产代码**中的已知危险模式（测试代码不受 R 约束），例如：`Runtime.exec`/`sh -c` 拼命令、MyBatis `${}` 注入、`new Thread`（应用虚拟线程）、字段注入、`double` 金额、日志/凭证泄露、`SELECT *`、空 catch、`System.out`、`ScriptEngine`、错误码字面量、通用 throw 等。

豁免必须写 `// spec-ignore: R06 原因`（原因 ≥3 字符）；同一规则同一模块豁免超过 5 次触发评审。`RuleFixtureSelfTest` 用正/反样例夹具反证规则本身有效——扫描器静默失效会被抓出来。

### 1.3 错误码契约

- `ErrorCodeUniquenessTest`：错误码不得重复。
- `ErrorCodeContractTest`：业务代码不得裸抛字面量错误码，必须引用 `ErrorCode` 常量；码段必须合法。

### 1.4 迁移锁（MigrationGuardTest + MigrationBaseline）

- `migration-baseline.txt` 登记已发布迁移的 **LF 归一化 sha256**（与 Flyway checksum 同口径，跨平台一致）；由脚本生成，不是手算。
- "已发布"以**最近发布 tag** 为锚：tag 树里出现过的迁移冻结不可改；tag 后新增的迁移可改，但发布切点必须重算基线（`mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true`）。
- CI backend job 必须 `fetch-depth: 0` + `fetch-tags`：浅克隆拿不到 tag，会把未发布迁移误判成已发布而冻结。
- 新增 schema 变更必须新增版本号，不能改旧文件（两条线并行开发时的版本撞车教训见 [持久化 §2](07-persistence.md)）。

### 1.5 文档与归档（入口型测试）

两个"入口型"测试：Java 侧只调用 Python 单一实现，校验逻辑只有一份，防止 Java/Python 双份规则漂移。

| 测试 | 实际入口 |
|---|---|
| `HandbookDriftTest` | `tools/handbook/build.py check`：锚点/行号/生成区与代码一致 |
| `ProcessArchiveTest` | `gate.py validate` + `self-test` + `sweep --dry-run`：归档完整性、哈希链、防回潮 |

### 1.6 工具面预算

`ToolSchemaBudgetTest`：把工具 schema 的 token 预算纳入构建。新增工具若让常驻 schema 超预算，必须放进插件或调整披露策略。

### 1.7 API 契约

`ApiDocContractTest`：控制器与实际 OpenAPI 快照一致，防止接口改了文档没改。

### 1.8 凭证扫描

`ConfigCredentialScanTest`：阻止明文密钥写入配置/代码（对应规约 §8）。

### 1.9 0.2 新增守卫

| 测试 | 守护 |
|---|---|
| `TokenBaselineTest` | token 量化基线：真实工具面 + 评测指令下按需 schema 的 inputTokens 下降 ≥40%，防回涨 |
| `ToolkitAssemblyTest` | `agentToolkit` 必须是 `readOnlyToolkit` 的超集（BUG-30 回归） |
| `SupervisorToolkitWiringTest` | 调度/角色 Worker 工具面零写工具，防角色裁剪回退 |
| `HostToolExclusionTest` | 宿主 shell 工具不得进任何工具面（沙箱分层契约） |

基线类契约是"棘轮"：`api-doc-baseline.txt` 等只允许减少（补了文档必须删行），新增接口必须先写文档。

### 1.10 门禁的自我防护

- `RuleFixtureSelfTest`：正/反样例夹具证明每条规则真的能命中。
- `gate.py red-team`：端到端注入违规样本跑扫描器，必须红——防扫描器静默失效。
- ArchUnit `allowStoreCreation=false`：存量违规由 FreezingArchRule 冻结，但新增规则不会静默入库；收紧基线必须显式提交 `archunit_store`。

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

## 3. 会话归档与 docs 三层模型

### 3.1 唯一写入点：ledger

`docs/ledger/<YYYY-MM>.jsonl` 保存会话留痕——每行一份 `process-manifest/v2`，追加式 + 按分类哈希链（`chain.prev` → `chain.self`，按 `(createdAt, id)` 排序）。**唯一写入入口是 [tools/session-archive/gate.py](file:///workspace/tools/session-archive/gate.py) 的 `finish`**：落一行 → `reseal_chain` 重封该分类的链 → 自动刷新 views。

`docs/views/`（index/changelog/weekly/modules/*）与 `docs/skills/index.md` 都是 `tools/docs/build.py sync` 的生成投影：改 ledger 后必须 sync，`build.py check`（pre-push 与 CI 都跑）发现投影漂移即红。检索用 `python tools/docs/find.py "<关键词>"`（线性扫描，不建索引），**不要整读 ledger**。

### 3.2 会话流程

```bash
python tools/session-archive/gate.py start      # 开会话（--category feat|bug|chore|docs|deploy + slug）
python tools/session-archive/gate.py finish     # 校验证据 → 追加 ledger 行 → 重封链 → 刷新 views
python tools/session-archive/gate.py validate   # 完整性：schema/链/提交存在性/归档覆盖缺口
python tools/session-archive/gate.py self-test  # 门禁自检
python tools/session-archive/gate.py doctor     # 环境/配置体检
python tools/session-archive/gate.py metrics    # 各层耗时分位数与预算
python tools/session-archive/diag-chain.py      # 断链定位（打印 prev/self 指到哪两份）
python tools/docs/find.py "<关键词>"             # 检索留痕与 skills
```

- 会话状态按 **worktree 分片**（`.git/session-archive/`），多工作树并行互不阻塞。
- `finish` 强制证据：feat/bug 必须 PASSED 测试证据且证据树与最后代码提交一致、有用例增量；chore 含代码须 `--verification`；docs 类允许空提交。
- 归档覆盖契约：pre-push 检查"锚点 → HEAD 的全部代码提交都已归档"，缺一个禁止推送。
- **旧模型回潮守卫**：`docs/process/<cat>/<folder>/manifest.json` 一出现 validate 即报错并提示运行 `migrate-to-ledger.py` 吸收（防旧基点分支合并把旧归档模型整套带回）。
- **断链处理**：并行合并后断链不用手改——`reseal_chain(category)` 按 `(createdAt, id)` 重排整条链并重算每份 `self`；`diag-chain.py` 负责定位断在哪两份。

### 3.3 并发健壮性

| 机制 | 说明 |
|---|---|
| 僵尸锁 PID 自愈 | 锁文件首行写持锁 PID；持锁进程已死 → 秒级回收，不再干等 mtime 超时 |
| git index.lock 退避 | `git` 调用对 `index.lock` 冲突指数退避重试（0.1s 起 ×1.5 封顶 1s），并行会话提交不互踩 |
| 证据指纹 | 指纹 = 索引树 + 未暂存 diff（**故意排除未跟踪文件**，防 IDE 临时文件让缓存永不命中）；同指纹且 JDK 一致直接复用测试证据 |
| 乐观并发归档 | finish 无全局互斥；并行 finish 各接旧链尾，由 reseal_chain 收敛重排 |

### 3.4 增量测试分层

| 层 | 策略 |
|---|---|
| pre-commit（求快） | `-amd` 只做下游编译检查；测试只跑变更模块；类级映射（源码引用 + 类名约定，上限 24 个类）；高风险路径（security/policy/permission/工具目录/迁移）回退模块全量；纯前端改动跑 <2s 零依赖自检 |
| pre-push（求全） | spec-checks + 前端自检 + views/handbook check（spec-checks 按输入指纹缓存，纯文档提交不重跑） |
| CI / `SESSION_ARCHIVE_FULL_TEST=1` | 全量，模块闭包哈希跳过已绿模块 |

### 3.5 钩子

`.githooks/pre-commit` / `pre-push` / `commit-msg`（拦截损坏中文提交信息）由 `gate.py install-hooks` 或 `install-hooks.ps1` 安装（`core.hooksPath=.githooks`）。规范见 `docs/session-archive-spec-v1.md`（当前 v3）。

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

## 6. CI：两条流水线

### 6.1 GitHub Actions（.github/workflows/ci.yml，完整门禁）

触发 push/PR × main / 0.1.0-snapshot / 0.2.0-snapshot；`changes` job 用 paths-filter 输出开关，业务 job 按需跳过（skipped 不阻塞分支保护）：

| job | 内容 |
|---|---|
| `backend` | 真 PG16 service 容器 + `mvn -B -ntp verify`（编译 + 单测 + spec-checks）；checkout 必须 `fetch-depth: 0` + `fetch-tags`（迁移守卫要读发布 tag） |
| `frontend` | pnpm frozen install → typecheck → `check:api-types`（schema.d.ts 与 OpenAPI 一致）→ 前端测试 |
| `docs` | 提交信息编码门禁（`tools/ci/check-commit-messages.py`）+ 门禁自测 + `gate.py validate` + `build.py check`（views 一致）+ handbook check |
| `contract` | `node --test audit.test.mjs mock.test.mjs` + `audit.mjs --strict`（前端契约审计） |
| `security` | gitleaks + osv-scanner（不过滤，每次都跑） |

### 6.2 Gitee Go（.workflow/build.yml，构建兜底）

```text
1. docs_check            doc-marshal check --all（阻断）+ drifted 仅提示
2. api_contract_audit    npm ci + node --test + audit.mjs --strict
3. maven_verify          mvn -B -q verify
artifacts                agent-gateway/target、spec-checks/surefire-reports
cache                    ~/.m2/repository
```

只依赖四个稳定事实：文档门禁、前端契约、平台构建、产物目录固定——可平移到任意 CI。与 GitHub Actions 的分工：这边求"能构建"，那边求"完整门禁"。

## 7. 文档体系与规范

`docs/` 分层消费：`skills/` 是 agent 入口（9 张手写卡 + 生成的路由索引）、`views/` 是生成视图、`ledger/` 是唯一写入点（见 §3）、`summary|plan/` 是方案契约、`code-wiki/` 与 `handbook/` 一人写一生成。改任何 ledger 数据后运行 `python tools/docs/build.py sync`，投影漂移会被 check 拦下。

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
