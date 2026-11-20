---
canonical: docs/summary/2026-11/2026-11-17_2054_tool-result-approval-card.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T21:00:20+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_2054_tool-result-approval-card
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 582
  passRate: "100%"
  lineCoverage: "38.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 工具结果 JSON 序列化还原，修复确认卡不出现

## 概述
AgentScope 会把工具返回的 String 再 JSON 序列化一次，换行变成字面量 \\n，导致 ChangeWire/EnvBindingWire 的整行匹配失败，对话里变更单/环境绑定确认卡不出现；只读模式下审批卡也不应自动展开
新增 ToolResultText 还原 JSON 字符串字面量（含转义解码），两处 wire 解析先还原再匹配；ChatView 在非只读模式（confirm/auto）出现待确认单时展开审批卡；补充 ChangeWire 与 EnvBindingWire 的序列化回归测试

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `5699a53` | bug | fix(chat): 还原工具结果的 JSON 字符串，修复确认卡不出现 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 582, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：4
- 行覆盖率：38.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_2054_tool-result-approval-card/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
