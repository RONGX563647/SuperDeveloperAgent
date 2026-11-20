---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T09:00:13+08:00
extends: session-archive-gate
refs:
  - feat/2026-11-17_0856_readiness-one-click-dockerfile
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 523
  passRate: "100%"
  lineCoverage: "37.3%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 就绪清单一键生成 Dockerfile

## 概述
就绪清单里「仓库没有 Dockerfile」没有修复入口，用户只能去对话或手写
新增 POST /projects/{id}/dockerfile 复用 ensure_dockerfile 生成待确认补丁；清单修复项 kind=action；前端确认后自动 confirm+apply 提交到 agent/fix-* 分支

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `52e05d0` | feat | feat(readiness): 就绪清单支持一键生成 Dockerfile |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 523, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：37.3%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_0856_readiness-one-click-dockerfile/manifest.json`
- 上一份同域总结：session-archive-gate
