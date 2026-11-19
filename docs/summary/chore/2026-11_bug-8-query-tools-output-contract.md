---
canonical: docs/summary/2026-11/2026-11-19_1347_bug-8-query-tools-output-contract.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T13:47:50+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1347_bug-8-query-tools-output-contract
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 61
  passRate: "100%"
  lineCoverage: "40.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-8 任务查询输出字段契约回归

## 概述
BUG-8（GitIssueQueryTools 输出丢失 version）：字段本身已在批次 2 随任务版本读写一起补上（get 带 version 行、list 每行带 v版本），但当时没有为这两处输出留下任何回归测试。缺测试的后果是：字段再被谁改动掉时没人发现，而缺失表现为「问它这条任务关联哪个版本，答不上来」——听起来像模型能力问题，其实是契约被破坏。
新增 GitIssueQueryToolsOutputTest（3 个用例）：get_git_issue 必须同时输出 code / version / labels 三行；list_git_issues 每一行都要带 v版本 与 code（不是只校验首行）；空值必须用占位符 v- / labels:- 而不是纯空白，否则模型无法区分「有字段但值为空」与「压根没输出该字段」。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `b31ee17` | chore | test(tools): 锁住任务查询输出的 code/version/labels 字段 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-tools,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-tools test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 61, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：3
- 行覆盖率：40.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1347_bug-8-query-tools-output-contract/manifest.json`
- 上一份同域总结：worktree-commit-hook
