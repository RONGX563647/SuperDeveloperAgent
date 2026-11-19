---
canonical: docs/summary/2026-11/2026-11-20_0030_dispatch-gap.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T00:30:00+08:00
extends: round-status
refs:
  - feat/2026-11-19_2326_feat-5-usage-hitrate-panel
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 960
  passRate: "100%"
  lineCoverage: "N/A"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# FEAT-1/2/3 的真实缺口：机制在，实机没跑通（2026-11-20）

> 看板剩 3 条 FEAT，全部 P0。这轮不写代码，只做**证据核实**，
> 因为前几轮的教训是：机制存在 ≠ 功能可用，而看板不会告诉你差别。

## 一、数据库里的事实

查 `data/workspaces/_platform/conversations.db`（`python tools/task-board/check-dispatch-evidence.py`）：

```
conversations: 10 行
  kind=NORMAL: 7
  kind=SUPERVISOR: 3
  有父会话的子会话: 0
conversation_runs: 2 行（都是普通会话的）
workspace_tasks: 4 行，全部 TODO
```

**3 个调度会话建了，但从没派发过任何子会话。** 派发票据表里的 4 条全是
`TODO`（是更早的规划条目，不是真实派发产生的）。

## 二、代码里的机制（都在，且有测试）

| 能力 | 实现 | 测试 |
|---|---|---|
| DAG 并行与依赖 | `WorkspaceDispatchScheduler` | `WorkspaceDispatchSchedulerTest` 12 例 |
| 失败重试 / 连续失败熔断 | 同上 | 同上（含「重试复用同一会话」「熔断后不再派发」） |
| 循环依赖拒绝 | 同上 | 同上 |
| 派发票据与回写 | 同上 | 同上（「任务板能看到规格、会话与结论」） |
| 会话管理工具面 | `WorkspaceSupervisorTools` 20 个工具 | `ToolChainTest` |
| 子会话结论汇总 | `ChildDigest` | `ChildDigestTest` 5 例 |

**结论**：机制侧没有缺口。FEAT-1 说的「而非人肉调度」，卡点不在代码。

## 三、卡点在哪（三个候选，需要一次实机派发来区分）

1. **调度 Agent 没被真正驱动**：3 个 SUPERVISOR 会话都是 `IDLE`，
   说明用户打开过调度面板但没发过指令，或发过但模型没走到 `workspace_dispatch`；
2. **模型没选对工具**：调度会话的工具面是 20 个管理工具（`dispatch` 插件），
   如果模型不熟悉 `workspace_dispatch` 的参数结构（nodes/depends_on），
   可能退化成「建议用户自己派发」；
3. **派发失败被静默**：`ConversationManager.send` 曾在两条分支返回 `err:` 文案
   （已在批次 14 修掉），如果当时失败过，可能留下「看起来没派发」的印象。

## 四、怎么验证（一次就够）

```
1. 打开工作区的「调度」会话，发一句：
   「把这三件事并行派发出去：查最近一次失败运行的错误码、读 docs/handbook 的
     system-overview、总结当前环境的中间件状态；三个都完成后汇总」
2. 跑 python tools/task-board/dispatch-baseline.py 对比基线
   （基线已记录：children=0, runs=2, tickets=4）
3. 判定：children>0 且 runs 增加 → 派发生效；children 仍为 0 → 卡在第三节的 1 或 2
```

## 五、为什么这轮不直接跑

派发会触发真实 LLM 调用（消耗额度）并在工作区创建会话，
属于「用用户资源做验证」的动作。基线脚本已就位，一次点击即可完成验证；
需要我代跑时明确说一声。

## 六、FEAT-3 与另外两条不同

FEAT-3 除了会话内协作，还要 **A2A 协议**（调用本机 trae / workbuddy / dsh 分发任务）。
全仓检索无 A2A 实现——这是**真正的新功能**，不是验证缺口，建议单独立项。
