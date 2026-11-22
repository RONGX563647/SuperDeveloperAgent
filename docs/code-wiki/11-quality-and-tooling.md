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

模块只有 `src/test`，不产出运行时依赖。包含三类辅助类（`ForbiddenPatternScan`、`MigrationBaseline`、`PythonScripts`——把 Python 门禁脚本纳入 Java 测试调用）与 14 个测试类。

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

扫描**生产代码**中的已知危险模式（测试代码不受 R 约束）。规则只有一份，定义在 `ForbiddenPatternScan`：

| 规则 | 禁止 |
|---|---|
| R01 | `Runtime.exec`：命令必须经执行面 |
| R02 | 调用 shell 解释器（`sh -c` / `bash -c` / `cmd /c` / `powershell`） |
| R03 | MyBatis `${}` 拼接（必须 `#{}` 参数绑定） |
| R04 | `new Thread()`（用受管线程池/虚拟线程） |
| R05 | 字段注入（`@Autowired` 不得标注在字段上） |
| R06 | 金额/费用用 `double`（用 `BigDecimal`） |
| R07 | 日志打印凭证类字段 |
| R08 | `SELECT *`（显式列出字段） |
| R09 | `java.util.Date` / `new Date()`（用 `java.time`） |
| R10 | 空 catch 块（吞异常） |
| R11 | `System.out/err`（用结构化日志） |
| R12 | 动态脚本执行（`ScriptEngine` / `eval`） |
| R13 | 软删用 `is_deleted` 布尔字段（必须用 `deleted_at`） |
| R14 | 硬编码 `E-xxx` 错误码字面量（必须用 `ErrorCode` 枚举） |
| R15 | `waitFor` 之前的无界读流（必须走 `BoundedCommand`） |

豁免必须写 `// spec-ignore: Rxx 原因`（原因 ≥3 字），并登记项目规约 §13；同一规则同一模块豁免超过 5 次触发评审。`RuleFixtureSelfTest` 用正/反样例夹具反证每条规则真的能命中——扫描器静默失效会被抓出来。

### 1.3 错误码契约

- `ErrorCodeUniquenessTest`：错误码不得重复。
- `ErrorCodeContractTest`：业务代码不得裸抛字面量错误码，必须引用 `ErrorCode` 常量；码段必须合法。

### 1.4 迁移锁（MigrationGuardTest + MigrationBaseline）

`migration-baseline.txt` 登记已发布迁移的 **LF 归一化 sha256**（先把 CRLF / 孤立 CR 归一成 LF 再算，与 Flyway checksum 同口径；Windows 与 Linux CI 得到同一个值，不可用 `Get-FileHash` 手算）；它是生成物，禁止手改哈希。

- "已发布"以**最近发布 tag**（`x.y.z`，不带 `v`）为锚：tag 树里出现过的迁移冻结不可改；tag 后新增的迁移可改，但发布切点必须重算基线。0.1 线已用 `0.1.0` / `0.1.1` 作锚，**V5–V8 基线已补录**。
- 新增 schema 变更必须新增版本号，不能改旧文件（演进只能新增 `V(n+1)__xxx.sql`，用 `ALTER TABLE` / `CREATE TABLE IF NOT EXISTS` / `DO` 块）。
- CI backend job 必须 `fetch-depth: 0` + `fetch-tags`：浅克隆拿不到 tag，会把未发布迁移误判成已发布而冻结。
- 重算（发布切点或改未发布迁移时）：`cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true test`。

### 1.5 文档与归档（入口型测试）

两个"入口型"测试：Java 侧通过 `PythonScripts` 只调用 Python 单一实现，校验逻辑只有一份，防止 Java/Python 双份规则漂移。

| 测试 | 实际入口 |
|---|---|
| `HandbookDriftTest` | `tools/handbook/build.py check`：锚点 / 行号 / 生成区与代码一致 |
| `ProcessArchiveTest` | `tools/session-archive/gate.py validate` + `self-test` + `sweep.py --dry-run`：归档完整性、哈希链、对账脚本可运行 |

### 1.6 工具面预算

`ToolSchemaBudgetTest`：把工具 schema 的 token 预算纳入构建。新增工具若让常驻 schema 超预算，必须放进插件或调整披露策略。

### 1.7 API 契约

`ApiDocContractTest`：控制器与实际 OpenAPI 快照一致，防止接口改了文档没改。

### 1.8 凭证扫描

`ConfigCredentialScanTest`：阻止明文密钥写入配置/代码（对应规约 §8）。

### 1.9 工具面装配与 token 量化基线

| 测试 | 守护 |
|---|---|
| `TokenBaselineTest` | token 量化基线：真实工具面（`ToolPlugins.CORE`=8 个常驻工具）+ 评测指令下按需 schema 的 inputTokens 相对全量下降 **≥40%**，且首轮下发不超硬上限（防回涨） |
| `ToolkitAssemblyTest` | `agentToolkit` / `supervisorToolkit` / `deployToolkit` 必须是 `readOnlyToolkit` 的超集（BUG-30 回归），且主面必须注册 `AskUserTools`（`ask_user` 拦截锚点） |
| `HostToolExclusionTest` | AgentScope 宿主 shell/文件工具（`ShellCommandTool` 等）不得注册进任何工具面（沙箱分层契约：模型侧不暴露 shell，命令只来自配方） |

`TokenBaselineTest` 复现 `ProgressiveToolMiddleware` 的渐进披露逻辑（按插件目录顺序只保留命中的前 2 个插件），把"token 爆炸专项"的 ≥40% 验收锁定为可复现的结构性代理；插件分项归因的真实读数来自 V8 迁移 `token_usage_attribution.sql`（`ToolAttribution` / `TokenUsageRepository.summary()`）。

基线类契约是"棘轮"：`api-doc-baseline.txt`（未写文档的接口缺口）、`error-code-baseline.txt`（通用 throw 计数）只允许减少（补齐后必须删行/降计数），新增缺口必须先登记。

### 1.10 门禁的自我防护

- `RuleFixtureSelfTest`：正/反样例夹具证明每条禁令规则真的能命中、合规范例不误报。
- `gate.py red-team`：端到端注入违规样本跑扫描器，必须命中（防扫描器静默失效）。
- ArchUnit `allowStoreCreation=false`（`spec-checks/pom.xml`）：存量违规由 `FreezingArchRule` 冻结为基线（棘轮），但新增规则不会静默入库；收紧基线必须显式提交 `archunit_store/stored.rules`。

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
python tools/session-archive/diag-chain.py bug  # 断链定位（分类，默认 bug；打印 prev/self 指到哪两份）
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
| 僵尸锁 PID 自愈 | 锁文件首行写持锁 PID；持锁进程已死 → 秒级回收，不再干等 stale 超时（默认 10/15 分钟只兜底"进程活着但卡死"） |
| git index.lock 退避 | `git` 调用对 `index.lock` 冲突指数退避重试（默认最多 80 次），并行会话提交不互踩 |
| 证据指纹 | 指纹 = 索引树 + 已跟踪文件的未暂存改动（**故意排除未跟踪文件**，防 IDE 临时文件让缓存永不命中）；同指纹且 JDK 一致直接复用测试证据 |
| 乐观并发归档 | finish 无全局互斥；并行 finish 各接旧链尾，由 reseal_chain 收敛重排 |

### 3.4 增量测试分层

| 层 | 策略 |
|---|---|
| pre-commit（求快） | `-amd` 只做下游编译检查；测试只跑变更模块；类级映射（源码引用 + 类名约定）+ 高风险路径（security/policy/permission/工具目录/迁移）回退模块全量 |
| pre-push（求全） | spec-checks + 前端自检 + views/handbook check（spec-checks 按输入指纹缓存，纯文档提交不重跑） |
| CI / `SESSION_ARCHIVE_FULL_TEST=1` | 全量，模块闭包哈希跳过已绿模块 |

### 3.5 钩子

`.githooks/pre-commit` / `pre-push` / `commit-msg`（后者拦截损坏中文提交信息）由 `gate.py install-hooks` 或 `install-hooks.ps1` 安装（`core.hooksPath=.githooks`）。规范见 `docs/session-archive-spec-v1.md`（当前 v3）。

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

触发 push/PR × `main` / `0.1.0-snapshot`。五个 job（backend / frontend / docs / contract / security）需设为 required checks，与本地门禁共用同一组命令，避免"本地绿、CI 红"：

| job | 内容 |
|---|---|
| `backend` | 真 PG16 service 容器 + `mvn -B -ntp verify`（编译 + 单测 + spec-checks）；checkout 用 `actions/checkout@v7.0.1`，必须 `fetch-depth: 0` + `fetch-tags: true`（迁移守卫要读发布 tag） |
| `frontend` | pnpm frozen install → typecheck → `check:api-types`（schema.d.ts 与 OpenAPI 一致）→ 前端测试 |
| `docs` | 提交信息编码门禁（`tools/ci/check-commit-messages.py`，禁止损坏中文）+ 门禁自测 + `gate.py validate` + `build.py check`（views 一致）+ handbook check |
| `contract` | `node --test audit.test.mjs mock.test.mjs` + `audit.mjs --strict`（前端契约审计） |
| `security` | gitleaks + osv-scanner（不过滤，每次都跑） |

action 版本钉在 node24 运行时的精确版本（checkout@v7.0.1 / setup-java@v6.3.0 / setup-node@v7.1.0 等），保证同一 commit 的 CI 可复现。全链路强制 UTF-8（`PYTHONUTF8` / `PYTHONIOENCODING` / `LANG=C.UTF-8`），防中文归档/日志被打成 `?`。

### 6.2 Gitee Go（.workflow/build.yml，构建兜底）

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

只依赖四个稳定事实：文档门禁、前端契约、平台构建、产物目录固定——可平移到任意 CI。与 GitHub Actions 的分工：这边求"能构建"，那边求"完整门禁"。

### 6.3 构建环境与依赖安全

- 前置任意发行版 **JDK 21+**，构建 JDK 由 `JAVA_HOME` 一次性覆盖即可（`JAVA_HOME=/path/to/any-jdk-21+ mvn -q verify`），无需改 `pom.xml`。
- Jackson 2 安全补丁：`pom.xml` 用 `jackson-bom.version=2.21.4` 覆盖 Spring Boot 4.0.4 BOM 自带的 2.21.1，修复 GHSA-r7wm-3cxj-wff9 / GHSA-j3rv-43j4-c7qm 等；Jackson 3（`tools.jackson.*`）不受影响。

## 7. 文档体系与规范

`docs/` 分层消费：

| 目录 | 角色 |
|---|---|
| `skills/` | agent 入口（手写路由卡 + 生成的 `index.md` 路由索引） |
| `views/` | 生成视图（index/changelog/weekly/modules），由 `tools/docs/build.py sync` 投影 |
| `ledger/` | 会话留痕唯一写入点（见 §3） |
| `summary/` | 方案/总结契约 |
| `code-wiki/`、`handbook/` | 一人写一生成，锚点与行号由工具维护 |

改任何 ledger 数据后运行 `python tools/docs/build.py sync`，投影漂移会被 check 拦下。

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
