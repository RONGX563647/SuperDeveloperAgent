---
canonical: docs/summary/2026-11/2026-11-19_1354_bug-4-tool-args-unavailable-ui.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T13:57:50+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1354_bug-4-tool-args-unavailable-ui
metrics:
  sessions: 1
  casesAdded: 20
  casesTotal: 770
  passRate: "100%"
  lineCoverage: "40.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-4 工具入参不可用在界面上与无参数区分开

## 概述
BUG-4（轨迹工具详情参数缺失）的界面一半：后端在批次 9 已把 argsUnavailable 下发下来，但两处界面都把它渲染成「无参数」（详情卡写死「无参数」，摘要行留空）。后果是三者不可区分：工具本来无参、入参还在流式路上、后端明确拿不到。排查的人因此无法判断是映射漏了还是工具真的没参数——这也是参数「缺失」长期没人发现的界面原因。
抽出纯函数 utils/toolArgs.ts 统一表达口径，四种状态分开：present（有参原样）/ pending（运行中且暂无，显示等待入参…）/ unavailable（后端明确拿不到，用告警色并写明「这不等于该工具无参数」）/ none（确实无参）。ToolCallCard 详情与 MessageItem 摘要都改为走这个函数；空对象 {} 与空数组 [] 仍视作没参数，但若同时带 unavailable 标记则优先报不可用。新增 20 个断言的 toolArgs.test.ts 并接进前端自检脚本；同时修掉归档门禁的用例计数规则（原来只认 it(/test(，不认本仓库的 check(，导致确实写了断言的提交被判成没有新增用例）。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `45a0832` | bug | fix(chat): 工具入参不可用在界面上与无参数区分开 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl !spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 770, Failures: 0, Errors: 0, Skipped: 3
- 新增用例：20
- 行覆盖率：40.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1354_bug-4-tool-args-unavailable-ui/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
