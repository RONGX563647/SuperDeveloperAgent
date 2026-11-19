# 质量门禁设计（v1）

> 本文说明"这道门禁为什么长成这样"：分层、每层只干该干的事、规则怎么写、失败了怎么修。
> 重点是 **M1 迁移守卫** —— 它踩过三次真实的坑，坑的形状决定了现在的设计。
>
> 相关文档：Harness Handbook [`L2 质量门禁`](handbook/l2/quality-gates.md)（怎么用）、
> [`session-archive-spec-v1.md`](session-archive-spec-v1.md)（会话归档契约）。

---

## 1. 四层结构

| 层 | 入口 | 触发 | 跑什么 | 预算 | 能绕过吗 |
|---|---|---|---|---|---|
| L1 | `.git/hooks/pre-commit` → `gate.py gate-pre-commit` | `git commit` | 必须有活跃会话；变更模块及下游的**增量测试**（`-pl <模块> -amd`，排除 spec-checks）；证据按工作区指纹 + TTL 复用 | ≤ 60s（首跑 ~2min） | `--no-verify` |
| L2 | `.git/hooks/pre-push` → `gate.py gate-pre-push` | `git push` | 会话必须 finish；`docs/process`、`docs/summary` 干净；每个代码提交被归档覆盖；`validate`；handbook 漂移；前端自检；spec-checks 全量 | ≤ 3min | `--no-verify` |
| L3 | `.github/workflows/ci.yml` | PR / push 到 `main`、`0.1.0-snapshot` | 5 个 required checks：`backend (mvn verify)`、`frontend`、`docs`、`contract`、`security` | ≤ 10min | **不能**（ruleset 强制） |
| L4 | `platform/spec-checks`（并入 `mvn verify`） | 每次构建 | ArchUnit 棘轮、禁令正则 R01–R15、错误码契约 EC、决策面凭证扫描 C、**迁移基线 M1**、工具 schema 预算、API 文档契约、Handbook 漂移、归档校验 | — | 不能 |

分层原则：**本地求快反馈，远端求全覆盖**。L1/L2 可以被 `--no-verify` 绕过，所以规则的真实边界在 L3 ——
`0.1.0-snapshot` 已开 ruleset（必须走 PR + 5 个必过检查），直推被服务端拒绝。

---

## 2. 六条设计原则

1. **规则只有一份实现**。Java 侧只通过 `PythonScripts.run` 调 `tools/` 下的 Python（归档、handbook），
   避免"Java 一份、Python 一份"漂移。
2. **门禁是能执行文档**。失败信息必须自带处方（怎么修、跑什么命令），否则下一个人只会得出"门禁太苛刻"。
3. **棘轮只降不升**。错误码基线、ArchUnit store 只能缩小；放宽必须是一次显式、可 review 的改动。
4. **用锚点，不用记忆**。判断"已发布"靠发布 tag，判断"哪次会话改了什么"靠归档 manifest，
   判断"行为落在哪一行"靠 `@handbook` 锚点 —— 不靠人手维护的行号或口头约定。
5. **口径与下游一致**。迁移守卫的哈希口径必须等于 Flyway 的 checksum 语义，多出来的严格全是误报。
6. **降级要选安全的那个方向**。解析不到锚点时，宁可把未发布迁移也当成已发布（多要求一次登记），
   也不能把已发布迁移的改动放过去。

---

## 3. M1 迁移守卫（重点）

### 3.1 它要防什么

Flyway 在 `flyway_schema_history` 里存每个已执行迁移的 checksum。**改已发布迁移的文件**会让
checksum 变化 → 线上已跑过的库 `validate` 失败 → 启动直接挂；若关掉校验，则新装库拿新 DDL、
老库永远不补，**schema 静默分叉**（最难排查的一类 bug：单测全绿、真实后端行为错）。

所以规则是：**已发布迁移只能新增，不能原地改**。演进一律新增 `V(n+1)__*.sql`，
用 `ALTER TABLE` / `CREATE TABLE IF NOT EXISTS` / `DO $$ … $$`。

### 3.2 判定口径：LF 归一化 sha256

```
hash(file) = sha256( 把 CRLF / 孤立 CR 归一成 LF 后的字节 )
```

**为什么不用原始字节**：`.gitattributes` 的 `* text=auto eol=lf` 只约束仓库里的 blob，
工作区字节取决于 `core.autocrlf` 与检出平台。Windows 检出 CRLF、Linux CI 检出 LF，
同一份内容会算出两个哈希（见 §6 事故一）。归一化后哈希只反映内容，与平台无关 ——
这正是 Flyway 自己的口径（它同样忽略行尾差异）。

**为什么不让手算**：手算 = 选了一个口径，而口径只能由代码定义。
基线的注释头写明"不要用 `Get-FileHash` / `sha256sum` 手算"，重算只走一条命令。

### 3.3 锚点：发布 tag（`x.y.z`）

"哪些迁移算已发布"不能靠基线文件自己声明（那是循环论证），要靠 git 上的发布事实：

```
anchor   = git describe --tags --abbrev=0 --match '[0-9]*.[0-9]*.[0-9]*'
released = git ls-tree -r --name-only <anchor> -- platform   # 过滤 db/migration/*.sql
```

约定与仓库的发布模型一致（`domain/release/ReleaseVersion`）：标签严格 `x.y.z`、**不带 `v`**，
版本线 `x.y` 对应长期分支 `x.y.0-snapshot`。

**为什么必须有锚点**：只有基线（"未登记即红"）的话，**还没发布的迁移也被提前冻结** ——
同一个 PR 里想微调刚写的迁移，就得凭空开一个新版本号。有了锚点，未发布的可以自由演进，
发布切点补录即可。

**CI 前置**：`actions/checkout` 必须 `fetch-depth: 0` + `fetch-tags: true`。浅克隆没有 tag，
守卫只能退化成"全部按已发布"——它不会漏放已发布迁移的改动，但会让未发布迁移也被迫登记。
这条写在 `ci.yml` 的 checkout 注释里，改它之前先读本文。

### 3.4 三类判定

| 类别 | 判定 | 为什么 |
|---|---|---|
| 已发布 + 已登记 + 内容一致 | 通过 | — |
| 已发布 + 已登记 + 内容变了 | **失败**（附处方） | Flyway checksum 变，线上库起不来 |
| 已发布 + 未登记 | **失败** | 基线漏登记 = 这个文件没人守（旧版只打 hint，等于静默失效） |
| 未发布（tag 之后新增） | 通过（打印清单） | 还没上过线，可以改；发布切点重算基线 |

实现上分两层，便于自测：

- 纯逻辑 `MigrationBaseline.check(root, released, anchor)` —— 临时目录即可覆盖三条分支；
- 取锚点 `releaseTag(root)` / `releasedMigrations(root, tag)` —— 走 `git` 子命令，失败返回 `null` 走降级。

### 3.5 失败处方

```
处方：
  1) 【已发布】迁移被改 → 恢复原文件，改动落成新迁移：
     git log --oneline -- <路径>    # 找到含旧内容的提交
     git checkout <sha> -- <路径>
     新增 V<n+1>__<slug>.sql，用 ALTER TABLE / CREATE TABLE IF NOT EXISTS / DO $$ … $$
  2) 【已发布】但没登记 → 说明发布切点漏了补录：确认文件内容与 tag 一致后重算基线。
  3) 【未发布】迁移要改 → 直接改，发布切点重算基线即可（未发布的不受冻结约束）。
  重算（生成物，别手改哈希）：
     cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true test
口径：LF 归一化后的 sha256（与 Flyway checksum 一致，行尾差异不算改动）。
锚点：最近一次发布 tag（x.y.z）；CI 需 fetch-depth: 0 才能看到 tag。
```

### 3.6 重算时机与流程

| 场景 | 动作 |
|---|---|
| 新增迁移（未发布） | 什么都不用做，守卫会把它列成"未发布" |
| 改一个**未发布**迁移 | 直接改；本地会红一次，跑重算命令后提交 baseline 变化 |
| 发布切点 | 打 tag（`x.y.z`）→ 跑重算 → baseline 的 diff 就是"这一版新增了哪些迁移" |
| 改一个**已发布**迁移 | 不允许；按处方恢复 + 新增迁移 |

重算是**生成模式**（对齐 `-Derror.code.baseline.update=true` 的既有惯例）：它不判红，只重写文件并打印
`新增 / 更新 / 移除` 三项 diff —— 于是 baseline 的变化在 PR 里一定是可见的。

### 3.7 现状与不变量

- 当前锚点：`0.1.0`（首个发布快照）；基线登记 5 个迁移，全部落在标签内。
- 不变量：**基线里的每一条都必须能在某个发布 tag 的树里找到**（否则就是登记了不该登记的东西）。
- 自测：`MigrationGuardTest` 8 个用例 —— 行尾归一化、归一化不掩盖真实改动、
  已发布被改（带处方）、已发布未登记（红）、未发布放行、重算幂等、锚点可从 tag 树读到、真实仓库全绿。

---

## 4. 新增一条规则的标准三件套

1. **规则实现**（`spec-checks` 里的测试或 Python 脚本）+ **fixture 自测**：
   至少一组"应命中"、一组"不应命中"，否则规则写错了只会表现为"永远绿"。
2. **失败信息带处方**：说清违反了哪条、为什么、怎么修、跑什么命令。
3. **基线/棘轮**（如果这条规则有存量违规）：默认值为零，放宽必须独立提交并在 PR 里可见。

---

## 5. 已知缺口

| 缺口 | 现状 | 下一步 |
|---|---|---|
| patch coverage | 归档 summary 长期 `N/A`，只有 JaCoCo 报表没有 `check` 闸门 | 接 `jacoco:check` + diff 行覆盖率棘轮 |
| 集成测试层 | 无 failsafe；SQLite↔PostgreSQL 契约只在单测 mock 层验证 | Testcontainers + `@Tag("integration")` |
| spec-checks 自测覆盖 | `RuleFixtureSelfTest` 已覆盖部分禁令正则，未覆盖全部 R 规则 | 每条规则补 fixture |
| 浅克隆环境 | 无 tag 时守卫退化为"全部已发布" | CI 已固定 `fetch-depth: 0`；其它环境需显式取 tag |
| 门禁耗时 | 只有 `gate-metrics.json` 记录，未设预算告警 | pre-commit ≤ 60s / push ≤ 3min / CI ≤ 10min 的阈值告警 |

---

## 6. 三次真实事故（设计就是它们的形状）

### 事故一：CRLF 误报（2026-11-19）

CI 报三个迁移"已被修改"，内容一个字节没动。基线在 Windows 上用 `Get-FileHash` 按原始字节生成，
Linux CI 检出 LF 自然对不上 —— 被报的正好是工作区带 CRLF 的三个文件，纯 LF 的两个没事，指纹完全吻合。
**教训**：口径必须由代码定义，且必须等于下游（Flyway）的语义。修法：归一化 + 禁止手算。

### 事故二：手改基线（同一个上午）

修完口径后，重算仍靠人手改 txt：既容易用错口径打回原形，又没人保证"什么时候该重算"。
**教训**：生成物就该由程序生成。修法：`rewrite()` + 生成模式开关 + 注释头写明口径与时机。

### 事故三：未登记只打 hint

"未登记"只打印一行提示：忘了补录，M1 就静默失效 —— 门禁最怕的不是严，是"看起来在守、其实没守"。
**教训**：缺口的默认方向是失败。修法：升级为红，并补上锚点让它不至于苛刻到冻结未发布迁移。

---

## 7. 一句话结论

门禁的价值不在"拦住了多少"，而在**每条规则都能自证**：口径自证（哈希怎么算）、
锚点自证（什么算已发布）、失败自证（处方能直接执行）。三者缺一，规则就会在某个平台上
变成噪音或摆设 —— 上面三次事故各自踩中了其中一条。
