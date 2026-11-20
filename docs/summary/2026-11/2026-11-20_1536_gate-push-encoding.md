---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T15:36:41+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-20_1536_gate-push-encoding
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "38.3%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 预推门禁前端自检输出编码修复

## 概述
预推门禁前端自检输出编码修复
fix(process): 预推门禁前端自检按 UTF-8 读取输出，不再因 GBK 解码抛线程异常

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `7573c5c` | bug | fix(process): 预推门禁前端自检按 UTF-8 读取输出，不再因 GBK 解码抛线程异常 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：38.3%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-20_1536_gate-push-encoding/manifest.json`
- 上一份同域总结：worktree-commit-hook
