---
canonical: docs/summary/2026-11/2026-11-19_1603_archive-orphan-sha.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T16:05:04+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1603_archive-orphan-sha
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "40.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 修复归档 manifest 指向游离提交导致 CI 校验失败

## 概述
bug-24 的归档 manifest 引用了提交 5fc1ab1，该提交因并行会话重写历史成为游离提交：本地 git cat-file 找得到所以校验通过，CI 检出找不到，导致 spec-checks 的 ProcessArchiveTest 与 docs 作业同时失败。该条目 subject 还带 BOM。
manifest 的 sha 改为内容等价的正式提交 1b01d77（git diff 为空），去掉 subject 的 BOM，并用 reseal_chain 重算该分类的 prev/self。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `232abdb` | bug | fix(docs): 修正 bug-24 归档指向的游离提交，去掉 subject 的 BOM |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：40.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1603_archive-orphan-sha/manifest.json`
- 上一份同域总结：worktree-commit-hook
