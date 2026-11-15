---
type: reference
summary: "文档树总览、阅读入口与 doc-marshal 治理规则"
source:
  - docs/code-wiki.md
---
# docs/ 文档总览

> 定位：**文档即代码**。代码仓库是唯一可信源，外部 Wiki / 知识库只是检索视图。
> 治理方式：`docs/` 是 [doc-marshal](https://github.com/rootdrew27/doc-marshal) 文档树；每篇笔记必须有 `type` / `summary`，类型决定结构契约与代码锚点。
> 最后核对：2026-11-15 ｜ `doc-marshal check --all`：**44 篇笔记，0 error**。

## 1. 怎么找文档

| 你想… | 先看 |
|---|---|
| 知道产品做什么、不做什么 | [PRD](prd-devops-agent-v1.md) |
| 写代码前确认约束 | [项目规约](project-conventions-v1.md)、[规约自动化检查](convention-automation-checks-v1.md) |
| 快速理解代码结构 | [Code Wiki](code-wiki.md) |
| 用起来（接入项目、触发、回滚） | [流水线工作台使用指南](pipeline-workbench-guide-v1.md) |
| 部署/运维这个平台本身 | [运维手册](operation/runbook.md) |
| 本地开发与调试 | [开发指南](dev/overview.md) |
| 查全部文档的类型与摘要 | 运行 `doc-marshal index` |
| 查某个决策为什么这么定 | [ADR 索引](adr/overview.md) |
| 查架构、数据流与模块边界 | [架构索引](architecture/overview.md) |
| 查接口 | [API 说明](api/overview.md) |
| 查功能规格与任务清单 | [规格索引](specs/overview.md) |
| 查调研证据（只读，不作结论） | [调研索引](research/overview.md) |
| 想知道文档怎么写、怎么治理 | [Agent 文档治理方案](governance/agent-doc-governance-v1.md) |
| 查某一版改了什么 | [变更记录](changelog/2026-11.md) |
| 找已废弃/删除的文档 | [废弃登记](deprecated/registry.md) |

## 2. 目录与命名

```
docs/
├── overview.md          本总览（人工导航，不是强制索引）
├── NOMENCLATURE.md      平台共享术语
├── AGENTS.md            给 Agent 的文档树使用说明
├── .doc-marshal/        类型契约与锚点定义
├── adr/                 架构决策记录（ADR-000x）
├── architecture/        架构图、数据流、模块边界
├── governance/          文档治理：契约、同步、收敛与审批规则
├── api/                 接口索引
├── operation/           部署、排障、备份、回滚
├── dev/                 本地启动、调试、质量门禁
├── specs/               功能规格与任务清单
├── research/            调研证据（只读，不作当前结论）
├── changelog/           按月的变更记录
├── deprecated/          废弃登记
└── *.md                 现行设计/规约/决策文档
```

命名硬约束：文件名与目录必须是 ASCII kebab-case；`docs/` 下不允许 `README.md`（doc-marshal 会拒绝）。新文档用 `doc-marshal new <type> <path>` 生成，不要手写 frontmatter。

## 3. 类型与状态

| 类型 | 用途 | 结构约束 |
|---|---|---|
| `reference` | 可查阅的事实、架构、设计说明 | 至少一个 `code_refs` 或 `source` |
| `spec` | 功能规格、产品稿 | `status: proposed/in-progress/done`；`done` 前必须有 `code_refs` |
| `plan` | 有边界的工作项 | 同上；必须有 `code_refs` |
| `decision` | ADR | 必须含「决策」「后果」；append-only |
| `convention` | 编码/写作约定 | 必须有 `code_refs` |
| `record` | 变更记录、废弃登记、外部调研快照 | 不做代码锚定 |
| `nomenclature` | 共享术语 | 固定文件名 `NOMENCLATURE.md` |

人工状态词仍用于阅读判断：**稳定**＝可依赖；**现行**＝当前有效、仍在演进；**部分现行**＝核心已实现、部分为规划；**Draft**＝产品目标稿；**参考**＝设计稿；**规划**＝未实施。

## 4. 删除与留痕

1. 删除正式文档前，在 [废弃登记](deprecated/registry.md) 写明原因、替代物与删除日期；
2. 同一事实只写一处，其余用链接引用；
3. 外部 Wiki 只做索引同步，不做双向覆盖；
4. 无法确认的信息写风险清单，不编造；
5. 历史记录（`changelog/`、`research/`、`deprecated/`）不当作当前结论。

## 5. 校验命令

```bash
# 文档契约、frontmatter、链接、代码锚点（任一失败即非 0）
uvx --from doc-marshal==0.6.1 doc-marshal check --all

# 当前分支改动触碰到的、需要重新确认的文档
uvx --from doc-marshal==0.6.1 doc-marshal drifted

# 全部文档的类型与摘要
uvx --from doc-marshal==0.6.1 doc-marshal index

# 代码侧约束与单测
mvn -q -f platform/pom.xml verify
```

本地提交由 `.pre-commit-config.yaml` 拦截；CI 在 `mvn verify` 前执行同一检查。

## 6. 健康看板（2026-11-15）

| 指标 | 结果 |
|---|---|
| doc-marshal check | 44 篇笔记，0 error |
| 断链 | 0（doc-marshal 校验） |
| 代码锚点 | 每篇 `reference` / `spec` / `plan` / `convention` 至少一个，路径必须存在且被 Git 跟踪 |
| 已删除历史文档 | 9 篇（2026-11-15，逐条登记在 [废弃登记](deprecated/registry.md)） |
| 代码-文档漂移 | 2026-11-15 全量复核 31 篇：7 个固定动作、62 个 @Tool、prod 只读 + L3/破坏性 L2 确认、4 类 stage + 内部 `git-checkout`、E-705 语义、环境表/端口字段、ADR-0002/0005 时点结论 |
| 仍需人工 | 语义一致性（路由/工具数/配置键/模块数）、复审日、架构变更是否补 ADR |

## 7. 边界

- doc-marshal 能机械发现：缺 frontmatter、类型契约不符、链接失效、`code_refs` 路径消失、命名不合规。
- 它不能自动发现：代码逻辑变了但路径没变。此类改动必须在评审中运行 `doc-marshal drifted` 并更新对应笔记。
- `changelog/`、`research/`、`deprecated/` 是记录，不伪装成现行技术文档。
