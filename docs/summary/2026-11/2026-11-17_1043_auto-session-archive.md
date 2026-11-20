---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T10:48:25+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-17_1043_auto-session-archive
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 533
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 会话自动存档：sweep 对账 + 全局 skill + 定时任务

## 概述
AI 开发会话只有有提交才归档，纯问答/分析会话永远进不了 docs/process，现有 hook 看不到会话
新增 sweep.py 读取 IDE 会话摘要对账补录 docs 类归档（空 commits、ideSessionId 幂等），validate 支持空提交会话并校验 ideSessionId 全局唯一；注册每 2 小时定时任务兜底

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `8cdc37d` | feat | feat(archive): 会话对账补录 docs 归档并支持空提交会话 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 533, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_1043_auto-session-archive/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
