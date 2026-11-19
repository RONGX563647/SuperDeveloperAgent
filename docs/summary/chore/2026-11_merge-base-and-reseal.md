---
canonical: docs/summary/2026-11/2026-11-19_2311_merge-base-and-reseal.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T23:17:13+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_2311_merge-base-and-reseal
metrics:
  sessions: 1
  casesAdded: 8
  casesTotal: 265
  passRate: "100%"
  lineCoverage: "33.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 合并基线并重排归档链

## 概述
PR #28 与基线冲突（dirty）：并行会话在主线实现了等价的 405 诊断（BuildFreshnessCheck 运行期提示 + GlobalExceptionHandler.methodNotSupported），与我的改动正面相撞三处；合并后归档链也断了两条。
代码冲突取主线版本（功能等价，避免重复实现），删掉我这边重复的同名测试用例；对 feat/chore 两条归档链执行 reseal 收敛。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `dbeb728` | feat | feat(usage): 用量记录提供缓存命中率，汇总带组件 |
| `fe0b85f` | chore | chore: 重新生成前端类型并加 CI 失败日志抓取脚本 |
| `2a4591d` | docs | docs(process): 归档用量命中率会话并同步 OpenAPI 快照 |
| `cfaf487` | docs | docs(process): 归档用量命中率与门禁隔离会话 |
| `ac40c70` | docs | docs(process): 归档纯文档收口会话 |
| `a054113` | bug | fix(process): 归档改记 cherry-pick 后的新 sha，修复 CI 归档校验 |
| `9b96b52` | docs | docs(process): 归档 cherry-pick sha 修正会话 |
| `60accb8` | chore | chore: 补装 domain 后重新生成 OpenAPI 快照 |
| `d2ce0ba` | docs | docs(process): 归档快照重生成会话 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 265, Failures: 0, Errors: 0, Skipped: 6
- 新增用例：8
- 行覆盖率：33.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_2311_merge-base-and-reseal/manifest.json`
- 上一份同域总结：worktree-commit-hook
