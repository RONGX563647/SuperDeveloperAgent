---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T09:10:20+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_0905_pipeline-log-scroll
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 619
  passRate: "100%"
  lineCoverage: "39.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 流水线右侧日志被裁掉，缺少滚动

## 概述
流水线运行详情列用 overflow:hidden，产物/镜像面板一多就把日志区挤出可视区且无法滚动，日志尾部看不见
run-detail 改为 overflow-y:auto + overscroll-behavior:contain，日志区 flex:1 1 320px/min-height:320px 保底并保留自身滚动条，阶段时间线 flex:none；新增 pipelineLayout.test.ts 锁住这三条约束

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `8bb486b` | bug | fix(pipeline): 运行详情列可纵向滚动，日志区不再被挤出可视区 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 619, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：3
- 行覆盖率：39.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-18_0905_pipeline-log-scroll/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
