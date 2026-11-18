---
canonical: docs/summary/2026-11/2026-11-18_quality-gate-review.md
schema: session-plan/v1
kind: plan
slug: quality-gate-review
period: 2026-11
createdAt: 2026-11-18T20:04:00+08:00
updatedAt: 2026-11-18T20:20:00+08:00
status: reviewing
owner: YG
verdict: FAIL
supersedes: null
supersededBy: null
refs: []
---

# 质量门禁评审与优化方案（2026-11）

> 评审对象：当前仓库的全部门禁入口（本地钩子 / Maven 规约检查 / 归档门禁 / CI 配置 / 前端契约）。
> 结论先行：**门禁分层设计是好的，但"最后一层"（远端 CI）实际上是空的**——现有强度全部依赖
> 本地钩子，而本地钩子可被 `--no-verify` 一次性绕过；前端与覆盖率是两个完全未设防的面。
> 契约：本文件按 `docs/session-archive-spec-v1.md` §4.6 的 plan 类契约维护；
> 「验收契约」一节是唯一的完成判定口径，「执行状态」一节随落地进度更新。

---

## 概述

仓库的门禁是四层结构（本地提交前 / 推送前 / CI / 构建内规约检查），**规则质量已达成熟团队水准**
（架构棘轮、错误码契约、归档 hash 链），但**执行可靠性只落在开发者本机**：远端 CI 不存在、
钩子靠手工复制、前端零门禁、覆盖率只有报表没有闸门。本方案不改规则，只修"强制力"：
让 CI 真实存在、让钩子随仓库走、把前端与覆盖率接上，用很小的改动把体系从"自觉遵守"变成"无法绕过"。

现状结论 `verdict: FAIL`（被评审体系当前不满足任何"不可绕过"要求）。

---

## 现状与证据

### 1.1 四层结构

| 层 | 入口 | 触发 | 实际执行内容 | 可绕过 |
|---|---|---|---|---|
| L1 提交前 | `.git/hooks/pre-commit` → `gate.py gate-pre-commit` | `git commit` | 必须有活跃会话；跑**增量**测试（`-pl <变更模块> -amd`，排除 `spec-checks`）；证据按工作区指纹 + TTL 60min 复用 | `--no-verify` |
| L2 推送前 | `.git/hooks/pre-push` → `gate.py gate-pre-push` | `git push` | 会话必须 finish；`docs/process`、`docs/summary` 不得脏；每个代码提交必须被归档覆盖；`gate.py validate` 全量；handbook 漂移；`spec-checks` 全量（tree 指纹缓存） | `--no-verify` |
| L3 CI | `.workflow/build.yml`（**Gitee Go**） | push / PR 到 main | doc-marshal 文档检查 → 前端 OpenAPI 契约审计（`tools/api-docs-mcp/audit.mjs --strict`）→ `mvn -B verify` | — |
| L4 构建内 | `platform/spec-checks`（并入 `mvn verify`） | 每次构建 | ArchUnit 架构规则（A1/A2/A3/A4/A6/A7/A9，FreezingArchRule 棘轮）+ 禁令正则 R01–R15 + 错误码契约 EC1–EC5 + 决策面凭证扫描 C1–C5 + 迁移文件 sha256 基线 M1 + 工具 schema 预算 + API 文档契约 + Handbook 漂移 + 归档校验 | — |

### 1.2 spec-checks 规则清单（已落地，质量不错）

- **架构**：依赖单向（Gateway→Runtime→Tools/Governance→Domain←Executor，Infra 只依赖 Domain）、domain 无框架、executor 无 LLM SDK、决策面无 Docker/K8s 客户端、禁 util/common/helper、tools 禁 `ProcessBuilder`、禁字段注入。
- **禁令**：R01–R15（`Runtime.exec`、`sh -c`、MyBatis `${}`、`new Thread`、`double` 金额、日志打凭证、`SELECT *`、`java.util.Date`、空 catch、`System.out`、ScriptEngine/eval、`is_deleted`、错误码字面量、通用 `throw new`）。
- **契约**：错误码分组与异常类一一对应；禁止通用异常携带错误码；业务异常只能在 `domain/error`。
- **基线**：迁移文件 sha256 锁定（改历史迁移即红）；豁免需带原因且 >5 次触发评审。
- **棘轮健康度**：`platform/spec-checks/archunit_store/` 下 7 个违规文件当前**全部为 0 字节**，说明存量违规已清零，棘轮处于"新增即失败"的理想位置。

### 1.3 已有亮点（不要在优化中丢掉）

1. **证据复用**：pre-commit 测试按"索引树 + 未暂存 diff + 未跟踪文件元数据"算指纹，60 分钟内绿色直接复用，改 commit message 不重跑。
2. **per-worktree 测试锁**：锁放在各自 `git dir` 下，多 worktree 可并行跑测试，同 worktree 内串行；`spec-checks` 与业务测试共用同一把锁，避免并发写 `target` 造成类版本错配的假失败。
3. **规则单一实现**：Java 侧只调用 `tools` 下的 Python 脚本（`PythonScripts.run`），归档规约不存在 Java/Python 双份漂移。
4. **归档 hash 链 + 只增不改**：改历史归档必断链，补录只能新建 manifest。
5. **JDK 自动探测**：`gate.py` 内置 `JDK_MIN_MAJOR=21` 与模式扫描（`F:/dev/tools/jdk*`、`~/.jdks/*` 等），门禁自己找 JDK，不绑死路径。

---

## 缺口清单

按证据分级：P0 = 强制力不存在，P1 = 强制力可被静默削弱，P2 = 防线缺失但非致命。

### P0-1 远端 CI 实际不存在 ✗

- 证据：`git remote` = `https://github.com/RONGX563647/SuperDeveloperAgent`；仓库内**没有** `.github/workflows/`（`git ls-files .github` 为空）；唯一的流水线是 `.workflow/build.yml`（Gitee Go）。
- 后果：**L3 整层失效**。所有强制力都压在 L1/L2 两个本地钩子上，任何一次 `--no-verify`（本会话删远端分支时即用过）就完全绕过；换台机器、新克隆、CI 上跑构建都不受任何约束。
- 等级：最高。这是"门禁是否真实存在"的分水岭。

### P0-2 钩子靠手工复制，不可审计、克隆即失效 ✗

- 证据：`git config core.hooksPath` 为空；`tools/session-archive/install-hooks.ps1` 是 `Copy-Item` 到 `.git/hooks`；`.git/hooks/pre-commit`、`pre-push` 存在（本机装过）。
- 后果：钩子内容与仓库内 `.githooks` 源文件可能漂移且无人知晓；新克隆默认裸奔；升级钩子必须每台机器重装。
- 修法成本极低：`git config core.hooksPath .githooks`。

### P0-3 前端零门禁 ✗

- 证据：`platform/web/package.json` 无 `vitest`/`jest` 依赖、无 `test` 脚本、无 `eslint`/`prettier`；但 `platform/web/src/` 下已有 3 个 `.test.ts`（`capabilityOrder`、`agentEventReducer`、`pipelineLayout`）**从未被执行过**；`typecheck`（`vue-tsc --noEmit`）、`check:api-types` 脚本存在但没有任何门禁调用；`.workflow/build.yml` 只对 `tools/api-docs-mcp` 跑契约审计，**不构建 web**。
- 后果：前端类型错误、契约漂移（`schema.d.ts` 与 OpenAPI 不一致）、已有单测死代码化，都能一路合入。

### P0-4 覆盖率只有报表，没有闸门 ✗

- 证据：`platform/pom.xml` 只配了 JaCoCo `prepare-agent` + `report`，**无 `check` goal**；`docs/session-archive-spec-v1.md` §4.4 要求 `patchCoverage ≥ 80%` 才 PASS，但归档 summary 长期记 `N/A` → 归档 gate 永久降级 WARN。
- 后果：覆盖率可以持续下滑而不触发任何失败；质量门禁表里的 PASS 条件事实上不可达。

### P1-1 pre-commit 增量 + pre-push 只跑 spec-checks = 组合漏洞

- pre-commit：只跑变更模块及其下游（`-amd`），且**排除 spec-checks**。
- pre-push：只跑 `spec-checks`（`-pl spec-checks -am install -DskipTests` 后单跑），**不跑业务测试**。
- 结论：一次 push 的验证集合 = 变更模块测试 + 全部规约检查，**未变更模块的测试全程不跑**。跨模块回归（如改 domain 影响所有模块的语义）只靠 `-amd` 下游覆盖，反向不受影响；而 CI 本来应该兜底这一层，但 P0-1 说 CI 不存在。

### P1-2 ArchUnit 棘轮可静默放水

- 证据：`platform/spec-checks/pom.xml` 设 `archunit.freeze.store.default.allowStoreCreation=true`。
- 后果：新增一条架构规则时，若代码已有违规，ArchUnit 会**自动创建 store 并冻结**，测试直接通过——新规则形同虚设。
- 修法：CI 剖面置 `allowStoreCreation=false`，基线更新必须是显式提交。

### P1-3 spec-checks 无 fixture 自测（README 已自承）

- 现状：规则本身写错（正则失效、包名打错）不会被发现，只会表现为"永远绿"。
- `platform/spec-checks/README.md`「已知限制」第一条即为此项，标注"待补"。

### P1-4 `mvn` 直跑与门禁行为不一致（本次已踩过）

- 证据：`gate.py` 自动探测 JDK≥21，但开发者裸跑 `mvn test` 用的是 `JAVA_HOME=F:\dev\tools\jdk17` → `class file version 65.0 vs 61.0` 假失败；反之用 JDK21 编译、JDK17 跑测试会留下脏 `target`。
- 后果：本地"测试红"与门禁绿不一致，开发者误判。

### P2-1 无静态分析与供应链扫描

- 缺 SpotBugs/PMD/ErrorProne；缺依赖漏洞扫描（osv-scanner / Trivy）、镜像扫描、密钥扫描（gitleaks）。
- 本项目特性放大了这块风险：平台自身持有 SSH 部署凭据、`SecretBox` 主密钥、可执行远程命令。现有只有 R07（日志打凭证）与 C1–C5（决策面配置）两条正则。

### P2-2 无集成测试层（failsafe 缺失）

- 无 `maven-failsafe-plugin`，`mvn verify` = 编译 + 单测 + spec-checks。
- Docker/Host 真实部署、SQLite↔PostgreSQL 双后端持久化契约都只在单测 mock 层验证（归档自评遗留项："PostgreSQL 通路仅做静态验证"）。
- 最近这次 SQLite 数据丢失 bug 正是"单测全绿、真实后端行为错"的典型样本。

### P2-3 门禁耗时无预算、无度量

- `spec-checks` 缓存按 tree 指纹，tree 每次改动都变 → 实际每次 push 都全量重跑（`mvn install -am` + spec-checks）。
- 没有任何一层记录耗时，无法回答"门禁是否已成为瓶颈"。

---

## 优化方案

设计原则：**远端兜底优先、只加不删、每层只干该干的事**。本地门禁求"快反馈"，CI 求"全覆盖"，二者不重复劳动。

### 阶段一（P0，1–2 天）：让门禁真实存在

| # | 动作 | 落点 | 验收 |
|---|---|---|---|
| 1 | 建 GitHub Actions 真实 CI | `.github/workflows/ci.yml` | PR 上 4 个 job 全绿才算可合并 |
| 2 | 钩子改 `core.hooksPath` | `tools/session-archive/install-hooks.ps1` 改为 `git config core.hooksPath .githooks` | 新克隆一条命令生效，`.githooks` 即唯一真相 |
| 3 | 前端接入门禁 | CI 加 `frontend` job：`pnpm install` → `typecheck` → `check:api-types` → `vitest run` | 3 个存量 `.test.ts` 真正执行 |
| 4 | 分支保护 | GitHub 上对 `main`、`0.1.0-snapshot` 开 required checks | `--no-verify` 只影响本地，合并不受影响 |

CI job 划分（对齐现有能力，不新增工具）：

```yaml
jobs:
  backend:      # JDK 21 + mvn -B verify（含 spec-checks）
  frontend:     # node 20 + pnpm：typecheck → check:api-types → vitest
  docs:         # python 3.11 + doc-marshal check --all + gate.py validate
  contract:     # node --test tools/api-docs-mcp/*.test.mjs + audit.mjs --strict
```

`.workflow/build.yml` 保留作为 Gitee 镜像，两处共用同一组命令，避免规则分叉。

### 阶段二（P1，1–2 周）

| # | 动作 | 说明 |
|---|---|---|
| 5 | **覆盖率闸门** | `jacoco:check` + 自定义 patch coverage（对比 diff 行覆盖率）；阈值取当前实测值做**棘轮**，只升不降；补齐归档 summary 里长期为 `N/A` 的字段 |
| 6 | **收紧 ArchUnit** | CI 剖面置 `allowStoreCreation=false`；基线更新必须单独提交并在 PR 里可见 |
| 7 | **spec-checks fixture 自测** | 新建 `platform/spec-checks/fixtures/`，每条 R 规则一组"应命中/不应命中"样例，用 `-Dspec.root=fixtures` 跑；补 README 已知限制第一条 |
| 8 | **统一 JDK 入口** | 新增 `gate.py doctor`（复用已有的 `detect_jdk`）在门禁开头打印实际 JDK；`mvn` 统一走 wrapper 或在 `pom.xml` 用 `maven-enforcer-plugin` 强制 `java.version >= 21`，直接给出可读报错 |

### 阶段三（P2，1 个月）

| # | 动作 | 说明 |
|---|---|---|
| 9 | **安全扫描** | CI 加 gitleaks（密钥）、osv-scanner/Trivy（依赖与镜像）；对 SSH/部署相关代码补一条 spec 规则 |
| 10 | **集成测试层** | 引入 failsafe + JUnit tag（`@Tag("integration")`）：SQLite 与 PostgreSQL(Testcontainers) 各跑一遍持久化契约；打上 `@Tag("docker")` 的部署测试在 self-hosted runner 执行 |
| 11 | **门禁耗时预算** | 每层把耗时写入 `docs/.session/gate-metrics.json`，summary 里出趋势；定"pre-commit ≤ 60s、pre-push ≤ 3min、CI ≤ 10min"的目标，超了就把重活下移到 CI |
| 12 | **pre-push 契约重定义** | 全量测试交给 CI，pre-push 只保留"归档完整 + spec-checks + web typecheck"；文档里明确"本地不跑全量不等于没跑" |

### 阶段四（长期，可选）

- **门禁自审**：每月跑一次"故意注入违规"的红队测试（临时造一条违反 A1 的 import、一条 R06 `double` 金额），确认门禁会红——防止规则静默失效（对应 P1-3 的纵深防御）。
- **归档门禁与 CI 融合**：`gate.py validate` 在 CI 上跑（不依赖本地钩子），未归档提交在 PR 上直接红。
- **迁移门禁增强**：现有 M1 只锁 sha256；可加"迁移可执行性"——在临时库上真实回放一遍 V1→Vn，确保 SQLite/PG 都能跑通（这次数据丢失类 bug 的预防）。

### 落地顺序

```
先做 P0-1（真实 CI）——没有它，其余优化的强制力都是零
  ↓
P0-2（hooksPath）→ P0-3（前端）→ P0-4（覆盖率闸门）
  ↓
P1-2/P1-3/P1-4（收紧棘轮、补自测、统一 JDK）
  ↓
P2-1/P2-2（安全与集成层，投入最大、见效最慢）
```

---

## 验收契约

### 契约条款（此节是完成判定的唯一口径）

1. **一条缺口 = 一条契约**：下面表格每行必须同时给出「落点 + 可复制执行的验收命令 + 通过判定 + 反证」。缺任一项，该缺口不得标记完成。
2. **机制存在 ≠ 生效**：验收必须跑命令看输出，不接受"代码已经加了"作为完成依据；反证必须能复现失败形态。
3. **status 升级条件**：`reviewing → approved` 需要 C0–C4 的行全部填满；`approved → executing` 需要至少一条 C 项进入"进行中"并留下提交依据；任何 C 项判定为通过才可进入"已完成"。
4. **契约自身可验证**：C0 保证本文件符合 §4.6，plan 文档违规同样红。

| # | 缺口 | 落点 | 验收命令（仓库根执行） | 通过判定 | 反证（失败长这样） |
|---|---|---|---|---|---|
| C0 | 契约自检 | `docs/summary/plan/`、`docs/summary/2026-11/` | `python tools/session-archive/gate.py validate` | 输出「会话归档校验通过」且退出码 0 | `缺少 frontmatter` / `必需章节缺失` / `canonical 指向不存在` |
| C1 | P0-1 | `.github/workflows/ci.yml` | `git ls-files .github/workflows` + `gh workflow list` | 存在 workflow，且 backend/frontend/docs/contract 四 job 在 PR 上真实运行 | `git ls-files` 为空 → L3 仍不存在 |
| C2 | P0-2 | `tools/session-archive/install-hooks.ps1`、`.githooks/` | `git config core.hooksPath`；新克隆后 `git commit` 触发门禁 | 输出 `.githooks`；`.git/hooks/*` 不再是唯一入口 | 输出为空 → 仍是手工 `Copy-Item` |
| C3 | P0-3 | `platform/web/package.json`、CI `frontend` job | `cd platform/web && pnpm typecheck && pnpm check:api-types && pnpm vitest run` | 三条命令存在且 `vitest` 实际执行 3 个存量 `.test.ts` | `ERR_PNPM_NO_SCRIPT` / `vitest: not found` |
| C4 | P0-4 | `platform/pom.xml`（jacoco `check`） | `cd platform && mvn -B -pl agent-domain test jacoco:check` | `check` goal 存在，低于阈值即构建失败 | `Unknown goal jacoco:check` → 仍只有报表 |
| C5 | P1-1 | `.githooks/pre-push`、`gate.py gate-pre-push` | `python tools/session-archive/gate.py gate-pre-push`（或 `git push --dry-run` 看门禁输出） | 输出能证明"未变更模块测试被谁覆盖"（CI 或本地全量） | 只打印 spec-checks → 验证集合仍有缺口 |
| C6 | P1-2 | `platform/spec-checks/pom.xml` | `grep -n allowStoreCreation platform/spec-checks/pom.xml` | CI 剖面为 `false`，store 变更以显式提交出现 | 仍为 `true` → 新规则可被自动冻结 |
| C7 | P1-3 | `platform/spec-checks/fixtures/` | `mvn -B -pl spec-checks test -Dspec.root=fixtures` | 每条 R 规则至少一组正/负样例，负样例能让测试红 | 目录不存在 → 规则写错永远绿 |
| C8 | P1-4 | `tools/session-archive/gate.py doctor`、`platform/pom.xml` enforcer | `python tools/session-archive/gate.py doctor`；`mvn -v` | 门禁与裸 mvn 打印同一 JDK major 且 ≥ 21 | `doctor` 不存在 / 两边 major 不同 |
| C9 | P2-1 | `.github/workflows/ci.yml` security job | `gitleaks detect --no-banner --redact`；`osv-scanner -r .` | 有 job 且失败即红；结果进 PR 检查 | 无相关 job → 供应链无人看 |
| C10 | P2-2 | `platform/pom.xml`（failsafe）、`@Tag("integration")` | `cd platform && mvn -B verify -DskipITs=false` | failsafe 输出中含 integration 用例（含 PG Testcontainers） | verify 只跑 surefire |
| C11 | P2-3 | `docs/.session/gate-metrics.json` | `python tools/session-archive/gate.py metrics`（待实现）；否则直接读 json | 每层耗时被记录，超预算在 summary 中告警 | 文件不存在 → 无法回答"门禁是否瓶颈" |
| C12 | 阶段四 | `gate.py`、CI | 临时注入一条 A1 违规 import + 一条 R06 `double` 金额，跑门禁 | 门禁必须红，且报出规则编号 | 门禁仍绿 → 规则静默失效 |

**DoD（各阶段完成定义）**：阶段一 = C1+C2+C3 全绿且反证可复现；阶段二 = C4+C6+C7+C8 全绿；
阶段三 = C9+C10+C11 全绿；阶段四 = C12 可重复执行。任何一项只做到"代码写了"不计完成。

---

## 执行状态

| # | 阶段 | 状态 | 依据 |
|---|---|---|---|
| C0 | 契约 | 已完成 | 本文件已按 §4.6 建档（frontmatter + 双索引），`gate.py validate` 通过 |
| C1 | 阶段一 | 未开始 | 待新增 `.github/workflows/ci.yml` |
| C2 | 阶段一 | 未开始 | 待改 `install-hooks.ps1` 为 `core.hooksPath` |
| C3 | 阶段一 | 未开始 | 待补 `vitest` 依赖与 CI `frontend` job |
| C4 | 阶段二 | 未开始 | 待加 jacoco `check` goal 与 patch 阈值 |
| C5 | 阶段二 | 未开始 | 待重定义 pre-push 契约 |
| C6 | 阶段二 | 未开始 | 待 CI 剖面置 `allowStoreCreation=false` |
| C7 | 阶段二 | 未开始 | 待建 `spec-checks/fixtures/` |
| C8 | 阶段二 | 未开始 | 待加 `gate.py doctor`（`detect_jdk` 已有，直接复用） |
| C9 | 阶段三 | 未开始 | 待加 gitleaks / osv-scanner job |
| C10 | 阶段三 | 未开始 | 待引入 failsafe 与 integration tag |
| C11 | 阶段三 | 未开始 | 待写 gate-metrics 采集 |
| C12 | 阶段四 | 未开始 | 待建立门禁红队自审 |

---

## 变更记录

- 2026-11-18T20:04 初稿：四层门禁现状核查、缺口分级（P0×4 / P1×4 / P2×3）、分阶段优化方案与落地顺序。
- 2026-11-18T20:20 归档为 plan 类：补 `session-plan/v1` frontmatter；新增「验收契约」（C0–C12，含命令与反证）
  与「执行状态」「变更记录」两节；路径与命名对齐规格 §4.6；结论字段固化为 `verdict: FAIL`。

## 链接

- 契约来源：`docs/session-archive-spec-v1.md` §4.6（plan 类契约）、§4.4（质量门禁规则）
- 评审入口：`.githooks/`、`tools/session-archive/gate.py`、`platform/spec-checks/`、`.workflow/build.yml`
- 数据来源：`docs/process/*/*/manifest.json`（提交与测试证据）、`platform/*/target/site/jacoco/jacoco.xml`
- 方案轨副本：`docs/summary/plan/quality-gate-review-2026-11.md`（内容与本文件一致，`canonical` 指回本文件）
