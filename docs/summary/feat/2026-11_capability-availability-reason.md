---
canonical: docs/summary/2026-11/2026-11-19_0108_capability-availability-reason.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T01:13:01+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0108_capability-availability-reason
metrics:
  sessions: 1
  casesAdded: 6
  casesTotal: 217
  passRate: "100%"
  lineCoverage: "39.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 能力列表返回可用状态与不可用原因

## 概述
task-46122095b6（会话能力 skill/mcp 不实用要大改）：能力接口只返回名字与 installed，用户装完在会话里发现用不上，却无法判断是没开启、没装好、缺环境变量还是平台没挂上；失败原因完全没有出口。skill 正文注入的问题已在渐进披露里解决（提示词只放 name + 一句话，正文由 skill_load 按需读取）。
新增 CapabilityState 把四件事分开表达：enabled（用户是否开启）/ installed（是否已落地）/ available（当前是否真的可用）/ reasonCode+reason（不可用的稳定原因码与可读原因，缺变量时列出键名）；原因码固定为 CAPABILITY_DISABLED / CAPABILITY_NOT_INSTALLED / CAPABILITY_MISSING_ENV / CAPABILITY_TRANSPORT_UNAVAILABLE；市场视图补齐 enabled/available/reasonCode/reason/missingEnv 五个字段，前端类型同步。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `1614c1e` | feat | feat(capability): 能力列表返回可用状态与不可用原因 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 217, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：6
- 行覆盖率：39.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0108_capability-availability-reason/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
