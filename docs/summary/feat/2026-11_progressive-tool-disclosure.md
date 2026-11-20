---
canonical: docs/summary/2026-11/2026-11-17_0026_progressive-tool-disclosure.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T00:39:02+08:00
extends: session-archive-gate
refs:
  - feat/2026-11-17_0026_progressive-tool-disclosure
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 516
  passRate: "100%"
  lineCoverage: "37.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 工具渐进披露与任务面板改版

## 概述
工具按能力拆成插件按需加载，技能与 MCP 只保留索引/分组；提示词与工具检索输出压缩，裁剪加迟滞以提升 KV 缓存命中。
工作区任务页删除统计卡、筛选收成一行，并支持任务描述与评论插入图片。详见 canonical 文档。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `4b0a8c4` | feat | feat(tools): 工具按插件渐进披露以压缩上下文 |
| `ef7eaa8` | feat | feat(issue): 任务支持上传并读取配图 |
| `2b7dc5f` | feat | feat(ui): 任务面板去掉概览并把筛选收成一行 |
| `b3fbfe7` | chore | test(issue): 覆盖任务配图读取的路径与类型校验 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 516, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：37.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_0026_progressive-tool-disclosure/manifest.json`
- 上一份同域总结：session-archive-gate
