---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T17:54:52+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_1747_bug-10-release-facts-in-ui
metrics:
  sessions: 1
  casesAdded: 17
  casesTotal: 779
  passRate: "100%"
  lineCoverage: "40.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-10 界面展示发布事实：首次发布与标签是否生效

## 概述
BUG-10/BUG-11/BUG-13 的后端部分此前已完成（tag 状态枚举、首次发布标记、日志行分页），但界面一处都没用：release{firstRelease,tagState,tagReason,hint} 下发到前端却没人渲染，用户只能到远端自己发现「标签没生效」；首次发布跳过回滚被当成故障；日志只显示「N 行」而不告诉服务端一共多少行，分不清「还没来」与「到底了」。
PipelineView 头部补两个徽标：首次发布（tooltip 说明这不是错误）、标签状态（PUSHED 正常，LOCAL/FAILED/REJECTED 用警示色并带 hint）；标签状态映射抽到 utils/releaseTag.ts 供视图与回归脚本共用，新增 14 个断言并接入前端自检。RunLog 借用行分页接口的 total，在头部显示「已载入 M 行 / 共 N 行」，不改变原有增量加载策略（跟随/去重/终态补齐那套逻辑不动）。同时纠正一处误判：我一度把 .run-log 的 min-height 压到 120px 去'修'滚动，被既有回归用例 pipelineLayout.test.ts 拦下（要求 ≥240px），已回退——那条约束是对的，压掉会重现「日志被挤成一条缝」。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c7e483a` | feat | feat: 生成项目 Code Wiki 文档 |
| `232abdb` | bug | fix(docs): 修正 bug-24 归档指向的游离提交，去掉 subject 的 BOM |
| `ce48355` | docs | docs(process): 归档游离提交修正会话 |
| `fce29f9` | bug | fix(deploy): HOST 产物复制对短暂占用重试，失败时给出可操作原因 |
| `a03abed` | docs | docs(process): 归档 HOST 部署复制重试会话 |
| `5789f3a` | chore | chore(task-board): 关闭已修项并补齐只读排查脚本 |
| `12c6e66` | docs | docs(process): 归档看板收口与排查脚本会话 |
| `ea95dc0` | feat | feat(pipeline): 界面展示发布事实与日志总行数 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl !spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 779, Failures: 0, Errors: 0, Skipped: 7
- 新增用例：17
- 行覆盖率：40.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_1747_bug-10-release-facts-in-ui/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
