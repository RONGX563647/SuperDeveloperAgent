---
type: plan
status: plan
owner: docs
lastVerified: 2026-11-15
related: [../README.md, ../项目规约-v1.0.md, ../规约自动化检查-v1.0.md, ../adr/0005-安全边界放在平台层.md]
---

# Agent 文档治理方案 v1

> 日期：2026-11-15 ｜ 状态：部分实施（断链 + 未登记已进 CI；front-matter / drift / `_inbox` 暂缓）
> 上游：[docs 总览](../README.md)（Wikidoc v0）、[项目规约 v1.0](../项目规约-v1.0.md)、[规约自动化检查 v1.0](../规约自动化检查-v1.0.md)
> 结论：**不引入外部文档平台**。本轮只落地「断链 + 未登记」两项机械检查；front-matter、`covers/lastVerified` 漂移、`_inbox` 闸门在触发条件出现前不实施。

---

## 0. 一句话结论

Agent 文档之乱，根源不是"生成得太多"，而是三处缺失：**写入门槛**（什么算正式文档）、**同步责任**（代码改了谁改文档）、**收敛入口**（Agent 该读哪一份）。本方案在现有 Wikidoc 上补「契约 → 同步 → 收敛 → 审批」四道闸门，不新建平台、不自动改文档、不强制一次性迁移存量。

---

## 1. 现状盘点：已经有的，不再造

2026-11-14 的 Wikidoc 已经解决了"有没有索引、断链、废弃登记"的问题，本方案不重复建设。

| 能力 | 现状 | 位置 | 判断 |
|---|---|---|---|
| 唯一索引 | `docs/README.md` 是 single source of index，含登记表、状态、归属、复审日 | [docs/README.md](../README.md) | **保留**，作为登记与可达性的唯一判据 |
| 状态与复审 | 稳定 / 现行 / 历史 / 规划，90 天复审 | [docs/README.md §3](../README.md) | **保留**，升级为可机械读取 |
| 问题分级 | P0 / P1 / P2 + 禁止无验证删除 | [docs/README.md §4](../README.md) | **保留**，作为检查结果的处置语义 |
| 废弃留痕 | 删除前登记原因与替代物，表永久保留 | [deprecated/README.md](../deprecated/README.md) | **保留**，暂由人工评审 |
| 断链巡检 | 扫描全仓 Markdown 相对链接，非 0 退出 | [check-links.mjs](../../scripts/docs/check-links.mjs) | **保留**，CI 独立执行 |
| 检索入口 | `llms.txt` 给 LLM / RAG 的阅读顺序 | [llms.txt](../../llms.txt) | **保留**；一致性校验暂缓 |
| 规约机械检查 | JUnit + ArchUnit 随 `mvn verify` 跑，违规即红 | [规约自动化检查](../规约自动化检查-v1.0.md)、`platform/spec-checks` | **借鉴其三条原则**：不引入新服务、能自动的自动、可豁免不可静默 |

**真正缺的是四件事：**

1. **契约没有落到单篇文档**：登记表只管元信息，正文结构仍然任意；Agent 可以继续产出"看似合理"的散文。
2. **复审靠日历**：没有 `lastVerified` 与"本文覆盖哪些代码路径"，无法自动发现"代码已改、文档没改"。
3. **契约与漂移检查没进 CI**：断链与未登记已接入；front-matter、`covers/lastVerified` 仍未落地。
4. **写入没有闸门**："人类审批"只是原则，Agent 仍可直接在 `docs/` 新增或覆盖文件，没有机械约束（本轮暂缓）。

---

## 2. 目标与不变量

| # | 不变量 | 机械落点 |
|---|---|---|
| I1 | **先登记，后写入** | 新文档未出现在索引中即 CI 失败（D04） |
| I2 | **先契约，后内容** | 6 种文档类型 + 每类必填章节（D01–D03） |
| I3 | **变更即同步** | `covers` 指向代码路径，`lastVerified` 与最后一次提交比较（D06–D07） |
| I4 | **单一事实源** | 同一事实只写一处，其余链接；重复段落进报告（D08） |
| I5 | **人类审批不可绕过** | Agent 只写 `docs/_inbox/` 或 PR 分支，`main` 有分支保护 |

文档生命周期（与登记表状态一一对应）：

```
草案 draft ──▶ 现行 current ──▶ 稳定 stable ──▶ 历史 history ──▶ 废弃 deprecated
                    │                                  ▲
                    └──────── 规划 plan（未实施）──────┘
```

| 中文状态 | front-matter 值 | 含义 | 复审 |
|---|---|---|---|
| 草案 | `draft` | 未评审，不得被引用为结论 | 不设 |
| 现行 | `current` | 当前有效、仍在演进 | 90 天 |
| 稳定 | `stable` | 可依赖 | 90 天 |
| 规划 | `plan` | 设计稿，未实施 | 不设 |
| 历史 | `history` | 仅追溯 | 不设 |
| 废弃 | `deprecated` | 已被替代，等待删除或归档 | 不设 |

---

## 3. 方案总览：四道闸门

```
Agent 产出 ──▶ docs/_inbox/（不可直接合入）
                    │ 人工筛选、改号、补 front-matter
                    ▼
               PR ──▶ 契约检查 + 断链 + 登记 + 漂移报告
                    │ 文档 owner approve
                    ▼
              正式文档 ──▶ docs/README 索引 ──▶ llms.txt / RAG ──▶ Agent 检索
                    │
                    └── 代码提交触达 covers ──▶ STALE ──▶ 人工复审 ──▶ 更新 lastVerified
```

| 闸门 | 解决什么 | 外部参考 | 本仓形态 |
|---|---|---|---|
| **A 契约** | 文档结构与准入 | OttoDoc 的 6 类型 + lint | front-matter + 6 类必填章节 + `check-docs.mjs` |
| **B 同步** | 代码变了文档不变 | Ohara 的 PR 联动、Dox 的 `lastVerified` | `covers` + `check-drift.mjs` + PR 模板 |
| **C 收敛** | 散落、重复、检索不到 | Wiki-Forge / loci-rag / RTFM | 唯一索引 + 去重/孤立报告 + `llms.txt` 一致性 |
| **D 审批** | Agent 自写自审、覆盖丢失 | Libraryan 单一写入者、slashdocs 评审 | `_inbox` + 分支保护 + owner approve + 成品归档 |

---

## 4. 闸门 A：文档契约

### 4.1 六种类型

| 类型 | 收什么 | 不收什么 | 必填章节（H2） | 存量映射 |
|---|---|---|---|---|
| `runbook` | 可照做的操作步骤 | 设计推演、方案对比 | 适用场景 / 前置条件 / 步骤 / 验证 / 回滚 / 排障 | `operation/`、`dev/`、[流水线工作台使用指南](../流水线工作台-使用指南-v1.md) |
| `reference` | 稳定事实：接口、模块、配置、规约 | 未定方案、一次性分析 | 范围 / 事实表 / 使用方式 / 已知边界 | [Code Wiki](../Code-Wiki.md)、`api/`、`architecture/`、[项目规约](../项目规约-v1.0.md) |
| `decision` | 已定结论与理由 | 尚未选择的候选 | 背景 / 问题 / 候选 / 决策 / 影响 / 风险 / 后果 | `adr/`、[CICD 决策](../CICD方案替代与自创-决策-v1.md) |
| `explanation` | 背景、调研、复盘、证据链 | 当作当前结论使用 | 问题 / 结论 / 证据 / 与现状的关系 | 历史可行性分析、`_research`（迁入后） |
| `plan` | 未实施的设计稿与路线 | 已落地事实（应改 design） | 目标 / 现状 / 方案 / 顺序 / 验收 / 不做什么 | [窄控制器](../窄控制器设计-单机滚动部署器.md)、本文 |
| `design` | 已定且实施/演进中的方案 | 未定选项、一次性记录 | 目标与不变量 / 现状盘点 / 方案 / 接口与数据 / 风险 / 验收 | 流水线、发布、接入、多项目、环境模板等 |

> 索引页（`docs/README.md`、`adr/README.md`、`deprecated/README.md`、`changelog/`）归 `reference`，只受 D04/D05 约束，不套用正文模板；用行内豁免声明（见 §4.4）。

### 4.2 Front-matter（新文档强制，存量渐进）

```yaml
---
type: design                 # runbook | reference | decision | explanation | plan | design
status: current              # draft | current | stable | history | plan | deprecated
owner: executor              # 模块或角色，必须能找人
lastVerified: 2026-11-15     # 最后一次人工确认与代码一致
reviewBy: 2027-02-14         # current / stable 必填
covers:                      # 本文断言了哪些代码路径的事实
  - platform/agent-executor/src/main/java/**/Pipeline*.java
related: [../adr/0002-不造CI-DSL用动作+仓库内配方.md]
---
```

字段最小化到 7 个；`title` 取正文 H1，不重复填写。**存量 15 篇继续用文首引用块**，采用「touch-to-migrate」：文档下次被修改时补齐 front-matter，并把路径从 `docs/.docgov-legacy` 白名单中删除。白名单清空即迁移完成。

### 4.3 准入规则（新增文档时必须回答）

1. **能不能改现有文档？** 同一事实已有归属，必须改原文并用链接引用，不得新建。
2. **是不是一次性产物？** 日报、会议纪要、临时脚本、单次修复记录不进 `docs/`，放 `docs/_inbox/` 或仓库外。
3. **能不能归入 6 类之一？** 不能，先改本方案再写文档，禁止"先建目录再想类型"。
4. **有没有 owner 与复审日？** `current` / `stable` 缺一不可。
5. **删了以后去哪？** 删除必须登记 [废弃登记](../deprecated/README.md) 并给出替代物。

### 4.4 机械检查 D01–D08

新增 `scripts/docs/check-docs.mjs`，复用 `check-links.mjs` 的链接判定与遍历逻辑。

| 规则 | 判定 | 阶段 1 | 处置 |
|---|---|---|---|
| **D01** front-matter 完整 | 非 legacy 文档缺 `type/status/owner/lastVerified` | **阻断** | 补元信息 |
| **D02** 枚举合法 | `type` / `status` 不在允许集合 | **阻断** | 改值 |
| **D03** 必填章节 | 对应类型的必备 H2 缺失 | 警告 | 补章节，或先标 `draft` / `plan` |
| **D04** 已登记可达 | `docs/**/*.md` 未被 `docs/README.md` 或子索引链接 | **阻断** | 补登记 |
| **D05** 相对链接 | 复用 `check-links`，存在失效目标 | **阻断** | 修链接 |
| **D06** covers 有效 | glob 不匹配任何文件，或指向 `docs/` | 警告 | 修正覆盖范围 |
| **D07** 复审与漂移 | `reviewBy` 过期，或 `covers` 在 `lastVerified` 后有提交 | 警告 | 人工复审并更新 |
| **D08** 重复与孤立 | 规范化后同段落 >300 字出现在多篇；标题重复 | 报告 | 合并或改链接 |

豁免沿用规约检查的做法：行内 `<!-- doc-ignore: D03 索引页无正文模板 -->`，必须带规则号与原因；同一规则全仓豁免 >5 次时，触发"是规则有问题还是文档有问题"的复审。豁免数量进健康看板。

**阶段 1 只阻断 D01 / D02 / D04 / D05 四条**。理由与[规约自动化检查 §4](../规约自动化检查-v1.0.md)一致：一次上满规则会产生大量误报，最后团队会把检查整体关掉。

---

## 5. 闸门 B：文档与代码同步

### 5.1 `covers` + `lastVerified` 的语义

- `covers`：只写**代码路径**（`platform/**`、`scripts/**`、`.workflow/**`），不写文档路径；
- `lastVerified`：**最后一次由人确认"文档与这些代码一致"**的日期；
- Agent **MUST NOT** 自行更新 `lastVerified`——它只能提出"建议复审"，由 owner 在 PR 中确认。

### 5.2 漂移检测

新增 `scripts/docs/check-drift.mjs`：

```bash
node scripts/docs/check-drift.mjs            # 只出报告，退出码 0
node scripts/docs/check-drift.mjs --fail     # 阶段 2 稳定后再用于 CI
```

判定方法（不猜测语义，只比对时间）：

1. 读取每篇文档的 `covers` 与 `lastVerified`；
2. `git log -1 --format=%cs -- <covers 匹配到的文件>` 取最后一次代码提交日期；
3. 晚于 `lastVerified` → 标记 `STALE`，输出文档、覆盖路径、相关提交；
4. `history` / `plan` 不参与；`draft` 只提示。

报告示例：

```
STALE docs/环境配置事实层与分环境门控-方案-v1.md
  lastVerified: 2026-11-15
  covers: platform/agent-tools/src/main/java/**/Env*.java
  last code change: 2026-11-20 (a1b2c3d)
  action: owner 复审后更新 lastVerified，或修文
```

### 5.3 PR 联动（Ohara 的本地化）

PR 模板固定三问，缺一即评审驳回：

1. 本次是否新增/修改 `docs/`？登记表同步了吗？
2. 是否改动了某篇文档 `covers` 内的代码？该文 `lastVerified` 是否仍成立？
3. 是否删除文档？废弃登记与替代物在哪？

按[项目规约 §12](../项目规约-v1.0.md)，文档与代码**分开提交**，但可以在同一分支；不强制"代码 PR 必须带文档 PR"，先用报告暴露漂移，避免用流程噪音换形式主义。

### 5.4 不做什么

- 不做自动改写、自动更新 `lastVerified`（`auto_fix=false` 保持）；
- 不因代码 PR 未改文档就直接阻断合入（阶段 2 仅提示，阶段 3 再评估）；
- 不用大模型判断"文档是否过时"——只回答"覆盖的代码有没有动过"，语义判断留给 owner。

---

## 6. 闸门 C：散落文档的收敛层

### 6.1 两个入口，职责不同

| 入口 | 面向 | 职责 |
|---|---|---|
| [docs/README.md](../README.md) | 人 | 唯一索引、登记表、状态、复审、处置规则 |
| [llms.txt](../../llms.txt) | Agent / RAG | 高价值文档的阅读顺序；**不复制正文** |

规则：每篇 `current` / `stable` 文档必须能从 `docs/README.md` 一跳可达；`llms.txt` 只收录高价值入口，但其中的每条链接必须存在。D04 负责可达性，D08 负责重复。

### 6.2 去重与孤立（报告，不阻断）

- **重复段落**：去空白、去标点后做段落哈希；同一段 >300 字出现在两篇以上，报告"应保留哪篇、其余改链接"。
- **重复标题**：同名 H1/H2 跨文档出现，提示确认是否同一事实。
- **孤立文档**：D04 已覆盖；报告按"未被任何索引引用"分组，避免和"登记了但没人链"混淆。

### 6.3 检索导出（阶段 3 可选）

当 Agent 需要跨文档检索时，再增加 `scripts/docs/export-rag.mjs`：按 H2 切片，输出 JSONL（含 `type/status/owner/lastVerified/covers` 元数据），产物不进 Git。**当前不引入向量库**：`rag_source=docs+adr+api` 先由 `llms.txt` + 索引承担。

### 6.4 外部工具的取舍

| 项目 | 借鉴什么 | 本仓决策 | 触发引入的条件 |
|---|---|---|---|
| **Wiki-Forge** | 索引多格式文档、漂移检测、MCP 查询 | 暂不引入 | 需要索引 PDF/DOCX/PPTX，或 Agent 必须跨格式检索 |
| **Docmancer** | 重复内容调和、版本历史、批准后的本地索引 | 暂不引入 | D08 报告连续两轮上升，人工合并跟不上 |
| **loci-rag** | 笔记/文档/Agent 记忆统一检索 + 审计台 | 暂不引入 | 文档 >60 篇，或检索质量成为 Agent 的主要瓶颈 |
| **RTFM** | 24 种解析器 + MCP 上下文切片 | 暂不引入 | 已接入代码/数据/文档多源，且 `llms.txt` 无法覆盖 |

> 统一门槛（见 §8）：能内网部署、不产生第二事实源、关键约束可机械校验、维护成本低于自研脚本。四条中不满足三条，只借理念，不引入依赖。

---

## 7. 闸门 D：写入、审批与成品归档

### 7.1 单一写入者（Libraryan 的本地化）

不装 git hook（`--no-verify` 可绕过），改用**路径 + 分支保护**：

| 主体 | 可写位置 | 约束 |
|---|---|---|
| Agent | `docs/_inbox/<slug>.md` | 必须带 front-matter 与来源说明；CI 阻止 `_inbox/**` 合入 `main` |
| Agent | PR 分支 `docs/<slug>` | 不得直推 `main`（项目规约 §12 已禁止） |
| 人（文档 owner） | `docs/` 正式路径 | 从 `_inbox` 提升，补登记、补链接、改状态 |

这等价于"指定唯一写入者"，但把约束放在 CI 与分支保护，而不是可绕过的本地钩子。Agent 不持有 `main` 写凭证，与[项目规约 A1](../项目规约-v1.0.md)「AI 决策面不持有写凭证」一致。

### 7.2 人类闸门与三权分立

| 角色 | 谁 | 做什么 |
|---|---|---|
| 写作 | Agent / 文档作者 | 产出草案，只落到 `_inbox` 或 PR 分支 |
| 校验 | `check-docs` / `check-drift` / `check-links` | 机械判定结构、登记、链接、漂移 |
| 审批 | 文档 owner | 确认事实、更新 `lastVerified`、合并 |

- 文档 PR 至少 1 名 owner approve；修改本方案（契约本身）需 2 人；
- 写规则的人不能给自己豁免；豁免必须出现在 PR diff 中；
- `auto_fix=false` 保持不变：检查只出草案与报告，不自动提交。

### 7.3 成品文档归档（slashdocs / tunc-mcp 的本地化）

Agent 生成的报告、验收、复盘属于"成品"，但**不引入逐句评论服务**：

```
docs/reports/YYYY-MM/<slug>/
├── index.md          # 结论、范围、验证方式
├── assets/           # 图片、附件；二进制不散落在 docs 根目录
└── manifest.yml      # sourceCommit / generatedBy / reviewedBy / inputs
```

- 版本管理由 Git 承担，评审用 PR 行内评论；
- `manifest.yml` 记录生成来源，避免"这份 PPT 是哪次 Agent 运行产出的"无法追溯；
- 大文件（pptx/xlsx）优先放 Git LFS 或对象存储，仓库只留链接与校验和；
- **不引入 tunc-mcp**：当前没有多机、多 Agent 共享 DOCX/XLSX 的硬需求；一旦出现，再按 §8 门槛评估。

---

## 8. 外部方案选型结论

| 项目 | 核心机制 | 结论 | 原因 |
|---|---|---|---|
| **OttoDoc** | 6 类型契约、准入规则、lint、三权分立 | **借理念，不引入** | 独立服务 + 模型审稿；本仓可用 front-matter + Node 脚本覆盖 80% |
| **Libraryan** | 单一写入者 + git hook | **改造引入** | 用 `_inbox` + 分支保护替代 hook，避免 `--no-verify` 绕过 |
| **Ohara** | PR 联动文档更新、stale 标记 | **改造引入** | Gitee PR + `covers` 漂移报告，不引入 MCP 服务 |
| **Dox** | `lastVerified` + `dox check --drift` | **自研等价物** | `check-drift.mjs`，约百行，无新依赖 |
| **Wiki-Forge** | 多格式索引、漂移检测、MCP | **暂缓** | 当前文档以 Markdown 为主，`llms.txt` 够用 |
| **Docmancer / loci-rag** | 去重、审计、统一检索 | **暂缓** | 先做 D08 报告，达到触发条件再评估 |
| **RTFM** | 多解析器 + MCP 切片 | **暂缓** | 检索面未稳定，过早接入会固化错误切分 |
| **slashdocs** | HTML 成品 + 逐句评论 + 版本账本 | **暂缓** | 当前评审者是研发，PR 评论足够；非研发逐句批注出现时再评估 |
| **tunc-mcp** | 跨会话 Agent 制品存储 | **不引入** | Git + `docs/reports/manifest.yml` 已可追溯 |

**引入门槛（四条）**：内网可部署且无强制外部账号；不产生第二个事实源；关键约束可机械校验；维护成本低于自研脚本。不满足三条以上，只借理念。

---

## 9. 落地路径

### 阶段 1：止损（已落地 2026-11-15）

| 交付物 | 内容 |
|---|---|
| `scripts/docs/check-docs.mjs` | 仅 D04：`docs/**/*.md` 未被任一 README 链接即失败 |
| `scripts/docs/check-links.mjs` | 复用现有断链检查 |
| `docs/specs/README.md`、`docs/_research/README.md` | 子索引，消除规格/调研的未登记项 |
| CI | `verify` 前执行两条 Node 命令，任一失败即中断 |
| `docs/README.md` | 命令、巡检项、健康看板同步 |

**验收**：未登记文档或断链时 CI 必红；当前 43 篇文档 0 未登记。

**暂缓（触发条件出现前不做）**：

- **front-matter / 6 类契约 / D01–D03**：正式文档（不含 `specs`、`_research`）超过 40 篇，或正文结构开始失控；
- **`covers` + `lastVerified` 漂移**：代码高频变更，且出现"文档不可信"的实际投诉；
- **`docs/_inbox` 闸门 / PR 模板**：多个 Agent 并发写 `docs/`，冲突或覆盖真实发生。

### 阶段 2：同步（暂缓）

| 交付物 | 内容 |
|---|---|
| `scripts/docs/check-drift.mjs` | `covers` + `lastVerified`，默认报告模式 |
| 健康看板 | 增加 `STALE` 列表与复审过期清单 |
| D03 / D06 / D07 | 先警告，连续两轮无误报后评估升级为阻断 |
| 存量迁移 | 被修改的文档按 touch-to-migrate 补齐 front-matter |

**验收**：能列出所有"覆盖代码已变、文档未复审"的页面；owner 能在报告里直接定位提交；无自动提交。

### 阶段 3：收敛与审批（暂缓）

| 交付物 | 内容 |
|---|---|
| D08 | 重复段落 / 重复标题报告 |
| `llms.txt` 一致性 | 每条链接存在；高价值入口与登记表不矛盾 |
| 成品归档 | `docs/reports/` + `manifest.yml` 模板 |
| 检索导出（可选） | `export-rag.mjs` + JSONL，不进 Git |
| Agent 写入审计 | 定期抽查：是否存在绕过 PR 的 `docs/` 变更 |

**验收**：孤立/未登记文档为 0；重复内容完成一次基线清理；Agent 新增文档 100% 经 `_inbox` 或 PR。

---

## 10. 验收指标

| 指标 | 目标 | 采集方式 |
|---|---|---|
| 新增文档契约合规率 | 暂缓（未上 front-matter） | — |
| 未登记 / 孤立文档 | 0 | D04 / `check-docs` |
| 内部断链 | 0 | `check-links` |
| `stable` / `current` 的 STALE 页面 | 暂缓（未上 drift） | — |
| 删除未登记 | 0 | PR diff + [废弃登记](../deprecated/README.md) |
| Agent 直写 `main` | 0 | 分支保护 + 审计 |
| 重复段落 | 暂缓（未上 D08） | — |

巡检命令（与[docs/README §5](../README.md)合并执行）：

```bash
node scripts/docs/check-docs.mjs
node scripts/docs/check-links.mjs
node scripts/docs/check-drift.mjs
```

---

## 11. 风险与不做什么

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| 契约形式主义 | 字段多、模板重，作者开始复制空壳 | 7 个字段；索引页豁免；只对新建/改动文档强制 |
| 误报导致检查被关 | 一次上满规则、大量历史文档不合规 | 阶段 1 只阻断 4 条；其余只报告；白名单显式可见 |
| Agent 绕过 | 直接改 `docs/` 或 `--no-verify` | 分支保护 + CI + 不给写凭证；不依赖本地 hook |
| 迁移半途而废 | 24 篇存量长期挂白名单 | touch-to-migrate；白名单只减不增；健康看板公示 |
| 双事实源 | 引入 Wiki / RAG 后双向覆盖 | 只做索引同步；RAG 只读；发现循环同步立即暂停 |
| 工具蔓延 | 每个问题引入一个平台 | §8 四条门槛 + 触发条件；能脚本解决的不上服务 |

**不做什么：**

1. 不部署 OttoDoc / Wiki-Forge / slashdocs / tunc-mcp 等独立服务；
2. 不让 Agent 自动改写、自动删除、自动更新 `lastVerified`；
3. 不强制一次性迁移 24 篇存量文档；
4. 不做 `docs/` 与外部 Wiki 的双向同步；
5. 不把 RAG 索引当作事实源，事实源永远是仓库 Markdown；
6. 不在阶段 1 引入逐句评论、协同编辑、制品存储等重能力。

---

## 12. 与现有体系的关系

| 现有机制 | 关系 |
|---|---|
| [项目规约 §12 提交规范](../项目规约-v1.0.md) | 已有"文档与代码分开提交"；本方案建议后续把 `check-docs` 写入规约，作为合入前置 |
| [规约自动化检查](../规约自动化检查-v1.0.md) | 代码约束留在 JUnit / ArchUnit；文档约束用 Node 脚本 + CI step，理由：Markdown 解析与仓库现有 Node 工具链一致，且不把 JDK 测试模块当通用脚本运行时 |
| [docs/README 限制开关](../README.md) | 保持 `auto_fix=false`；建议阶段 2 新增 `doc_contract`、`drift_check` 两个开关，默认 report-only |
| [ADR-0005 安全边界](../adr/0005-安全边界放在平台层.md) | 文档写入权限按同一原则处理：Agent 不持 `main` 写凭证，写操作经人类闸门 |
| [废弃登记](../deprecated/README.md) | 删除流程不变，由 D04 + PR diff 增加机械校验 |
