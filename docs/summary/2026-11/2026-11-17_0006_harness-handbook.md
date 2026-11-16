---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T00:17:13+08:00
extends: session-archive-gate
refs:
  - feat/2026-11-17_0006_harness-handbook
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 507
  passRate: "100%"
  lineCoverage: "37.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# Harness Handbook 三级文档体系（锚点生成 + 漂移门禁）

## 概述
工程文档只有零散的 spec 与 schema，改运行时行为时无法快速定位到源码；行号手工维护必然过期
docs/handbook 三级结构（L1 总览/L2 阶段/L3 实现单元），L3 由代码内 @handbook 锚点脚本生成行为→源码位置表；build.py sync/check/list 提供生成与漂移校验，pre-push 与 HandbookDriftTest 双入口阻断漂移

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `7a1d78a` | feat | feat: Harness Handbook 三级文档与锚点漂移门禁 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 507, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：37.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_0006_harness-handbook/manifest.json`
- 上一份同域总结：session-archive-gate
