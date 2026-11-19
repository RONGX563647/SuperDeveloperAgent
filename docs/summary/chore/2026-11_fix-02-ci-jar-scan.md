---
canonical: docs/summary/2026-11/2026-11-19_2326_fix-02-ci-jar-scan.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T23:26:54+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2326_fix-02-ci-jar-scan
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 10
  passRate: "100%"
  lineCoverage: "1.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 迁移版本扫描兼容 jar classpath

## 概述
CI run 44551144198 backend 仍在 switchBackKeepsPostgresData 报 duplicate column source_ref：latestMigrationVersion 只扫 file: 协议，CI 上迁移脚本在 agent-infra 依赖 jar（jar: 协议）里，扫描返回 0 → baselineVersion=0 → 从 V1 重放
scanMigrationVersions 支持 file: 与 jar: 两种协议（JarFile 条目按 rest 段无 '/' 过滤文件）；全部扫不到兜底 1；失败路径记 warn。jar classpath 反射探针验证 sqlite/postgresql latest=5；目录 classpath 单测 6/6；PG 实库 IT 首跑与重跑各 4/4

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `8321703` | bug | fix(settings): 迁移版本扫描兼容 jar classpath，修复 PG 回切二次失败 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dtest=DatabaseHotSwapperPostgresIT,DatabaseHotSwapperTest -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 10, Failures: 0, Errors: 0, Skipped: 4
- 新增用例：0
- 行覆盖率：1.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_2326_fix-02-ci-jar-scan/manifest.json`
- 上一份同域总结：worktree-commit-hook
