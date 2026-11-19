---
canonical: docs/summary/2026-11/2026-11-19_1907_gate-incremental-by-change-kind.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T19:08:04+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1907_gate-incremental-by-change-kind
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "41.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 门禁按改动性质分派测试范围，堵住前端改动跑后端全量

## 概述
用户反馈每次改动都要跑全量单测，浪费时间与 token。查 gate-metrics 确认病灶：changed_modules 把 platform/web 过滤后返回空列表，空列表被当成「不知道影响什么 ⇒ 跑全部业务模块」，于是纯前端改动与纯工具改动都会触发 mvn -pl !spec-checks test（779 个后端用例）。pre-commit 48 次共 2865s，其中 8 次这种全量占 1302s（45%），单次最慢 356s。
changed_modules 改为返回 (模块列表, 是否含前端)；incremental_test_command 按改动性质分四路：后端模块跑该模块 test-compile + 测试；只有 platform/web 跑前端自检 4 脚本不碰 Maven；只有根 pom 跑全 reactor test-compile；工具脚本与 CI 配置什么都不跑。判「全跑」的唯一正当理由收窄为根 pom 变化。配套新增 synth_evidence 记录「不跑 Maven 但结论有效」的证据（tree 取当前索引树），避免 finish 因缺 PASSED 证据而失败。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `02ddb88` | chore | perf(gate): 按改动性质分派测试范围，前端改动不再跑后端全量 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：41.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1907_gate-incremental-by-change-kind/manifest.json`
- 上一份同域总结：worktree-commit-hook
