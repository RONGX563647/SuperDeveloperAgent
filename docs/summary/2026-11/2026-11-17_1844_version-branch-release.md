---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T18:47:53+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-17_1844_version-branch-release
metrics:
  sessions: 1
  casesAdded: 10
  casesTotal: 571
  passRate: "100%"
  lineCoverage: "38.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 版本线与分支强绑定与快照发布

## 概述
版本号与分支没有关系：流水线只能选提交、不能选分支，版本号是自由文本且允许带 v；标签不分基础版/补丁版，补丁发布与版本线无关，容易把 0.2.0 的标签打到 0.1 的分支上，也无法阻止补丁跳号或重复；快照构建与正式发布在历史里无法区分；pom.xml 版本需要人工跟随发布版本修改。
新增 domain/release/ReleaseVersion：严格 x.y.z（不带 v）、版本线 x.y 映射长期分支 x.y.0-snapshot、补丁共用同一分支、下一个补丁固定 +1；流水线触发参数新增 branch，正式发布校验「版本线=分支、提交属于该分支、标签不重复、补丁不跳号」，不满足直接拒绝；历史记录新增 branch 并在列表展示；未打 tag 的构建标记 SNAPSHOT（快照版），与正式标签区分；构建时在一次性 worktree 内把根 pom.xml 版本同步为 x.y.z（快照为 x.y.z-SNAPSHOT），跳过 parent 块、不影响用户工作区；新增本地分支列表/创建分支接口，前端触发条增加分支选择并按分支拉取提交，推荐版本按分支版本线递增；test/prod 环境都启用版本控制。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `8b00f39` | feat | feat(release): 版本线与分支强绑定，流水线可选分支并支持快照版 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 571, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：10
- 行覆盖率：38.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-17_1844_version-branch-release/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
