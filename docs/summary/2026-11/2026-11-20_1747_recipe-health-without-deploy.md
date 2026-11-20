---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T17:49:01+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_1747_recipe-health-without-deploy
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 259
  passRate: "100%"
  lineCoverage: "43.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 固化脚本缺 deploy 阶段导致健康检查必败

## 概述
固化脚本（RECIPE 源）有 health 阶段但没有 deploy 阶段时被当'可复现脚本'回放：run-2a5d03c9 / run-899118e6 构建+镜像全成功，health 探一个没人监听的端口（body= 空、E-401、回滚空转）
PipelineDefinitionResolver 新增 satisfiesDeployStage 门：health 有而 deploy 无 → 视为部署语义断裂，与 satisfiesDeployTarget 同一退回路径；纯构建流水线（无 deploy 段）放行给引擎报 E-221

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c5fbcce` | bug | fix(executor): 固化脚本有 health 无 deploy 阶段时拒绝回放，退回仓库/平台定义 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 259, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：1
- 行覆盖率：43.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_1747_recipe-health-without-deploy/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
