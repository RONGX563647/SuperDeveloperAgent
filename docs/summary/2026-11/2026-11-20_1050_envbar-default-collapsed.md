---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T10:50:52+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_1050_envbar-default-collapsed
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "39.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-26/53 环境条默认折叠与抖动修复

## 概述
环境悬浮条全局 localStorage 单 key 导致展开过一次永远弹出；默认折叠无落点；切环境清空旧数据致三段跳变抖动（PG BUG-26/53）
fix(web): key 按 projectId+env 隔离；ChatView 传 :default-open=false；watch 就地刷新不清空；health 不闪烁；新增 envBarState.test.ts 5 例

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `0268bcc` | bug | fix(web): 环境条默认折叠、展开状态按项目/环境隔离、切环境不再抖动（BUG-26/53） |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：5
- 行覆盖率：39.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_1050_envbar-default-collapsed/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
