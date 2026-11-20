---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T16:56:01+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-20_1646_image-readiness-observability
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 728
  passRate: "100%"
  lineCoverage: "40.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 镜像就绪状态可观测：只读接口暴露按主机维度的镜像就绪/退避

## 概述
镜像拉取失败会落 image_readiness 并退避，但结论只写进运行日志：运维要翻历史才知道机器上哪几个镜像坏了、什么时候自动再试。
ImageReadinessStore 增加 listAll()（Db 实现按 host 汇总）；新增只读接口 GET /api/v1/settings/sandbox/images 返回状态/来源/digest/次数/最后错误/剩余 retryInSeconds，且不触发任何拉取；同步 OpenAPI 快照与 README 示例。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `6b132e9` | feat | feat(settings): 镜像就绪状态只读接口（按主机维度看卡住的镜像） |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway,agent-infra,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway,agent-infra test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 728, Failures: 0, Errors: 0, Skipped: 6
- 新增用例：3
- 行覆盖率：40.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-20_1646_image-readiness-observability/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
