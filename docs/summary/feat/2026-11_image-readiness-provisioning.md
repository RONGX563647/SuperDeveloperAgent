---
canonical: docs/summary/2026-11/2026-11-20_1620_image-readiness-provisioning.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T16:42:43+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-20_1620_image-readiness-provisioning
metrics:
  sessions: 1
  casesAdded: 17
  casesTotal: 725
  passRate: "100%"
  lineCoverage: "40.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 镜像就绪自动收敛：进度感知拉取+退避重试+缓存源+运行前就绪

## 概述
run-66c5c523 的 frontend 阶段以 E-302 失败：node:22-bookworm 八层全部 Download complete 后被写死的 180s 总预算在解压阶段砍断。镜像就绪判断只看总时长、失败不复用结论、每次构建都重等一轮，环境问题必须人工逐个解决。
把镜像就绪收敛成一套策略：ProcessExecutor.execUntilIdle 按有无新输出判卡死；ImageProvisioner 统一 探测→源链拉取→inspect 复核→记状态与指数退避；image_readiness 表按 (host,image) 持久化 READY/FAILED 与 digest；pipeline 开跑前并行点亮所有 stage 镜像，失败记在首阶段名下并由自动修复续跑；错误码按类型还原（缺镜像 E-705）；新增 tools/registry-cache pull-through cache 配方作为网络慢的根治手段。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `ea8de6e` | bug | fix(gateway): OpenAPI 快照生成统一 LF 换行 |
| `370cb72` | feat | feat(executor): 镜像就绪自动收敛（进度感知 + 退避重试 + 缓存源 + 运行前就绪） |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway,agent-infra,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway,agent-infra test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 725, Failures: 0, Errors: 0, Skipped: 6
- 新增用例：17
- 行覆盖率：40.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-20_1620_image-readiness-provisioning/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
