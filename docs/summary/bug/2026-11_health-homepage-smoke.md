---
canonical: docs/summary/2026-11/2026-11-18_0109_health-homepage-smoke.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T01:12:32+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_0109_health-homepage-smoke
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 606
  passRate: "100%"
  lineCoverage: "39.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 健康检查补首页冒烟

## 概述
健康检查只探后端接口，前端静态托管/反向代理配错导致首页 5xx 时发布仍被判成功
健康接口与容器就绪后补一次首页冒烟：根路径 5xx 判失败，404/401/403 视为无首页，探活地址即根路径时跳过；失败摘要带首页原因

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `7f9294c` | bug | fix(pipeline): 健康检查补首页冒烟，页面 5xx 不再假通过 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 606, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：3
- 行覆盖率：39.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-18_0109_health-homepage-smoke/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
