---
canonical: docs/summary/2026-11/2026-11-17_0900_pipeline-tag-and-history.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T09:44:57+08:00
extends: pipeline-tag-and-history
refs:
  - bug/2026-11-17_0900_pipeline-tag-and-history
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 523
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 流水线 tag 同步与构建历史编号修复

## 概述
打包镜像模式不产生 git tag，流水线版本输入只能手填且无候选/冲突提示，构建历史序号在重跑后错乱
打包镜像时同步创建 git tag；版本输入改为从 git tag 取候选并给出建议与冲突提示；构建历史序号归一化，UI 展示 tag 同步结果（远端凭据不足时标注 tag 仅本地）

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `bd3680c` | bug | fix(pipeline): 打包镜像时同步 git tag，构建历史序号归一化 |
| `e99fc8f` | feat | feat(pipeline): 版本号候选取自 git tag，历史展示 tag 同步结果 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 523, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 远端凭据不足时 tag 只能本地创建，UI 显示 tag 仅本地

## 链接
- 归档：`docs/process/bug/2026-11-17_0900_pipeline-tag-and-history/manifest.json`
- 上一份同域总结：pipeline-tag-and-history
