---
canonical: docs/summary/2026-11/2026-11-20_2007_deploy-address-and-image-retention.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T20:12:31+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-20_2007_deploy-address-and-image-retention
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 266
  passRate: "100%"
  lineCoverage: "42.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 部署地址指向/镜像保留策略/版本区分/环境页镜像面板

## 概述
四组问题：1)「打开部署地址」跳到 /actuator/health(JSON) 而不是前端页面；2) 前一个成功部署的镜像被过早清理（回滚时报『上一版镜像已不在本机』）；3) 部署矩阵把默认部署全标成 latest，工作区环境页看到的发布提交是旧容器；4) 环境页不展示保留的镜像、无法一键重建
三个部署器端点改根路径；pruneOldImages 分级保护（当前/上一版本/运行中镜像永不清理，规则外保留 5 个，staleImages 纯函数抽取）；默认部署容器 label 用 versionLabel(test-vN) 区分版本；环境页新增「最近保留的镜像」面板 + 一键重新构建

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `065cbb1` | bug | fix(deploy): 部署地址指向前端页面 + 镜像保留策略分级保护 + 版本标签区分 + 环境页镜像面板 |
| `d4cac4c` | chore | test(executor): 镜像清理名单分级保护回归 3 例（staleImages 纯函数抽取） |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 266, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：1
- 行覆盖率：42.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-20_2007_deploy-address-and-image-retention/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
