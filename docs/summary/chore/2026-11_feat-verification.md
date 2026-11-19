---
canonical: docs/summary/2026-11/2026-11-19_2112_feat-verification.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T21:12:00+08:00
extends: final-status
refs:
  - chore/2026-11-19_2059_bug-10-release-facts-ui-merge
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 900
  passRate: "100%"
  lineCoverage: "N/A"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 剩余 4 条 FEAT 的实现核实（2026-11-19）

> 结论先行：**这 4 条里，代码侧该做的都已经有了；缺的是实机验证，以及一条
> 真正的新功能（A2A 协议）。** 不能靠再写一遍来「关闭」它们。

## 一、逐条核实

### FEAT-5 token 爆炸（P1）

原文三项诉求，逐项核对代码：

| 诉求 | 现状 | 证据 |
|---|---|---|
| 1. 每次对话都插 tool，需要渐进 + 插件化 | **已实现** | `ProgressiveToolMiddleware`、`ToolPluginIndex`；核心常驻工具只有 8 个（`ToolSchemaBudgetTest` 锁住） |
| 2. prompt / skill / mcp 同样压缩 | **已实现** | skill 只注入 name + 一句话，正文由 `skill_load` 按需拉；能力索引文案固定顺序（`ToolPluginIndex` 注释明确为缓存稳定） |
| 3. 提升 KV cache 命中率 | **已实现 + 有度量** | `ConversationContextTrimmer` 留 25% 余量才裁、一次裁到 60%（高频小裁会让前缀缓存反复失效）；`UsageTotals.cacheHitRate()` 已提供命中率 |

**真正缺的**：没有任何「命中率随时间/模型变化」的可视化或告警。数据在库里
（`cachedTokens` 已入库），接口能算比率，但没人看。要做的是**观测面板**，
不是再改压缩逻辑。

### FEAT-1 super agent 搭建（P0）

调度骨架已经完整：`WorkspaceDispatchScheduler`（DAG 并行、依赖、重试、熔断）、
`WorkspaceSupervisorTools`（会话创建/派发/等待/取消/汇总）、`ChildDigest`
（父会话汇总子会话结论，批次 11 补）。

**真正缺的**：这条要的是「一支真正的 agent 军团而非人肉调度」。骨架在，
但需要真跑一次多子任务的编排来验证它确实能自己跑完——这是验收工作。

### FEAT-2 任务派发 agent 自动执行（P0）

原文三项：工具不足以支撑执行、token 爆炸与提前中止、效果验证未知。

- 「工具不足」：派发链路已具备（工具面按插件激活，写工具默认不可见）；
- 「提前中止」：`DispatchPlan` 有 `abortOnFailure` / `maxConsecutiveFailures` 熔断；
- 「效果验证未知」：`ChildDigest` 已能汇总结论。

**真正缺的**：同 FEAT-1，需要实机跑一轮才能回答「效果如何」。

### FEAT-3 多 agent 协作 / A2A（P0）

原文两项：会话内 agent 协作、以及通过 **A2A 协议**调用本机 trae / workbuddy / dsh 分发任务。

- 会话内协作：已具备（supervisor + 子会话 + DAG）；
- **A2A 协议：完全没有**。全仓检索无 A2A 实现，也无对外 agent 调用协议。

**这一条是真正的新功能**，量级不小（协议定义、能力发现、任务状态机、回调），
不适合塞进 bug 收口里顺手做。

## 二、为什么不再动代码

四条里有三条的代码侧已经完成。继续「补代码」只会产生重复实现，
而看板上的「未关闭」反映的其实是**验收缺口**：

- FEAT-5 需要看命中率数字，才知道压缩策略是否真的有效；
- FEAT-1/2 需要跑一轮真实多任务编排，才知道调度是否可用；
- FEAT-3 需要一个新协议，属于立项级别。

## 三、建议的推进方式

1. **FEAT-5**：先把 `cacheHitRate` 接到设置页的用量面板（数据已全，改动小），
   跑几天看数字，再决定要不要继续压 prompt；
2. **FEAT-1/2**：在本机跑一次「父会话派发 3 个子任务」的端到端编排，
   用 `ChildDigest` 看汇总是否合理；
3. **FEAT-3**：单独立项，先定协议边界（只做本机调用，还是跨机），
   再决定是自研还是接 A2A 标准。
