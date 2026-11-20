---
canonical: docs/summary/2026-11/2026-11-19_1301_migration-guard-line-endings.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T13:04:21+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1301_migration-guard-line-endings
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 26
  passRate: "100%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 迁移守卫按 LF 归一化算哈希，消除跨平台误报

## 概述
CI 报 V1__platform_schema.sql 已被修改（基线 79bebf6d1b94 当前 dd3a4b616856），但文件内容没动过，本地 Get-FileHash 算出的正是基线值。根因是 .gitattributes 的 text=auto eol=lf 只约束仓库内 blob，工作区实际字节取决于 core.autocrlf 与检出平台：Windows 检出 CRLF、Linux CI 检出 LF，同一文件两个 sha256。基线按 Windows 原样字节生成，Linux CI 必然对不上；且误报是单向的，内容被改但两边都还是 CRLF 时反而可能漏报。
MigrationGuardTest.sha256 先做行尾归一化（CRLF→LF，孤立 CR 也归 LF）再算哈希，哈希只反映内容，与 Flyway 判定口径一致；migration-baseline.txt 重算为 LF 口径并把生成命令改成归一化版本，注释写明口径。新增两个用例：lineEndingsDoNotAffectHash 验证 LF/CRLF/混合三份同哈希，normalizationStillDetectsRealEdits 反向保证内容改动仍被发现。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `d91cd59` | bug | fix(spec-checks): 迁移守卫按 LF 归一化算哈希，消除跨平台误报 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：2
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1301_migration-guard-line-endings/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
