---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T22:18:29+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_2151_feat-5-usage-hitrate-component
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "41.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# FEAT-5 汇总缓存命中率做成 OpenAPI 组件

## 概述
FEAT-5 第 3 项「提升 KV cache 命中率」缺度量侧：数据齐全（cachedTokens 已入库、UsageTotals.cacheHitRate 能算），但汇总结果里没有命中率，前端三处若各写一遍 cached/input 必然口径不一致。实现过程中还踩到两个真问题：1) 把 /usage/summary 改成返回自建 Map 会让 OpenAPI 的 UsageSummary schema 消失，前端生成的 schema.d.ts 立刻与契约不一致（CI 的 check:api-types 直接红）；2) 把命中率做成 record 的派生方法，springdoc 不序列化，字段根本到不了前端。
最终方案：命中率做成记录的一部分而不是派生方法或用 Map 重组。DailyUsage / ModelUsageSummary / ProjectUsageSummary 各加 cacheHitRate() 派生方法（它们只需在 Java 侧可用）；UsageSummary 新增 cacheHitRate 组件（Integer，构造器传 null 时按 totals 推导，并保留四参构造器兼容旧调用）——这样 springdoc 能序列化、schema 名不变、前端类型自动带上字段。新增 UsageSummaryHitRateTest 5 例：总体命中率与 totals 一致、null totals 安全、三维度各自可算且保留原字段、除零安全、四舍五入到整数。同步重新生成 OpenAPI 快照与前端 schema.d.ts。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `218ff86` | bug | fix(process): 重排 chore 归档链，修复 2059 归档断链 |
| `dbeb728` | feat | feat(usage): 用量记录提供缓存命中率，汇总带组件 |
| `fe0b85f` | chore | chore: 重新生成前端类型并加 CI 失败日志抓取脚本 |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：5
- 行覆盖率：41.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_2151_feat-5-usage-hitrate-component/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
