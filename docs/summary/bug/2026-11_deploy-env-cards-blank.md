---
canonical: docs/summary/2026-11/2026-11-20_2042_deploy-env-cards-blank.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T20:42:39+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_2042_deploy-env-cards-blank
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "42.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修复环境卡片区因 v-else 错配而空白

## 概述
813904ba 新增镜像 section 时把原属环境网格的 v-else 留给了它，v-else 与 retainedImages.length 的 v-if 配对，有保留镜像时环境卡片网格永不渲染
环境网格改独立 v-if=hasRows，镜像面板保留独立 v-if，二者互不排斥；镜像区移回网格之后保持视觉顺序

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `bb27e58` | bug | fix(deploy): 修复环境卡片区因 v-else 错配而空白 |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：4
- 行覆盖率：42.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_2042_deploy-env-cards-blank/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
