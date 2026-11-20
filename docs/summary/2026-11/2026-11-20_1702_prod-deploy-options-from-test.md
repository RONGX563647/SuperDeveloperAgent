---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T17:04:56+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_1702_prod-deploy-options-from-test
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 468
  passRate: "100%"
  lineCoverage: "43.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 生产按版本部署：选项改为测试环境成功运行的版本

## 概述
生产按版本部署：选项改为测试环境成功运行的版本
fix(deploy): 生产按版本部署的选项改为测试环境成功运行的版本

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `94b4703` | bug | fix(deploy): 生产按版本部署的选项改为测试环境成功运行的版本 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor,agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 468, Failures: 0, Errors: 0, Skipped: 5
- 新增用例：5
- 行覆盖率：43.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_1702_prod-deploy-options-from-test/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
