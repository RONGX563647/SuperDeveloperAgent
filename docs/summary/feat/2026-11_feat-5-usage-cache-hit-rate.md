---
canonical: docs/summary/2026-11/2026-11-19_2123_feat-5-usage-cache-hit-rate.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T21:24:51+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_2123_feat-5-usage-cache-hit-rate
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 31
  passRate: "100%"
  lineCoverage: "41.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# FEAT-5 用量汇总返回各维度缓存命中率

## 概述
FEAT-5 第 3 项要「提升 KV cache 命中率」，但缺度量侧：命中率数据其实已经齐全（每次模型调用都记了 cachedTokens，UsageTotals.cacheHitRate() 也能算），可 /usage/summary 只回原始 token 数，会话接口倒是给了命中率。结果是设置页要看命中率只能前端自己用 cached/input 算，而三处（按天/按模型/按项目）各算一遍必然出现口径不一致——除零、四舍五入、百分比还是小数，谁也没统一。没有可信数字，「压缩 prompt 有没有省到钱」就无法验证。
在 /usage/summary 里统一算好命中率：顶层给总体 cacheHitRate，daily / byModel / byProject 的每一行都补 cacheHitRate，口径只有一份（cached/input 的整数百分比，四舍五入）。字段名保持不变（day / modelName / projectId），不破坏既有契约；输入 token 为 0 时返回 0 而不是异常——新会话第一条必然如此。用 Map 组装而不是给每个维度新建 record：这三个 record 是存储层契约，加字段会牵连 Sqlite 实现与前端类型生成，为一个派生值不值得。新增 UsageCacheHitRateTest 4 例：总体命中率与四舍五入、除零安全、三个维度都带命中率且保留原字段名、行内 token 字段齐全。同步重新生成 OpenAPI 快照与 API 文档基线。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `3b3f463` | feat | feat(usage): 用量汇总返回各维度缓存命中率 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：4
- 行覆盖率：41.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_2123_feat-5-usage-cache-hit-rate/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
