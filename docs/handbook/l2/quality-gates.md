# 质量门禁：四层入口与修法

> 入口：`git commit` / `git push` / PR / `mvn verify`。
> 适用：提交、推送、合并被拦下时，先在这里定位是哪一层、为什么、怎么修。
> 设计动机与取舍见 [`docs/quality-gate-design-v1.md`](../../quality-gate-design-v1.md)。

---

## 1. 四层各管什么

| 层 | 入口 | 跑什么 | 本地命令 |
|---|---|---|---|
| L1 pre-commit | `git commit` | 活跃会话 + 变更模块增量测试，证据按工作区指纹复用 | `python tools/session-archive/gate.py gate-pre-commit` |
| L2 pre-push | `git push` | 会话已归档、归档链完整、handbook 漂移、前端自检、spec-checks 全量 | `python tools/session-archive/gate.py gate-pre-push` |
| L3 CI | PR / push | 5 个 required checks（backend / frontend / docs / contract / security） | `gh pr checks <n>` |
| L4 spec-checks | `mvn verify` | ArchUnit 棘轮、R01–R15、错误码契约、凭证扫描、**迁移基线 M1**、schema 预算、API 契约 | `mvn -pl spec-checks test` |

**能绕过吗**：L1/L2 可以用 `--no-verify` 跳过，但 `0.1.0-snapshot` 已加 ruleset（必须走 PR + 5 个必过检查），
真实边界在 L3。

---

## 2. 按症状找修法

| 症状 | 所在层 | 修法 |
|---|---|---|
| `没有进行中的会话归档` | L1 | `python tools/session-archive/gate.py start --category feat\|bug\|chore --slug <kebab> --title <中文>` |
| `测试未通过，禁止提交` | L1 | 看 surefire 报告；`SESSION_ARCHIVE_TEST_CMD='mvn -B -pl <模块> test'` 可指定命令 |
| `另一个 worktree 正在测试，排队等待…` | L1 | 不是错误；锁在各自 git dir 下，`test.lock` 属主进程已死可手工删除 |
| `本 worktree 的会话未归档` | L2 | `gate.py finish --problem … --solution … --verification …` |
| `存在未提交的归档/总结文件` | L2 | 提交 `docs/process`、`docs/summary` |
| `存在未归档的代码提交` | L2 | 每个代码提交都要被某份 manifest 覆盖；补录或回退 |
| `Harness Handbook 已漂移` | L2 | `python tools/handbook/build.py sync` 后提交 `docs/handbook` |
| `[M1] 已发布被修改 / 已发布未登记` | L4 | 见 §3 |
| `duplicate key … already exists`（PG） | 运行期 | 显式 id 搬行后序列落后；已有 V4 自动修，新代码走 `IdentitySequenceSync` |

---

## 3. M1 迁移守卫

判断依据：**发布 tag（`x.y.z`）= 已发布边界**；基线 `spec-checks/migration-baseline.txt` 记录
已发布迁移的 **LF 归一化 sha256**。

| 情形 | 结果 | 怎么办 |
|---|---|---|
| 已发布迁移内容被改 | 红 | 恢复原文件 + 新增 `V(n+1)__*.sql`（`ALTER TABLE` / `DO $$ … $$`） |
| 已发布但基线没登记 | 红 | 确认内容与 tag 一致后重算基线 |
| tag 之后新增的迁移 | 绿（列清单） | 可以自由改；发布切点重算 |
| 改了未发布迁移 | 红一次 | 跑重算命令，提交 baseline 变化 |

重算（**唯一正确姿势**，别用 `Get-FileHash` 手算）：

```bash
cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true test
```

发布时间线：

```bash
git tag -a 0.1.0 -m "…"        # 标签严格 x.y.z，不带 v
git push origin 0.1.0
cd platform && mvn -pl spec-checks -Dtest=MigrationGuardTest -Dmigration.baseline.update=true test
```

CI 前提：`actions/checkout` 需 `fetch-depth: 0` + `fetch-tags: true`，否则浅克隆看不到 tag，
未发布迁移会被误判成已发布（不会漏放已发布改动，方向是安全的）。

---

## 4. 规则从哪来、往哪加

| 规则 | 实现位置 |
|---|---|
| 架构依赖方向、字段注入、`Java util.Date` 等 | `spec-checks/src/test/java/.../arch/ArchitectureRulesTest`、`ForbiddenPatternScan` |
| 错误码分组与异常类对应 | `ErrorCodeContractTest` + `spec-checks/error-code-baseline.txt` |
| 决策面凭证 | `ConfigCredentialScanTest` |
| 迁移不可改 | `MigrationGuardTest` + `MigrationBaseline` + `migration-baseline.txt` |
| 归档链 / 会话契约 | `ProcessArchiveTest` → `tools/session-archive/gate.py validate` |
| Handbook 漂移 | `HandbookDriftTest` → `tools/handbook/build.py check` |

新增一条规则的三件套：**规则实现 + fixture 自测（应命中/不应命中）+ 基线（默认零，放宽需独立提交）**；
失败信息必须带处方，否则下一个人只会觉得"门禁太苛刻"。
