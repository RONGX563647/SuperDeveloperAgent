---
canonical: docs/summary/2026-11/2026-11-18_2254_db-hot-reload.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T23:25:26+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_2254_db-hot-reload
metrics:
  sessions: 1
  casesAdded: 5
  casesTotal: 685
  passRate: "100%"
  lineCoverage: "39.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 数据库后端热重载并移除旧库导入

## 概述
数据库后端切换只能改配置重启，运行中无法换库；同时旧库导入（8 个历史分域 .db）这条需求已取消，但代码仍散落在启动装配、REST 接口、前端卡片与配置项里。
PlatformDb 内部改为 volatile Holder（DataSource+EMF）承载真实后端，read/inTx 每次取当前后端，25 个 Store 注入的引用不变，换库=原子替换引用；新增 create()/swapTo()/isPostgres()。修正 swapTo 归属规则：返回独立包装的旧资源而非 this（否则调用方 close 会关掉刚切上去的新库），首次返回 null 归 Spring。新增 DatabaseHotSwapper 编排「探活→建 schema→搬数据→落配置→原子替换」，任一步失败则运行中后端与配置都不动；新增 POST /switch、DELETE 改为热切回 SQLite。整条删除旧库导入：LegacySqliteImporter 及测试、DataSourceConfig Bean、AgentDbProperties.importLegacy、GET /legacy 与 POST /legacy/import、前端卡片与 API、application.yml 配置项。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e9a6461` | feat | feat(gate): CI 安全扫描、红队自审与耗时预算校准 |
| `6b1d82b` | docs | docs(process): 归档门禁安全扫描与红队自审会话 |
| `06e1811` | bug | fix(db): 搬行后拨正 PG identity 序列，修 token_usage_pkey 主键冲突 |
| `612e675` | chore | test(spec-checks): 补录 V4 identity 序列迁移基线 |
| `3a12b86` | feat | feat(db): 数据库后端支持热切换，删除旧库导入 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl !spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 685, Failures: 0, Errors: 0, Skipped: 3
- 新增用例：5
- 行覆盖率：39.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_2254_db-hot-reload/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
