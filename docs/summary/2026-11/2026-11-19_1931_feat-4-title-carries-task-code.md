---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T19:33:12+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_1931_feat-4-title-carries-task-code
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 137
  passRate: "100%"
  lineCoverage: "41.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# FEAT-4 新建任务标题自动带任务编码

## 概述
FEAT-4（任务）第 1 项「新建任务标题需要带任务编码，git 提交和存档使用此编码作为标题」只做了一半：归档目录已经支持 --task-code（目录名与归档标题带编码），但任务本身的标题没带——看板上仍然只能看到「登录失败」而不是「BUG-3 登录失败」，列表、提交信息、归档名三处对不上同一个编号。根因是编码在创建时由存储层按分类自增分配，调用方提交草稿时根本不知道自己会拿到哪个编号，所以只能由存储层统一加前缀。
在 SqliteGitIssueStore.save 里，创建路径把分配到的编码写进标题（withCodePrefix），读回与返回值都带；更新路径不动标题，避免用户改一次标题就多叠一层前缀。withCodePrefix 做成幂等：已写「BUG-7 」「BUG-7: 」的标题不重复加（大小写不敏感），而「BUG-7xx」这种前缀相同但不是编码的仍会补上真编码。这样任务页、Agent 工具、Git webhook 三个入口行为一致，不必各自实现。新增 GitIssueTitleCodeTest 5 例：标题带编码且与 code 字段一致（含读回）、幂等、前缀须独立、更新不变、空值安全。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `028d277` | feat | feat(task): 新建任务标题自动带编码 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-infra,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-infra test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 137, Failures: 0, Errors: 0, Skipped: 1
- 新增用例：5
- 行覆盖率：41.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_1931_feat-4-title-carries-task-code/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
