---
canonical: docs/summary/2026-11/2026-11-17_1113_capability-settings-usability.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T11:25:22+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-17_1113_capability-settings-usability
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 534
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 能力栏与 Skill/MCP 设置可用性改造

## 概述
能力栏与 Skill/MCP 设置不可观测、不可调优：MCP 看不到连接状态与工具清单，Skill 无法验证命中，能力栏缺少搜索筛选排序与 token 体量参考
能力栏增加搜索/筛选/排序、token 估算、场景预设与设为默认；MCP 增加健康状态点、全部测试、真实工具清单与工具级开关（持久化 disabledTools，运行时按原名精确移除）；Skill 增加体积/描述质量检查与示例问题命中试跑

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `0e2041f` | feat | feat(settings): 能力栏与 Skill/MCP 设置增加可观测性与工具级开关 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 534, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_1113_capability-settings-usability/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
