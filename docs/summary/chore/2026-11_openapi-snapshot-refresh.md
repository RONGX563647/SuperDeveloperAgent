---
canonical: docs/summary/2026-11/2026-11-19_2254_openapi-snapshot-refresh.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T22:56:17+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2254_openapi-snapshot-refresh
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 174
  passRate: "100%"
  lineCoverage: "41.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 补装 domain 后重新生成快照

## 概述
PR #27 的 backend job 在 CI 上只有 ApiDocsSnapshotTest 一项红（202 用例里 1 个），报「快照已过期，首个差异在第 8724 行：committed 是 } 而 generated 是 },」。奇怪的是本地重新生成后快照一字未变，看起来像 CI 环境差异。实际根因是：本地 agent-domain 模块没有重新 install 到本地 Maven 仓库，springdoc 在生成快照时加载的是仓库里的旧 jar，读不到 UsageSummary 新增的 cacheHitRate 组件——所以本地生成的 JSON 里这个字段压根不存在（检索出现 0 次），自然「重新生成也没变化」。
先 mvn -pl agent-domain install -Dmaven.test.skip=true 把新 jar 装进本地仓库，再跑 -Dapi.docs.update=true 生成快照；确认 cacheHitRate 在 openapi.json 中出现 1 次后提交。这一步暴露出一个容易被忽略的本地验证盲区：多模块工程里改了上游模块，只跑下游模块的测试会读到旧 jar，症状是「测试报不一致，但重新生成/重跑都没用」。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `60accb8` | chore | chore: 补装 domain 后重新生成 OpenAPI 快照 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 174, Failures: 0, Errors: 0, Skipped: 6
- 新增用例：0
- 行覆盖率：41.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_2254_openapi-snapshot-refresh/manifest.json`
- 上一份同域总结：worktree-commit-hook
