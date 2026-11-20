---
canonical: docs/summary/2026-11/2026-11-17_2011_intake-dockerfile-parity.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T20:36:31+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_2011_intake-dockerfile-parity
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 580
  passRate: "100%"
  lineCoverage: "38.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修复项目健康检查与流水线预检的 Dockerfile 判定冲突

## 概述
项目接入自检说可发布，但流水线预检（ci_dry_run）因 image 阶段缺 Dockerfile 报 E-501；两条链路判定口径不一致，且 targetIsDocker 解析不了 inline flow map。
自检改为与预检同口径：有 image_package/type=image 阶段且缺 Dockerfile 即阻塞；补充 inline map 的 docker 目标识别；Dockerfile 探测覆盖根/backend/frontend/deploy/docker；阻塞项提示调用 ensure_dockerfile；新增本仓库 Dockerfile 与 .dockerignore（方案 A）。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `c80a2f4` | bug | fix(intake): 自检与流水线预检统一镜像阶段判定 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test -Dtest=!SqliteGitIssueStoreCodeTest -Dsurefire.failIfNoSpecifiedTests=false`
- 结果：PASSED；Tests run: 580, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：3
- 行覆盖率：38.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_2011_intake-dockerfile-parity/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
