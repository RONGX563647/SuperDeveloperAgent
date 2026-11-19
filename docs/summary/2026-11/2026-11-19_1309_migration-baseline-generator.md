---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T13:10:23+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1309_migration-baseline-generator
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 29
  passRate: "100%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 迁移基线改为生成物：未登记即红、失败带处方

## 概述
CI 报三个迁移文件『已被修改』，但内容一个字节都没动——基线哈希由人用 Get-FileHash 手算，是工作区原始字节口径：Windows 检出 CRLF、Linux CI 检出 LF，同一份内容两个哈希。d91cd59 已把守卫改成 LF 归一化，但『谁在什么时候重算基线』仍靠手改 txt，且未登记的迁移只打一行 hint——忘了补录就等于 M1 静默失效。
把基线改成生成物：新增 MigrationBaseline 承载口径（LF 归一化 sha256，与 Flyway checksum 一致）、校验（区分被修改/未登记）、整份重算与处方文本；MigrationGuardTest 相应瘦身并把『未登记』从提示升级为失败，失败信息直接给恢复命令、新增迁移模板与重算命令；migration-baseline.txt 由 rewrite 重写注释头，写明口径、禁止手算、以及重算时机。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `a38ca42` | chore | test(spec-checks): 迁移基线改为生成物：未登记即红、失败带处方 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：3
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1309_migration-baseline-generator/manifest.json`
- 上一份同域总结：worktree-commit-hook
