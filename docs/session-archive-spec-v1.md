# 会话存档机制规格（Session Archive Spec v3）

> 目标：把每一次 agent 研发会话「改了什么、为什么改、测试证据是什么」沉淀为可验证、只增不改的档案，
> 并在二级 summary 中对交付与**测试质量（覆盖率、用例增长、质量门禁）**做量化总结。
> 归档不是描述，是账：每个文件、每次提交、每条测试证据都可与 git 实际内容比对。

---

## 1. 参考的开源设计

| 来源 | 借鉴点 |
|---|---|
| ADR（joelparkerhenderson/architecture_decision_record） | 只增不改；更正 = 新记录 + supersede 回指，不修改历史 |
| Keep a Changelog（keepachangelog.com） | 人类可读的变更总结结构；按时间分文件；每条变更归类 |
| Codecov | **patch coverage**（本次新增代码被测试覆盖的比例）、**coverage delta**（覆盖率变化量），而非只看绝对值 |
| SonarQube Quality Gates | 质量门禁：多条件（测试通过、新增代码覆盖阈值、无新增未覆盖文件）合成 PASS / WARN / FAIL |
| Aider / Claude Code 会话记录 | 会话与文件改动绑定留存；工具调用次数等过程证据 |
| Certificate Transparency | hash 链防篡改：改历史文件必然断链 |

---

## 2. 目录结构

```
docs/
  session-archive-spec-v1.md            # 本规格
  process/                              # 原始档案：一次 agent 会话 = 一个文件夹
    feat/    2026-11-16_2245_deploy-versioned-env/
             └── manifest.json（+ 可选 attachments/：截图、失败日志）
    bug/     …
    chore/   …
    docs/    …
    deploy/  …
  summary/                              # 人读总结（markdown + frontmatter），双索引
    2026-11/                            # 时间维（按月）
      2026-11-16_deploy-track.md
    deploy-track/                       # 功能维
      2026-11_versioning.md
```

## 3. process 归档规范

### 3.1 命名与分类

- 文件夹名：`YYYY-MM-DD_HHMM_<英文slug>`（kebab-case），分钟级，固定 +08:00；同分钟冲突加 `-2`；中文标题写在 manifest。
- 一次会话一个归档、归主导类；`commits[].category` 逐条记录每个提交的真实类别，跨类信息不丢。

| 类别 | 判据 | 测试要求 |
|---|---|---|
| feat | 新能力 | **强制单测**，`tests.result=PASSED` 且 `casesAdded ≥ 1` |
| bug | 缺陷与测试失败修复 | **强制回归用例**（先红后绿），`tests.evidence` 须含修复前后对比 |
| chore | 重构/构建/依赖/配置 | 含非测试代码改动时 `impact.verification` 非空 |
| docs | 纯文档 | 豁免单测 |
| deploy | 部署链路/环境/发布/运维 | 豁免单测，但 `impact.verification` 非空（dry-run / 健康检查 / 测试任一） |

### 3.2 manifest.json（schema v2）

```json
{
  "schema": "process-manifest/v2",
  "id": "feat/2026-11-16_2245_deploy-versioned-env",
  "category": "feat",
  "title": "支持按版本号部署与打 tag 保留旧版本",
  "createdAt": "2026-11-16T22:45:00+08:00",
  "session": {
    "agentModel": "glm-5.3",
    "promptSummary": "部署全靠 git hash 区分，需要版本号与多版本并存",
    "toolCallCount": 34
  },
  "problem": "背景 2-3 句",
  "solution": "方案要点 2-3 句",
  "commits": [
    {
      "sha": "249a2a0",
      "subject": "feat(deploy): 版本号与打 tag 多版本部署",
      "category": "feat",
      "files": [
        { "path": "platform/.../DockerDeployer.java", "status": "M",
          "additions": 45, "deletions": 12, "blobHash": "git hash-object 改动后内容" }
      ]
    }
  ],
  "tests": {
    "required": true,
    "files": [
      { "path": "platform/.../DockerfileToolsTest.java", "status": "A",
        "additions": 108, "deletions": 0, "blobHash": "..." }
    ],
    "casesAdded": 4,
    "command": "mvn -B -pl agent-gateway -am test",
    "result": "PASSED",
    "evidence": "Tests run: 135, Failures: 0, Errors: 0, Skipped: 2"
  },
  "impact": { "modules": ["agent-executor", "agent-gateway"], "verification": "135 测试通过；npm build 通过" },
  "followUps": ["单容器 volume 需重新部署一次才生效"],
  "links": { "relatedIds": [], "summaryRefs": ["2026-11/2026-11-16_deploy-track"] },
  "chain": { "prev": "同类上一份 selfHash（首份 GENESIS）", "self": "sha256(除 self 外全部字段)" }
}
```

**与代码强关联**：`commits[].sha` 必须存在于 git 历史；`files[].blobHash` = 改动后内容的
`git hash-object`，校验时重算比对；`additions/deletions` 来自 `git diff --numstat`。
测试文件与用例同在此账内。

### 3.3 测试门槛与提交纪律

强制工作流，任何 feat/bug 会话按此顺序执行：

```
写实现 → 写单元测试 → 跑测试全绿 → 分类提交代码 → 写归档（记录测试证据）
```

- 测试不通过不允许 `git commit`（skill 流程强制 + spec-checks 兜底）。
- bug 的回归用例必须先红后绿：`tests.evidence` 写明「先复现：xxx 失败；修复后：全绿」。
- 归档时测试失败/未跑 = 不合格归档，`ProcessArchiveTest` 让构建失败。
- **并发与复用**：pre-commit 的测试在公共 `.git/session-archive/test.lock` 上串行执行，
  主树与 `.agent/worktrees/*` 不会同时跑两套全量测试；同一工作区内容（索引树 + 未暂存改动 +
  未跟踪文件）在 `SESSION_ARCHIVE_EVIDENCE_TTL_MIN`（默认 60 分钟）内已验证为绿色时直接复用证据，
  改提交信息之类的重试不再重跑。缩小范围用 `SESSION_ARCHIVE_TEST_CMD`。

### 3.4 只增不改

1. **hash 链**：每类（feat/bug/…）一条链，`chain.prev` 指向同类上一份 `self`，首份为 GENESIS。
   改历史文件 → `self` 对不上；删任一份 → 断链。
2. **校验器**：`spec-checks` 新增 `ProcessArchiveTest`，校验命名 / schema / 链完整 / `sha` 存在 /
   `blobHash` 一致 / tests 门槛。
3. **git 留痕**：对历史归档的任何修改必然产生新 commit。

更正错误 = 新归档 + `links.relatedIds` 回指。禁止修改/删除/重命名已有归档。
存量豁免：校验器只管 GENESIS 之后新增的归档。

### 3.5 归档覆盖契约（必需归档）

归档不是"最近一次做了就行"，而是**每个代码提交都必须被某份 manifest 覆盖**：

- 基线 = 最早新增 `docs/process` 文件的提交；基线之前的提交属于制度建立前的存量，不回溯。
- `gate.py validate`（由 `ProcessArchiveTest` 在每次测试中执行）检查**已归档锚点之前**的缺口：
  只要中间有代码提交没被归档，校验就失败——不允许"跳过中间提交、只归档最新一次"。
- `gate.py gate-pre-push` 要求从锚点到 `HEAD` 的**全部**代码提交都被覆盖，缺一个就禁止推送。
- 锚点之后尚未归档的提交属于"本轮会话进行中"，提交过程中不误报；会话结束必须 `finish`。
- 漏归档只能补录：新建 manifest（`createdAt` 取会话开始时间，`chain.prev` 按同类时间序接上），
  不得改动或删除已有归档。

### 3.6 会话覆盖契约（每次对话都归档）

提交覆盖只保证"有提交的会话"，纯问答/只读排查会漏。会话维度补两层：

- **docs 类允许空提交**：`commits: []`、`tests.required=false`、`result=NOT_RUN`，
  用于记录没有代码改动的会话；feat/bug/deploy 仍然必须有提交。
- **`session.ideSessionId` 全局唯一**：一个 AI 开发会话只能有一份归档，
  `validate` 对重复 id 报错；老归档没有该字段视为存量，不回溯。
- **定时对账**：`python tools/session-archive/sweep.py` 读取 IDE 自动写下的会话摘要
  （`topics.md`），按 8 小时窗口把提交归属到会话：
  - 窗口内提交都已被归档 → 生成 docs 归档并在 `links.relatedIds` 回指代码归档；
  - 窗口内有未归档提交 → 跳过并报警，必须先走 `start`/`finish` 补代码归档；
  - 已存在 `ideSessionId` 的会话不重复建档（幂等）。
- 对账任务每 2 小时跑一次；它只是兜底，正常流程仍是 `start` → `finish` → `validate`。

## 4. summary 规范（本版核心增强）

### 4.1 定位与文件

- 一份 summary = 一个 markdown 文件，双索引两处存放（时间维 + 功能维），内容互链。
- 路径：`docs/summary/<YYYY-MM>/<YYYY-MM-DD>_<slug>.md` + `docs/summary/<track>/<YYYY-MM>_<slug>.md`。
- 同等只增不改：同主题新进展 = 新文件 + frontmatter `extends` 回指旧文件（ADR supersede 风格）。

### 4.2 frontmatter（数据聚合层）

```yaml
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-30T20:00:00+08:00
extends: null                      # 同主题旧总结 id（新进展回指）
refs:                              # 引用的 process 归档 id（必须全部真实存在）
  - feat/2026-11-16_2245_deploy-versioned-env
  - bug/2026-11-16_2257_worktree-broken-git
metrics:                           # 由 skill 从 manifest.tests + JaCoCo 聚合生成，禁止手编
  sessions: 4                      # 本期归档数
  casesAdded: 12                   # 新增用例总数
  casesTotal: 147                  # 本期最后一次全量运行的总用例
  passRate: "99.3%"                # 通过率（含跳过说明）
  lineCoverage: "61.2%"           # 全仓行覆盖率（JaCoCo；无报告记 N/A）
  coverageDelta: "+1.8%"           # 对比 extends/上一份同 track 的变化量（Codecov delta）
  patchCoverage: "84%"             # 本期新增行覆盖率（Codecov patch coverage）
  gate: PASS                       # 质量门禁结论：PASS / WARN / FAIL（规则见 4.4）
```

### 4.3 正文结构（Keep a Changelog 风格）

```markdown
# 2026-11 部署链路月度总结

## 概述
2-4 句：本期主线、关键决策、一句话结论。

## 交付清单
| 归档 | 内容 | 测试 |
|---|---|---|
| feat/2026-11-16_2245_deploy-versioned-env | 版本号 + 打 tag 多版本部署 | PASSED，+4 用例 |
| bug/2026-11-16_2257_worktree-broken-git | 半残 worktree prune+清理 | PASSED，先红后绿 |

（每行引用 process id；测试状态必须与所引用归档的 tests 段一致，校验器比对）

## 测试与质量总结
### 覆盖率（Codecov 风格：看变化量与新增覆盖，不只看绝对值）
| 模块 | 用例变化 | 行覆盖率变化 | 新增行覆盖 |
|---|---|---|---|
| agent-executor | +6 | 58.1% → 62.4% | 86% |
| agent-tools | +4 | 71.0% → 71.2% | 91% |
| web（typecheck/build） | — | — | build 通过 |

### 质量门禁（SonarQube Quality Gates 风格）
| 条件 | 阈值 | 实际 | 结果 |
|---|---|---|---|
| feat/bug 归档测试全绿 | 100% | 4/4 | ✅ |
| 新增行覆盖（patch） | ≥ 80% | 84% | ✅ |
| 新增未覆盖文件 | 0 | 0 | ✅ |
| 跳过用例需注明原因 | 全部 | 2（按环境跳过，已注明） | ✅ |
> **Gate: PASS**（任一条件不满足则 WARN/FAIL，并列出补救项）

### 风险敞口
- 未覆盖的新增文件：无 / 逐个列出
- 跳过用例及原因
- 覆盖率下降的模块及归因

## 遗留与建议
followUps 汇总 + 下期建议。

## 链接
引用的 process id 列表、extends 指向的旧总结。
```

### 4.4 质量门禁规则（gate）

| gate | 条件 |
|---|---|
| **PASS** | 所有条件满足：refs 内 feat/bug 全 PASSED；patchCoverage ≥ 80% 或 N/A 且无未覆盖新增文件；跳过用例均有归因 |
| **WARN** | 数据缺失（如未接入 JaCoCo、patchCoverage=N/A）或 patchCoverage 在 60%~80% |
| **FAIL** | 任一 feat/bug 归档测试非 PASSED；存在新增未覆盖测试文件；未归档的 feat/bug 提交 |

FAIL 必须在下一期 summary 的「遗留与建议」中给出修复计划。

### 4.5 覆盖率数据来源

- 引入 JaCoCo（Maven 插件，绑定 test 阶段：`prepare-agent` + `report`，输出 `target/site/jacoco*.xml`），
  不改变现有测试行为。
- `session-archive` skill 解析 JaCoCo XML 计算 `lineCoverage` / `patchCoverage` / 模块分解
  （patch = 本期 refs 涉及的新增行覆盖）。
- 未接入前各字段记 `N/A`，gate 降级为 WARN，不阻塞流程——但接入后 N/A 即 WARN。

## 5. 落地件与执行

| 件 | 位置 | 作用 |
|---|---|---|
| JSON Schema | `docs/schemas/process-manifest-v2.json`、`session-summary-v1.md` 的 frontmatter 说明 | 校验依据 |
| 归档 skill | 仓库 skill `session-archive` | 会话末：跑测试 → 从 git 提取 commits/files → 生成 manifest（含 hash 链）→ 聚合 metrics 生成/追加 summary |
| 校验器 | `spec-checks` 新增 `ProcessArchiveTest`（附单测，作为 feat 归档自身的合规样本） | §3.3/§3.4/§4.2 全部规则执行者 |
| 覆盖率 | 根 pom 加 JaCoCo 插件 | §4.5 数据来源 |

执行顺序（首次落地）：
1. JaCoCo 插件 → 2. schema 文件 → 3. `ProcessArchiveTest` + 其单测 → 4. skill →
5. 本次会话自身生成首份归档（GENESIS）：`docs/feat/2026-11-16_*_session-archive-mechanism/`。

## 6. 分类判例（供归档时快速对照）

- 版本号部署、Dockerfile 自愈工具 → **feat**
- worktree 半残清理、ErrorCodeUniquenessTest 独立运行失败 → **bug**
- 错误码枚举收敛、设计令牌重构 → **chore**
- 本规格文档 → **docs**
- 端口/环境/发布链路改造 → **deploy**
