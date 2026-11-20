---
type: docs-map
summary: docs 树地图与消费规则：agent 从这里开始，按任务进 skills，历史用 find.py 检索，不整树通读
---

# docs/ 地图

## 目录与消费方式

| 目录 | 是什么 | 谁写 | 怎么用 |
|---|---|---|---|
| `skills/` | 任务域知识卡（每张 ≤150 行） | 人（index 生成） | **agent 从这里开始**：先读 [skills/index.md](skills/index.md) 路由表，按 `when_to_read` 匹配读 1–2 张 |
| `views/` | 生成视图：近期索引 / 变更日志 / 周报 / 模块档案 | `tools/docs/build.py` | 看"最近改了什么、哪个模块动过"；勿手改 |
| `ledger/` | 会话留痕（JSONL，追加式，哈希链） | `gate.py finish` | **不整读**；用 `tools/docs/find.py` 检索 |
| `summary/plan/` | 评审/优化方案（plan 契约，双份） | 人 | 找方案、验收契约、执行状态时读 |
| `code-wiki/` | 架构手册（为什么这样设计） | 人 | 理解全局、改架构前读对应篇 |
| `handbook/` | 行为→源码锚点（生成） | `tools/handbook/build.py` | 定位"这段行为在哪个方法" |
| `schemas/` | 文档契约 schema | 人 | 校验器用 |
| 根 `*.md` | 流程规约（归档/门禁/CI） | 人 | 动对应机制前读：`session-archive-spec-v1`、`quality-gate-design-v1`、`ci-commit-encoding` |

## agent 阅读路径

1. 本页 → 2. [skills/index.md](skills/index.md) 路由表匹配任务，读 1–2 张卡（每张标了 `budget`）
   → 3. 需要历史/影响面时 `python tools/docs/find.py "<关键词>"` 或 `--module` / `--impact` / `--recent`。

**单次任务的文档预算 ≤ 3 个文件**。全树 ≈ 370k token，其中 `ledger/`（约 2/3）是审计数据，
明确不读；agent 的入口成本是本页 + 1 张 skill ≈ 5k token，需要历史才付检索的成本。

## 生成物与写入规则

- 唯一写入点是 `ledger/`（`gate.py finish` 追加一行 = 一份会话记录）。
- `views/`、`skills/index.md`、`handbook/` 是投影：手改必漂移，pre-push 会红；重新生成即可
  （skills 卡片本体是手写的，index 从卡片 front-matter 生成）。
- 方案类文档走 `summary/plan/` 的 plan 契约（见 spec §4.6），不写散文总结。

## 快速查找

```bash
python tools/docs/find.py "docker pull 超时"     # 关键词（留痕 + skills）
python tools/docs/find.py --recent 10            # 最近 10 个会话
python tools/docs/find.py --module agent-executor
python tools/docs/find.py --impact DockerSandbox # 影响面：谁动过这个文件/类
python tools/docs/find.py --stale                # 30 天没更新的手写文档
```
