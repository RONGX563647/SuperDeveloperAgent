---
canonical: docs/summary/2026-11/2026-11-19_2302_round-status.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T23:02:00+08:00
extends: feat-verification
refs:
  - chore/2026-11-19_2254_openapi-snapshot-refresh
  - feat/2026-11-19_2151_feat-5-usage-hitrate-component
  - chore/2026-11-19_2243_cherry-pick-sha-fix
metrics:
  sessions: 5
  casesAdded: 5
  casesTotal: 950
  passRate: "100%"
  lineCoverage: "N/A"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 本轮收口结果（2026-11-19 23:02）

## 一、这一轮真正落地的代码

| 内容 | 提交 | 状态 |
|---|---|---|
| 界面：发布事实徽标、日志总行数、复用产物部署入口 | `4fe1c13` / `636a7ce`（cherry-pick 前为 `ea95dc0` / `2d8c0e4`） | 已进主干（PR #24） |
| 用量：`UsageSummary.cacheHitRate` 组件 + 三维度派生命中率 | `dbeb728` | 已进主干（PR #27） |
| HOST 部署产物复制重试 + Dockerfile 预检提示指向 `ensure_dockerfile` | `3f22301` / `96baa9b` | 已进主干 |
| CI：平台合并提交自动识别（清空白名单） | `1104fb9` | 已进主干（PR #23） |

主干 CI 连续三次 `success`，其中一次就是本轮的合并提交。

## 二、看板

OPEN 从 23 条降到 **4 条，且全部是 FEAT**：
FEAT-1（super agent）、FEAT-2（派发自动执行）、FEAT-3（多 agent / A2A）、FEAT-5（token）。

## 三、这轮反复踩到的三类「假问题」

这三条值得单独记下来，因为它们每次都伪装成「代码写错了」：

### 1. 分支落后主干 → CI 归档校验失败

本地 `gate validate` 通过、CI 却红，差别在于分支缺了主干上的归档文件。
**判别方法**：`git rev-list --count origin/主干..分支` 看落后多少；
缺的那几个提交里通常就有归档。

### 2. cherry-pick 后归档记的旧 sha → 「commit 不存在」

归档跟着代码一起 cherry-pick，sha 变了但 manifest 没变。
**判别方法**：CI 报 `commit xxx 不存在` 时，先确认那个 sha 是不是 cherry-pick 前的。
**工具**：`tools/session-archive/append-cherry-pick.py <manifest> <旧> <新>`。

### 3. 多模块改了上游却没 install → 本地生成「看起来没用」

改了 `agent-domain` 的 record，只跑 `-pl agent-gateway` 时 springdoc 读到的是
本地仓库里的旧 jar，重新生成快照毫无变化。
**判别方法**：`mvn -pl 上游模块 install -Dmaven.test.skip=true` 之后再生成。

## 四、并行工作线造成的干扰（已处理，未替其修）

这个 worktree 同时被另一条工作线使用，期间出现过多次「编译不过」挡住提交：

| 现象 | 处理 |
|---|---|
| `IssueExcelExporterTest` 引用尚不存在的 API | 临时 stash 出去，推完归还 |
| `BuiltInPipelineEngine` 与 `TriggerScheduler` 不同步 | 同上 |
| 新增 Excel 导出接口让 OpenAPI 快照过期 | 按门禁要求重新生成（快照必须与代码一致） |

原则：不替另一条线改代码，只做必要的隔离，且所有 stash 都已恢复回工作区。

## 五、剩下的 4 条 FEAT 为什么不再动代码

见 `docs/summary/chore/2026-11_feat-verification.md` 的逐条核实：

- FEAT-1/2：调度骨架（DAG、重试、熔断、`ChildDigest` 汇总）已完整，缺实机跑一轮；
- FEAT-3：A2A 协议**完全没有**，是真正的新功能，属立项级别；
- FEAT-5：三项压缩都已在代码里，这轮补上了命中率度量，接下来是看数字而非写代码。
