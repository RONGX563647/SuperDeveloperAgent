---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T22:02:23+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_2128_legacy-import-to-settings
metrics:
  sessions: 1
  casesAdded: 13
  casesTotal: 258
  passRate: "100%"
  lineCoverage: "38.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 旧库导入从启动链路移到设置页显式操作

## 概述
启动日志每次都在打「旧库一次性导入完成 ... 新增 N 行」：DataSourceConfig 用 @Bean(initMethod=run) 把 LegacySqliteImporter 挂在启动装配上，启动期静默改数据，用户没点过、无法选目标库、看不到逐表明细。
LegacySqliteImporter 去掉自动执行的 run()，拆成 inspect() 只读巡检（文件/大小/是否已导入/上次导入行数）与 importAll(force) 显式导入（逐文件结构化结果、失败原因回传）；DataSourceConfig 移除 initMethod，Bean 只装配不执行，启动链路仅保留不改数据的 SqliteWalCheckpointer；新增 GET /settings/database/legacy 与 POST /legacy/import 两个接口（settings:read / settings:write，受 agent.db.import-legacy 约束）；前端新增「旧库导入」卡片（巡检表 + 开始导入 + 强制重放）。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `b490f43` | feat | feat(db): 旧库导入移出启动链路，改为设置页显式触发 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,agent-infra,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway,agent-infra test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 258, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：13
- 行覆盖率：38.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_2128_legacy-import-to-settings/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
