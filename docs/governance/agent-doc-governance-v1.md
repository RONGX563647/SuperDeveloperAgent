---
type: plan
summary: "用 doc-marshal 统一文档契约、代码锚点、索引与漂移检查"
status: in-progress
code_refs:
  - .workflow/build.yml
  - .pre-commit-config.yaml
---
# Agent 文档治理方案 v2（doc-marshal）

> 日期：2026-11-15 ｜ 状态：全量迁移已完成，进入运行期
> 结论：**用一个 doc-marshal 替代 OttoDoc + Ohara + Wiki-Forge 的主体功能**。零运行时服务，一条 `check --all` 串起写入约束、代码锚定和门禁；不再维护自研的 `check-links` / `check-docs` / `CODE-MAP`。

---

## 1. 为什么换

旧方案的问题不是检查不够多，而是三套东西各管一段：登记表管可达性、CODE-MAP 管代码锚点、脚本管断链。Agent 仍然可以写任意结构，代码搬家后只能靠人记得改表。

doc-marshal 把三件事压进文档本身：

| 旧机制 | 新机制 | 差异 |
|---|---|---|
| `docs/README.md` 手写登记表 | `doc-marshal index` 从 front-matter 生成 | 索引不再手工维护 |
| `CODE-MAP.md` 人工代码锚点 | 每篇 front-matter 的 `code_refs` | 锚点跟着文档走 |
| `check-links.mjs` | `doc-marshal check --all` | 同时校验类型契约、链接、锚点、命名 |
| `check-docs.mjs` D04 未登记 | 类型/目录/文件名契约 | 不合规文档无法进入文档树 |
| 无漂移检查 | `doc-marshal drifted` | 当前分支改动触达 `code_refs` 即列出 |

---

## 2. 已落地的硬约束

1. `docs/` 下每篇笔记必须有 `type` 与 `summary`；
2. 文件名与目录必须是 ASCII kebab-case；
3. `docs/` 下不允许 `README.md`；入口改为 [overview.md](../overview.md)，索引由 `doc-marshal index` 生成；
4. 类型决定结构契约：
   - `reference`：至少一个 `code_refs` 或 `source`；
   - `spec`：`status` 为 `proposed/in-progress/done`，`done` 前必须有 `code_refs`；
   - `plan`：必须有 `code_refs`；
   - `decision`：必须含「决策」「后果」，append-only；
   - `convention`：必须有 `code_refs`；
   - `record`：变更记录、废弃登记、外部调研快照，不做代码锚定；
5. 本地提交由 `.pre-commit-config.yaml` 拦截；CI 在 `mvn verify` 前执行同一检查。

当前类型定义在 [docs/.doc-marshal/types](../.doc-marshal/types)，共享术语在 [NOMENCLATURE.md](../NOMENCLATURE.md)。

---

## 3. 全量迁移记录（2026-11-15）

| 动作 | 结果 |
|---|---|
| 中文文件名转 ASCII kebab-case | 35 个文件重命名，Git 历史跟随 |
| `README.md` 退出文档树 | 根改为 `overview.md`，子目录改为 `overview.md` / `runbook.md` / `registry.md` |
| 44 篇笔记补 front-matter | 类型、摘要、状态、`code_refs` / `source` |
| 删除自研检查 | `scripts/docs/check-links.mjs`、`check-docs.mjs`、`docs/CODE-MAP.md` |
| 接入门禁 | `.pre-commit-config.yaml` + `.workflow/build.yml` |
| 文档入口 | [overview.md](../overview.md)、[llms.txt](../../llms.txt) 指向新路径 |

迁移后 `doc-marshal check --all` 为 0 error；代码锚点路径必须存在且被 Git 跟踪。

---

## 4. 日常流程

```bash
# 新建：类型、front-matter、目录一次生成
uvx --from doc-marshal==0.6.1 doc-marshal new reference docs/new-topic.md

# 写完校验
uvx --from doc-marshal==0.6.1 doc-marshal check --all

# 当前分支改动触达了哪些文档的 code_refs
uvx --from doc-marshal==0.6.1 doc-marshal drifted

# 给 Agent / 人看的索引
uvx --from doc-marshal==0.6.1 doc-marshal index
```

写入规则：

1. 先查 `doc-marshal index`，同一事实已有归属就改原文，不新建；
2. 新文档必须选类型；不能归类就先改本方案；
3. 改代码后运行 `drifted`，被列出的文档由 owner 确认；
4. 删除文档前在 [废弃登记](../deprecated/registry.md) 留原因与替代物；
5. Agent 只能写 PR 分支或 `_inbox`（仍为规划），不得直推 `main`。

---

## 5. 运行期仍然靠人的部分

doc-marshal 只能回答“结构是否合规、锚点路径是否存在、当前分支是否触达了这些路径”。它不能回答“代码逻辑变了但路径没变”。

因此保留三条人工规则：

- 代码评审必须跑 `doc-marshal drifted`，并在 PR 描述里说明受影响的文档；
- 修改 `reference` / `spec` / `convention` 的结论时，同步改 `code_refs`，不要只改正文；
- ADR 是 append-only：结论变化新增 ADR 或写“后续变更”，不改历史决策。

---

## 6. 暂缓项与触发条件

| 暂缓项 | 触发条件 |
|---|---|
| `docs/_inbox/` Agent 写入闸门 | 多个 Agent 并发写 `docs/`，冲突或覆盖真实发生 |
| `docs/reports/` 成品归档 + `manifest.yml` | 出现需要逐句评审的 HTML/Office 成品 |
| 重复段落 / 孤立文档报告 | 文档 > 60 篇，或同一事实在 3 篇以上重复 |
| RAG 导出（JSONL） | `doc-marshal index` + `llms.txt` 无法满足检索 |
| 更严的类型章节契约 | 现有类型连续两轮评审未发现结构问题，说明当前约束够用 |

---

## 7. 验收指标

| 指标 | 目标 | 采集方式 |
|---|---|---|
| `doc-marshal check --all` | 0 error | CI / pre-commit |
| 断链 | 0 | doc-marshal |
| `code_refs` 路径失效 | 0 | doc-marshal |
| 未登记/无类型文档 | 0 | doc-marshal |
| 代码改动触达但未复审 | 0 | `drifted` + PR 记录 |
| Agent 直写 `main` | 0 | 分支保护 + 审计 |

---

## 8. 风险与不做什么

| 风险 | 缓解 |
|---|---|
| ASCII 文件名降低中文可读性 | H1 与 `summary` 保留中文；导航入口用中文标题 |
| 自定义类型契约过松 | 先保证类型/锚点/链接；结构章节按触发条件收紧 |
| `drifted` 只按路径，不判语义 | 代码评审人工确认；不自动改文档、不自动改状态 |
| 迁移一次改动过大 | 已按类型分批核对；Git 历史保留重命名前路径 |

**不做**：不部署文档平台；不让 Agent 自动改写或自动删除文档；不把外部 Wiki 当第二事实源；不引入 RAG 向量库。
