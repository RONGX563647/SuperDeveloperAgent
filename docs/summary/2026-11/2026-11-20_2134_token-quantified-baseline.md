---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T21:42:35+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-20_2134_token-quantified-baseline
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 34
  passRate: "100%"
  lineCoverage: "43.7%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 补齐 token 治理三条量化验收与插件分项归因

## 概述
token 爆炸专项验收 9 项里 6 项功能面通过，但 3 项量化指标（inputTokens 降 40%/命中率 60%/真实请求 token 回归基线）缺实测数据，且缺插件分项归因
新增 TokenBaselineTest 用真实工具面+评测指令锁定按需 schema 下降≥40%（最坏59.1%）防回涨；TokenHitRateBaselineTest 只读真实库固化命中率读数（实测82%）无数据则跳过不自造；打通中间件→RuntimeContext→recorder 分项归因链路（插件名+各插件schema字符数）落库V8迁移+独立日志token.attribution

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e94171d` | feat | feat(token): 补齐量化验收基线与插件分项归因 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：5
- 行覆盖率：43.7%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-20_2134_token-quantified-baseline/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
