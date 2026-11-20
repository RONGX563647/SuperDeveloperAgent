---
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
工具不再每轮全量注入：按能力拆成 repo / ci / deploy / env / issue / action / change / capability 八个插件，
默认只暴露核心工具与未归类工具（MCP、技能工具），其余由 `tool_load` 激活并写进会话状态，重启后仍有效；
首轮按用户输入关键词自动预加载相关插件。技能改为提示词只放名称与描述，正文由 `skill_load` 读取；
MCP 工具按服务收敛成 `mcp:<服务名>` 插件。系统提示与 `tool_search` 输出同步压缩，工具顺序保持稳定，
上下文裁剪改为留 25% 余量才触发、一次裁到 60%，减少前缀缓存失效。
工作区任务页删除四张统计卡（计数并入页头），状态/类型/排序合并成单行工具栏并支持排序；
任务描述与评论支持按钮、粘贴、拖拽插入图片，Markdown 渲染只允许站内 / http(s) / base64 图片并限制宽度。

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
- 模型若既不自动命中插件、也不主动 `tool_load`，会看不到对应工具；系统提示已明确加载规则，但首次使用仍需观察。
- 插件名与工具名是硬编码映射（`ToolPlugins`），新增工具若忘记归组会默认常驻（安全但省不到 token）。
- 配图 URL 是平台本地地址，任务同步到远端 Git 后图片不会显示。

## 遗留与建议
- MCP 工具参数要等插件加载后才进入工具 schema，`tool_get` 暂时只覆盖静态目录。
- `tool_search` 的中文摘要已截断到首句，若后续需要更精确的检索可按插件标签排序。

## 链接
- 归档：`docs/process/feat/2026-11-17_0026_progressive-tool-disclosure/manifest.json`
- 上一份同域总结：session-archive-gate
