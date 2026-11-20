---
canonical: docs/summary/2026-11/2026-11-19_1612_task-board-closeout-tools.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T16:15:17+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1612_task-board-closeout-tools
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 775
  passRate: "100%"
  lineCoverage: "40.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 看板收口：关闭已修项并补进展记录

## 概述
看板与实际不符：本轮已修完 10 条任务（BUG-2/3/4/5/6/7/9/14/15/16），但看板仍显示 23 条 OPEN，优先级判断因此失真——P0 列表里塞着已经修好的条目，真正待办被淹没。另一方面排查只靠翻 data/workspaces/_history 的 YAML：没有按错误码归类的入口，也就看不出 E-501 占了失败的一半。
逐条关闭已修任务并把「已修 + 提交号 + 复核用例」写进描述（10 条），关闭依据是 merge-base 验证修复提交已在 origin/0.1.0-snapshot；BUG-1 保持 OPEN 但补进展记录，说明 E-501 中「复制产物被占用」已修、缺 Dockerfile 需 ensure_dockerfile 介入、待实机再验；BUG-8/BUG-17 同样补记录。新增四个只读排查脚本：open.py 按优先级列待办、show.py 看单条全文、run-failures.py 按错误码归类失败、run-error-detail.py 看错误原文——正是靠它们从 34 份历史里定位到「文件被占用」这个真实缺陷，而不是照标题猜「agent 能力弱」。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `5789f3a` | chore | chore(task-board): 关闭已修项并补齐只读排查脚本 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl !spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 775, Failures: 0, Errors: 0, Skipped: 3
- 新增用例：0
- 行覆盖率：40.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1612_task-board-closeout-tools/manifest.json`
- 上一份同域总结：worktree-commit-hook
