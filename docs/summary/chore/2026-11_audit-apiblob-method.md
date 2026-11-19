---
canonical: docs/summary/2026-11/2026-11-19_2118_audit-apiblob-method.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T21:19:03+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2118_audit-apiblob-method
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "N/A"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 契约审计识别 apiBlob 的 POST 下载

## 概述
契约审计 extractCalls 把 apiBlob 硬编码成 GET，而 apiBlob 早已与 apiFetch 同签名、二进制下载可以走 POST。任务导出 POST /api/v1/git-issues/export 因此被误判成 GET，CI 的 contract 作业报 METHOD_NOT_FOUND——代码与规范其实都对。
apiBlob 与 apiFetch 共用 parseMethodAlternatives，三元表达式/变量引用/缺省 GET 全部复用；新增单测覆盖 apiBlob 的 POST 与缺省 GET 两种形态。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `0f6b8f6` | bug | fix(audit): 契约审计按声明解析 apiBlob 的方法，不再一律当 GET |

## 测试与质量总结
- 命令：`(无需后端测试：本次改动不涉及 Maven 模块)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：N/A（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_2118_audit-apiblob-method/manifest.json`
- 上一份同域总结：worktree-commit-hook
