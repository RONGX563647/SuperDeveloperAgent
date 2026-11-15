---
type: record
summary: "已删除文档的留痕登记"
---
# 废弃登记

> 规则：删除正式文档前，先在本表登记（文件、原因、替代物、日期）。文件从仓库移除后，本表**永久保留**，避免「文档去哪了」不可追溯。
> 当前策略：历史/废弃文档**直接删除**，不再保留 `archive/` 副本；正文可从 Git 历史恢复，替代物只链接仍存在的文档。

## 2026-11-15 删除列表（历史文档，9 篇）

| 文件 | 类型 | 删除原因 | 替代物 / 现状 |
|---|---|---|---|
| `docs/CICD验收报告-v1.md` | 验收报告 | 一次性验收证据，结论已并入决策 | [CICD 决策](../cicd-decision-v1.md) |
| `docs/Jenkins能力复刻与复用-规划-v1.md` | 规划 | 复刻范围已落到现行流水线设计 | [流水线设计](../pipeline-design-java-vue.md) |
| `docs/开源CI集成复用-调研-v1.md` | 调研 | 结论已固化，不再维护 | [CICD 决策](../cicd-decision-v1.md) |
| `docs/devops-agent-执行面设计-k8s-jenkins-flyway.md` | 备选设计 | 三条执行面路线均未采用 | [发布编排与平台部署器](../release-orchestration-deployer.md) |
| `docs/devops-agent-独立平台-可行性分析-v3.md` | 可行性分析 | 被 ADR-0001 与多项目方案取代 | [ADR-0001](../adr/0001-independent-platform.md)、[多项目托管 v4](../multi-project-hosting-v4.md) |
| `docs/ruoyi-vue-plus-agent-可行性分析-v2.md` | 可行性分析 | 被 v3 与多项目方案取代 | [多项目托管 v4](../multi-project-hosting-v4.md) |
| `docs/ruoyi-agent-可行性分析与市场调研.md` | 调研 | 被 v2/v3 与多项目方案取代 | [多项目托管 v4](../multi-project-hosting-v4.md) |
| `docs/ruoyi-ai-编程助手拆解.md` | 竞品分析 | 工具面设计已落地并归档到 Code Wiki | [Code Wiki](../code-wiki.md) |
| `docs/架构分析-agentscope-ruoyi-platform-v1.md` | 架构分析 | 被持续维护的 Code Wiki 取代 | [Code Wiki](../code-wiki.md) |

## 2026-11-14 删除列表

| 文件 | 类型 | 删除原因 | 替代物 / 现状 |
|---|---|---|---|
| `docs/项目接入设计-v1.md` | 设计（旧版） | 正文自述被 v2 取代，字段与流程均已过时 | [项目接入设计 v2](../project-intake-v2.md) |
| `docs/环境变量与环境模板设计-v1.md` | 决策（旧版） | 被 v2 取代（v1 仅作存档，不再有引用价值） | [环境模板 v2](../env-template-design-v2.md) |
| `docs/方案D修复方案-v1.md` | 一次性实施方案 | 代码已落地并通过验收，方案中的行号与旧结构已失效 | [CICD 决策](../cicd-decision-v1.md)、[Code Wiki](../code-wiki.md) |
| `docs/流水线自动部署-环境适配修复-v1.md` | 一次性修复记录 | 修复已合入 executor，记录中的临时绕行会误导读者 | [发布编排](../release-orchestration-deployer.md) |
| `docs/脚手架完善分析-v1.md` | 一次性差距分析 | 所列项已完成，保留会让人误以为仍未做 | [Code Wiki](../code-wiki.md)、[项目规约](../project-conventions-v1.md) |
| `docs/build_deck.py` | 一次性脚本 | 生成汇报稿的临时脚本，不属产品代码 | 汇报稿 `ruoyi-vue-plus-agent-汇报-v2.pptx` |
| `docs/build_deck_v2.py` | 一次性脚本 | 同上 | 同上 |
| `docs/ruoyi-agent-可行性分析-汇报.pptx` | 汇报稿（旧版） | 已被 v2 汇报稿取代，结论与 v3 独立平台方向冲突 | `ruoyi-vue-plus-agent-汇报-v2.pptx` |

## 保留说明（未删除）

| 文件 | 保留理由 |
|---|---|
| `narrow-controller-rolling-deployer.md` | **未实施**的备选设计，状态标「规划」，不是过时 |

## 流程

1. 在本文登记（文件 / 原因 / 替代物 / 日期）；
2. 从 [docs/overview.md](../overview.md) 登记表移除该行，并更新所有引用；
3. `uvx --from doc-marshal==0.6.1 doc-marshal check --all` 必须为 0 error；
4. 同一 PR 内完成删除与索引更新，避免中间态。
