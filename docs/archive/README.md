# 历史归档

> 定位：`status=history` 的文档归档区。**只读**，用于追溯决策链与证据，不得作为当前实现或结论引用。
> 规则：进入归档 = 从主阅读路径移出，但保留原文与 Git 历史，不删除；恢复时先在 [docs 索引](../README.md) 登记，再移回对应类型目录。
> 选入标准：结论已被后续版本取代、或属于一次性验收/调研证据，且没有持续的维护价值。

## 归档清单

| 文档 | 原分类 | 归档原因 | 当前替代 / 关联 |
|---|---|---|---|
| [CICD验收报告-v1.md](CICD验收报告-v1.md) | 验收 | 一次性验收证据；主链路已由设计与 Code Wiki 承载 | [流水线设计](../流水线设计-Java与Vue双栈.md)、[Code Wiki](../Code-Wiki.md) |
| [Jenkins能力复刻与复用-规划-v1.md](Jenkins能力复刻与复用-规划-v1.md) | 规划 | 规划已收敛为「5 动作 + 仓库内配方」的现行决策 | [CICD 决策](../CICD方案替代与自创-决策-v1.md) |
| [开源CI集成复用-调研-v1.md](开源CI集成复用-调研-v1.md) | 调研 | 调研结论已并入 CICD 决策；保留作为再评估证据 | [CICD 决策](../CICD方案替代与自创-决策-v1.md) |
| [devops-agent-执行面设计-k8s-jenkins-flyway.md](devops-agent-执行面设计-k8s-jenkins-flyway.md) | 设计 | 三条备选执行面路线，均未实施 | [发布编排](../发布编排与平台部署器-v1.md)、[窄控制器](../窄控制器设计-单机滚动部署器.md) |
| [devops-agent-独立平台-可行性分析-v3.md](devops-agent-独立平台-可行性分析-v3.md) | 决策 | 决策链证据；结论已固化到 ADR | [ADR-0001](../adr/0001-独立平台而非改造若依.md) |
| [ruoyi-vue-plus-agent-可行性分析-v2.md](ruoyi-vue-plus-agent-可行性分析-v2.md) | 背景 | 被 v3 独立平台方案取代 | [ADR-0001](../adr/0001-独立平台而非改造若依.md) |
| [ruoyi-agent-可行性分析与市场调研.md](ruoyi-agent-可行性分析与市场调研.md) | 背景 | 被 v2 / v3 取代；4.8.3 基线仅作对照 | [ADR-0001](../adr/0001-独立平台而非改造若依.md) |
| [ruoyi-ai-编程助手拆解.md](ruoyi-ai-编程助手拆解.md) | 背景 | 竞品拆解，工具面设计参考；不描述本平台现状 | [Code Wiki](../Code-Wiki.md) |
| [架构分析-agentscope-ruoyi-platform-v1.md](架构分析-agentscope-ruoyi-platform-v1.md) | 背景 | 分析基线已演进，模块与类数以 Code Wiki 为准 | [Code Wiki](../Code-Wiki.md) |

## 不在归档区的历史内容

- `docs/_research/`：市场调研与源码能力盘点，按「调研」保留，供后续评估复用；
- `docs/specs/`：功能规格与任务清单，按「规格」保留，已实施项在对应设计中收口；
- [废弃登记](../deprecated/README.md)：已删除文档的永久台账，与归档区职责不同（删除 vs 保留）。
