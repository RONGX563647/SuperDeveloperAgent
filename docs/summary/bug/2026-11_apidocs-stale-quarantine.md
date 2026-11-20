---
canonical: docs/summary/2026-11/2026-11-20_1239_apidocs-stale-quarantine.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T12:42:43+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_1239_apidocs-stale-quarantine
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 208
  passRate: "100%"
  lineCoverage: "43.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-36 快照生成失败时隔离过期契约

## 概述
BUG-36：api_docs 依赖反射 springdoc 私有方法，升级后静默退回过期快照，Agent 拿过期文档回答接口问题且无提示
快照生成失败时把现存快照改名隔离（.stale-时间戳）+ log.error 明确告警，api_docs 工具返回不可用而不是过期契约；新增 ApiDocsStaleQuarantineTest 2 例

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `702e860` | bug | fix(gateway): OpenAPI 快照生成失败时隔离过期快照，不再静默退回旧契约（BUG-36） |
| `b886478` | chore | test(gateway): BUG-36 隔离行为回归 2 例（失败路径调用隔离 + move 语义） |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 208, Failures: 0, Errors: 0, Skipped: 5
- 新增用例：2
- 行覆盖率：43.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_1239_apidocs-stale-quarantine/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
