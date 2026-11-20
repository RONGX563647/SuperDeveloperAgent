---
canonical: docs/summary/2026-11/2026-11-19_1154_bug-4-task-board-rebalance.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T11:58:24+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1154_bug-4-task-board-rebalance
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 748
  passRate: "100%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-4 任务看板优先级与分类重排

## 概述
用户的结构性判断没有被执行：看板 32 条任务的优先级按「现象刺眼程度」而不是影响面标注——版本号太丑（纯观感）标 P0，而已修目录里的轨迹参数缺失（影响所有调试判断依据）标 P2；BUG-20（super agent 搭建）是 FEAT 却标成 BUG，FEAT-6（部署失败）是 BUG 却标成 FEAT，FEAT-8（字段写读不回环）同样是 BUG；库里还存在两条共用 BUG-1 的重复编码，且用户报告里两个 id（对话流中断、历史环境卡片）在库中完全不存在，复核时对不上号。
按影响面重排 32 条任务：P0 收缩到能一票否决的七条（写不进 src/main、sqlite 丢数据、轨迹参数缺失、发送按钮挂住、对话流中断、会话丢失、部署链路不可用），轨迹与发送按钮由 P2 升 P0，版本号与沙箱由 P0 降 P1；三处分类纠正（BUG-20→FEAT-1、FEAT-6→BUG-1、FEAT-8→BUG-8）；BUG/FEAT 各自连续重编号消掉重复 BUG-1（BUG-1..25 / FEAT-1..7），并校验前缀与分类一致、编码无空洞；补建报告里缺失的两条记录。落地方式与可复核性：改库前备份 platform.db，改后由 tools/task-board/verify.py（编码唯一/前缀一致/连续性，退出码 0）与 dump.py 逐条复核，口径与回滚命令写进 docs/summary/chore/2026-11_task-board-rebalance.md。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `8141447` | chore | chore(task): 按影响面重排任务看板优先级与分类 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl !spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 748, Failures: 0, Errors: 0, Skipped: 3
- 新增用例：0
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1154_bug-4-task-board-rebalance/manifest.json`
- 上一份同域总结：worktree-commit-hook
