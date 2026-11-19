---
canonical: docs/summary/2026-11/2026-11-19_1340_migration-guard-tag-anchor.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T13:43:17+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1340_migration-guard-tag-anchor
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 31
  passRate: "100%"
  lineCoverage: "40.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 迁移守卫接入发布 tag 锚点，并补门禁设计文档

## 概述
上一版 M1 只有『未登记即红』：还没发布的迁移也被提前冻结（同一 PR 里微调刚写的迁移就得开新版本号），而且门禁没有任何文档沉淀——handbook 只有行为类 L2，事故教训散在会话归档里，下一个人被拦下时只能猜『这道门禁是不是太苛刻』。
1) 迁移守卫接入发布 tag 锚点：releaseTag() 用 git describe --tags --abbrev=0 --match x.y.z，releasedMigrations() 用 git ls-tree 读 tag 树；判定分成四类（已发布已登记一致=过、已发布被改=红、已发布未登记=红、未发布=过并打印清单），解析不到 tag 时按安全的『全部已发布』降级。2) 处方扩到三条路径并写明锚点口径；ci.yml 的 backend checkout 显式 fetch-depth: 0 + fetch-tags: true。3) 新增 docs/quality-gate-design-v1.md（四层结构、六条原则、M1 专题、三件套、缺口、三次事故复盘）与 docs/handbook/l2/quality-gates.md（按症状找修法 + 重算/发布时间线），index 与 manifest 同步。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `0a0cd5a` | docs | docs(process): 归档热切换失败路径测试会话 |
| `3deae53` | chore | test(spec-checks): 迁移守卫接入发布 tag 锚点，未发布迁移不再被冻结 |
| `d85eb2b` | docs | docs: 补门禁设计文档与 handbook 质量门禁篇 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：2
- 行覆盖率：40.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1340_migration-guard-tag-anchor/manifest.json`
- 上一份同域总结：worktree-commit-hook
