---
canonical: docs/summary/2026-11/2026-11-18_1133_archive-chain-race.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T11:34:15+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-18_1133_archive-chain-race
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "39.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 归档落盘互斥与链重排

## 概述
并行会话各自 finish 时读到同一旧链尾，合并后出现多处 chain.prev 断链，pre-push 直接拦下推送；此前只能手工重算
归档落盘走公共 .git 下的 archive.lock 互斥（复用 TestLock，支持自定义标签），并在同一临界区内按 createdAt 重排整条链、重算 self（新增 reseal_chain）；self-test 增加并联接链→重排恢复的用例

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `115e5c5` | chore | chore(session-archive): 归档落盘加跨 worktree 互斥并按 createdAt 重排链 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：39.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-18_1133_archive-chain-race/manifest.json`
- 上一份同域总结：worktree-commit-hook
