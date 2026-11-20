---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T20:57:09+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-20_2046_prod-deploy-version-picker
metrics:
  sessions: 1
  casesAdded: 9
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "36.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 生产发布改为从测试验证版本直接部署

## 概述
生产发布需按 git 版本号选择、且只显示"发布到生产"，无法从已验证版本直接部署并查看镜像/git tag
生产表单改为单一「直接部署」：从测试镜像账本+构建历史组装候选，展示镜像 tag 与 git tag；触发复用 deployImage+server 目标，不重建不重打 tag；新增 releaseCandidates 纯函数与单测

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `a27fe76` | feat | feat(pipeline): 生产发布改为从测试验证版本直接部署 |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：9
- 行覆盖率：36.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-20_2046_prod-deploy-version-picker/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
