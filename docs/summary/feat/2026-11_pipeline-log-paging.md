---
canonical: docs/summary/2026-11/2026-11-19_0033_pipeline-log-paging.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T00:42:30+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0033_pipeline-log-paging
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 392
  passRate: "100%"
  lineCoverage: "39.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 流水线日志支持行分页并返回 total/truncated

## 概述
task-699cc7692d（流水线右侧无滚动条、日志不能下滚）：日志接口只按字节 offset 返回 text，前端拿不到「一共多少行」，既算不出滚动高度也无法做虚拟滚动；几十万行的构建日志只能整包塞进 DOM。
PipelineEngine 新增 logLines(runId, offset, limit) 与 LogLinePage（offset/limit/total/lines/truncated，MAX_LINES=5000 上限）；行分页口径明确：行尾换行不多算一行、offset 越界夹紧到 total、truncated 如实反映还有更多行；BuiltInPipelineEngine 先切行再脱敏，保证行号语义稳定；新增 GET /ci/runs/{runId}/log/lines（含 complete）；前端补 fetchRunLogLines 与 RunLogPage 类型。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `bfdb821` | feat | feat(pipeline): 日志支持行分页并返回 total/truncated |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-executor,agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 392, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：5
- 行覆盖率：39.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0033_pipeline-log-paging/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
