---
name: archive-gates
description: 会话归档与门禁：gate.py 用法、ledger 链、断链修复、hooks、pre-push 全流程
covers: [gate.py, diag-chain.py, sweep.py, migrate-to-ledger.py, tools/docs/build.py, ProcessArchiveTest]
when_to_read: 要提交/推送/归档/改门禁/chain.prev 断链/归档校验失败/改 session-archive-spec
budget: 150
---

# 会话归档与门禁（archive-gates）

## 数据模型（2026-11-20 起）

- **唯一写入点**：`gate.py finish` 往 `docs/ledger/<YYYY-MM>.jsonl` 追加一行（= 一份 manifest，
  schema `process-manifest/v2`）。没有每会话目录、没有散文总结。
- **视图是投影**：`docs/views/`（index/changelog/weekly/modules）由 `tools/docs/build.py sync` 生成，
  勿手改；`build.py check` 在 pre-push 强制一致。
- **链**：每分类一条哈希链（`chain.prev`→`chain.self`，按 (createdAt,id) 排序）；
  `self` 只哈希内容，路径搬家不影响链。
- `docs/summary/` 只剩 plan 双份（§4.6 契约）；session-summary 散文已被 views 取代，validate 会拦。

## 标准流程

```bash
python tools/session-archive/gate.py start --category feat|bug|chore|docs|deploy --slug <kebab> --title <中文>
# …写代码 + 测试，git commit（pre-commit 自动跑测试存证据；--no-verify 禁止）
python tools/session-archive/gate.py finish --problem … --solution … --verification …
git add docs && git commit        # 归档/视图一并提交
git push                          # pre-push：归档校验 + views 一致 + handbook + 前端 + spec-checks
```

feat/bug 必须 ≥1 个新增用例；chore 含代码必须 `--verification`；docs 类允许零提交。

## 断链与并行会话

- `gate.py validate` 只报"chain.prev 断链"；**定位用 `python tools/session-archive/diag-chain.py <分类>`**。
- 修复：`gate.reseal_chain('<category>', gate.LEDGER)`（整链按 createdAt 重排重算）。
- rebase/并行合并后提交 sha 会变，归档里的 `commits[].sha` 要重指（历史上反复踩过）：
  找到等价新 sha 改进去，重算 `chain.self`，再 reseal 后续。

## 其它入口

| 命令 | 用途 |
|---|---|
| `gate.py validate` | 全量校验（spec-checks 的 ProcessArchiveTest 也调它） |
| `gate.py self-test` | 门禁自检（合成样本走一遍正反用例） |
| `sweep.py --dry-run` | IDE 会话对账（topics.md → 补录 docs 类归档） |
| `migrate-to-ledger.py` | 历史迁移脚本（manifest 目录 → ledger），审计留档 |
| `tools/docs/build.py sync/check/list` | views 生成/校验/统计 |
| `tools/docs/find.py` | 检索（关键词/模块/影响面/stale） |

## 怎么验证

改 gate.py 后必跑：`gate.py self-test` + `gate.py validate` + `sweep.py --dry-run`
（这三条就是 spec-checks 里 ProcessArchiveTest 的全部内容）。

## 找历史

`python tools/docs/find.py "归档"`；`docs/session-archive-spec-v1.md` 是完整规约。
