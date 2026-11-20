---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T08:44:10+08:00
extends: env-version-label
refs:
  - bug/2026-11-17_0842_docker-deploy-image-chain
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 520
  passRate: "100%"
  lineCoverage: "37.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修复 docker 部署被降级 host / 回滚镜像缺失

## 概述
docker 目标被静默降级为 host（固化脚本缺 image_package 却优先回放），回滚时 docker run 缺镜像只报 exit 125 且先删了在跑的容器
固化脚本按部署目标自洽校验，过期脚本退回仓库/平台定义；reconcileTarget 不再把 docker 改成 host；DockerDeployer 起容器前 docker image inspect，缺镜像直接 E-708

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `2b962bf` | bug | fix(deploy): docker 目标不再静默降级 host，过期固化脚本不再优先回放 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 520, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：37.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_0842_docker-deploy-image-chain/manifest.json`
- 上一份同域总结：env-version-label
