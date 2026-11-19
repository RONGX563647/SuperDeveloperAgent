---
canonical: docs/summary/2026-11/2026-11-19_1343_bug-17-change-status-readonly-tool.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T13:44:17+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_1343_bug-17-change-status-readonly-tool
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 138
  passRate: "100%"
  lineCoverage: "40.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-17 新增只读变更状态查询工具

## 概述
BUG-17（变更单无状态字段）现象仍成立但根因已过半：ChangePlan 早就有 status 枚举，ChangeController 的 /confirm 与 /apply 也确实在写 CONFIRMED / APPLIED / FAILED，confirm 工具也已改为回报真实状态而不是硬编码 PENDING。真正剩下的缺口是「没有只读入口」：模型想查「人确认了没、执行了没」只能调 confirm，而 confirm 会推送确认卡片——于是出现「人已经确认过、卡片又被推一遍」，长流程里还容易发完卡就收工。
新增只读工具 change_status(change_id)：readOnly + concurrencySafe，不推卡、不签发令牌、不执行动作，纯粹读 ChangePlan.status。按状态给出可执行的 next（PENDING 等操作人、CONFIRMED 可 apply、APPLIED 直接汇报、FAILED 先说明原因再重试、留痕单 RECORDED 无需确认直接继续），并回显 planHash / kind / env / highRisk / executable，让模型不必自己推断语义。越权校验与 confirm 完全一致：跨项目或跨环境一律拒绝，且拒绝时不回显状态。工具挂到 change 插件；补充 4 个用例：四种状态映射、零副作用（假 store 的写方法一被调用即失败并计数）、越权与不存在、留痕单语义。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `ab9280a` | docs | docs(process): 归档迁移守卫 tag 锚点会话；落盘上一会话遗留归档 |
| `f929bfe` | feat | feat(change): 新增只读 change_status 工具 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-tools,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-tools test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 138, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：4
- 行覆盖率：40.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_1343_bug-17-change-status-readonly-tool/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
