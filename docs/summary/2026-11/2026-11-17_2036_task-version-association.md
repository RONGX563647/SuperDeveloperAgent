---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T20:44:25+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_2036_task-version-association
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 582
  passRate: "100%"
  lineCoverage: "38.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 任务关联版本号并在看板展示

## 概述
任务看板的任务没有关联版本号，无法区分任务属于当前版本还是计划到下个版本
任务新增 version 字段并持久化：创建时默认取当前分支版本线的下一个补丁版本，编辑可改为目标版本；历史任务读取时按默认版本展示；列表/详情/看板展示版本徽标；同步时写入 version: 标签，远端标签也能回填版本；Agent 工具支持指定版本

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `b9d8063` | bug | fix(task): 任务关联版本号并按当前分支默认，看板与 Git 标签同步 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 582, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：3
- 行覆盖率：38.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_2036_task-version-association/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
