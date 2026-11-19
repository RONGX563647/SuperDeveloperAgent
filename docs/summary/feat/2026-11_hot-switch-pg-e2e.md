---
canonical: docs/summary/2026-11/2026-11-19_1646_hot-switch-pg-e2e.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T16:57:16+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_1646_hot-switch-pg-e2e
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 159
  passRate: "100%"
  lineCoverage: "40.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 真 PostgreSQL 端到端验证热切换

## 概述
热切换主链路（探活成功 → Flyway 建 PG schema → 搬行 → 原子替换 → 新后端可读）只有 SQLite 上的失败路径单测，方言、identity 列、ON CONFLICT 的 PG 语义都没被真库验证过。
新增 DatabaseHotSwapperPostgresIT 4 例并用 Docker postgres:16 实跑：全链路切换（断言 isPostgres、数据可读、platform_meta 由 Flyway 建出、db-backend.yml 已落盘）；源已非 SQLite 时搬数据被拒且数据不动；探活失败绝不写配置；切回 SQLite 后 PG 数据保留。踩到两个坑并修正：探针表必须用平台自带 run_ledger（搬运器契约是只在目标库已有同名表之间搬行，自建表永远搬不过去），源库不能用 @TempDir（被换下的连接池锁住 -wal 会让用例误判失败）。surefire 纳入 **/*IT.java，ci.yml 的 backend job 加 postgres service 并注入 AGENT_TEST_PG_URL。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `a8ec01a` | chore | test(db): 补真 PostgreSQL 端到端验证热切换 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 159, Failures: 0, Errors: 0, Skipped: 6
- 新增用例：4
- 行覆盖率：40.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_1646_hot-switch-pg-e2e/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
