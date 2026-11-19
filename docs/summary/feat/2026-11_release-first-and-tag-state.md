---
canonical: docs/summary/2026-11/2026-11-19_0044_release-first-and-tag-state.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T00:51:09+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0044_release-first-and-tag-state
metrics:
  sessions: 1
  casesAdded: 6
  casesTotal: 398
  passRate: "100%"
  lineCoverage: "39.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 首次发布标记与 tag 仅本地原因码

## 概述
task-6f3620f8f8（首次发布这里不对）与 task-bdaf4497fa（打 tag 未生效）：首次发布没有上一版时回滚预检把它当成故障（E-507），用户看到一次失败却不知道该改什么；打 tag 时本地创建成功但推送失败被当成成功，历史里显示版本号、远端却查不到标签。版本号与目标分支不一致时也没有前置拒绝。
新增 ReleaseMode：首次发布是可显式标记的正常分支，回滚预检据此跳过并说明原因；新增 versionBranchConflict，版本线与目标分支不一致时前置拒绝并给出可读原因；新增 TagState 枚举，只有 PUSHED 算远端可见，LOCAL 携带 TAG_PUSH_CREDENTIAL_MISSING 原因码与「补齐凭据后重推标签」建议，REJECTED 表示前置拒绝；引擎 syncGitTag 走枚举并写入 tagReason，运行详情通过 releaseFacts 下发 release{firstRelease,tagState,tagReason,remoteVisible,hint}。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `9930e82` | feat | feat(release): 首次发布显式标记，tag 仅本地返回原因码 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 398, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：6
- 行覆盖率：39.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0044_release-first-and-tag-state/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
