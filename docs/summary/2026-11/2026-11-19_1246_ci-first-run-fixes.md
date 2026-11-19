---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:47:30+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1246_ci-first-run-fixes
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 285
  passRate: "100%"
  lineCoverage: "39.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修 CI 首次运行的 5 类失败

## 概述
修 CI 首次运行的 5 类失败
fix(ci): 修复首次 CI 运行的 5 类失败

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `ce7af61` | bug | fix(ci): 修复首次 CI 运行的 5 类失败 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 285, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：39.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1246_ci-first-run-fixes/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
