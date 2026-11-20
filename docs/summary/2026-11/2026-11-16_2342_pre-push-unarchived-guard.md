---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-16T23:42:41+08:00
extends: jacoco-surefire-argline
refs:
  - chore/2026-11-16_2342_pre-push-unarchived-guard
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "37.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# pre-push 阻止未归档代码提交

## 概述
pre-push 只校验 active 会话与归档提交状态；用 --no-verify 跳过的代码提交不会被发现，仍可推送
pre-push 增加锚点校验：取最新归档的最后一个提交，逐条检查其后提交，凡含非豁免路径改动且未被任何归档登记即拒绝推送

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `255874f` | chore | chore: pre-push 阻止未归档的代码提交 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：37.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-16_2342_pre-push-unarchived-guard/manifest.json`
- 上一份同域总结：jacoco-surefire-argline
