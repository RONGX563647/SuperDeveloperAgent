---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T17:46:26+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_1740_agent-commit-isolated-index
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 700
  passRate: "100%"
  lineCoverage: "40.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 会话提交改用独立 index（并行 worktree 不争锁、提交后状态干净）

## 概述
多会话共享 bare 仓库时，会话工作树用普通 git add -A/commit 会争用 .git/index.lock；且提交后真实 index 未同步 HEAD，git status 把刚提交的文件误报为 D/MM，模型据此重复提交或判断失败。
GitWorktreeManager 新增 commitOnly：临时 index + GIT_INDEX_FILE 执行 read-tree HEAD → add -A → commit --no-verify，提交后把真实 index read-tree 到新 HEAD；SessionWorktreeEditor.commit 改用它。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `75fd4ed` | bug | fix(infra): 会话提交改用独立 index，避免并行 worktree 争锁 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 700, Failures: 0, Errors: 0, Skipped: 1
- 新增用例：1
- 行覆盖率：40.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-18_1740_agent-commit-isolated-index/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
