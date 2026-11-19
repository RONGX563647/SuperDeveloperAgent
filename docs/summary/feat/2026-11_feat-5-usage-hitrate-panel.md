---
canonical: docs/summary/2026-11/2026-11-19_2326_feat-5-usage-hitrate-panel.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T23:31:29+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_2326_feat-5-usage-hitrate-panel
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 264
  passRate: "100%"
  lineCoverage: "32.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# FEAT-5 用量面板显示各维度缓存命中率

## 概述
上一批只给 UsageSummary 加了总体命中率组件，三个维度仍是派生方法：Java 能调用、单测也过，但 springdoc 只序列化 record 组件，导致 JSON 与 OpenAPI 里没有该字段，前端表格那一列永远空白——而接口看起来一切正常。同时 UsagePanel 顶部命中率还在用 cachedTokens/inputTokens 手算，与后端形成两套口径（除零、四舍五入、百分比还是小数都可能不一致），后端一改算法界面对不上且很难发现。
把 DailyUsage / ModelUsageSummary / ProjectUsageSummary 的 cacheHitRate 改成 record 组件（构造器传 null 时按 token 数推导，旧构造器保留以兼容 Sqlite 层的既有调用点）；UsagePanel 顶部改用后端返回的 cacheHitRate，按模型与按天两张表各加「命中率」列，拿不到时显式显示 — 而不是回退到自己算。新增 UsageHitRateSerializationTest 5 例：用反射断言「是 record 组件」——domain 不允许依赖 Jackson（架构 A2），而「是不是组件」正是「会不会进 JSON」的本质判据；另有取值、无输入为 0、显式值优先、旧构造器仍可用四条。同步重新生成 OpenAPI（cacheHitRate 从 1 处增至 4 处）与前端类型。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `239ee27` | feat | feat(usage): 用量面板显示各维度缓存命中率 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 264, Failures: 0, Errors: 0, Skipped: 6
- 新增用例：5
- 行覆盖率：32.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_2326_feat-5-usage-hitrate-panel/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
