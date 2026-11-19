---
canonical: docs/summary/2026-11/2026-11-19_1220_v010-carryover-ledger.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:20:00+08:00
extends: v010-bug-batch
refs:
  - bug/2026-11-19_1200_bug-7-send-contract-and-child-failure-surfacing
  - feat/2026-11-18_2330_patch-guard-src-main
  - feat/2026-11-18_2341_issue-version-labels-readback
  - feat/2026-11-18_2355_sse-stall-failover
  - feat/2026-11-19_0010_env-binding-history
  - feat/2026-11-19_0024_sandbox-structured-denied
  - feat/2026-11-19_0033_pipeline-log-paging
  - feat/2026-11-19_0044_release-first-and-tag-state
  - feat/2026-11-19_0056_deploy-reuse-build
  - feat/2026-11-19_0057_trace-tool-args-snapshot
  - feat/2026-11-19_0108_capability-availability-reason
  - feat/2026-11-19_0115_supervisor-child-digest
  - feat/2026-11-19_0122_gateway-freshness-and-task-code-archive
  - chore/2026-11-19_1154_bug-4-task-board-rebalance
metrics:
  sessions: 14
  casesAdded: 62
  casesTotal: 748
  passRate: "100%"
  lineCoverage: "N/A"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 14 条遗留项逐条对账（2026-11-19）

> 这一轮把清单里的 14 条 + 三条结构性判断全部落地。
> 每条都走完「会话归档 → 代码+测试 → 提交 → 归档文档 → 推送」，
> 远端分支 `feat/task-backend-batch-1 … 14`（`0.1.0-snapshot` 有保护规则，只能走分支推送）。

## 一、P0 七条

| # | 任务 | 后端落地 | 提交 |
|---|---|---|---|
| ① | `task-76c9432989` 对话流中断 | 心跳改命名事件 `heartbeat`；静默看门狗主动下发 `run.failed`（写明等了多少秒）；终态必落时间线；调度会话补时间线记录器；前端 60 秒无心跳兜底中止并提示 | `c2821ad` |
| ② | `task-2ee7375068` 历史环境卡片 | `GET /env-bindings` 返回 `currentBindingId` + `bindings[]` 全量（草稿/已确认/过期/已解绑）；解绑只标 `REVOKED` 不删记录；`usable()` 口径统一 | `e7a886e` |
| ③ | `task-9779e75d91` 写不进 src/main | 允许 `src/main/**`、`**/src/main/**`、`src/test/**`；新增拒绝清单（`.git/`、`target/`、`build/`、`dist/`、`node_modules/`、`.env`、`*.pem`、`*.key`、`id_rsa*`、`.npmrc`）；拒绝返回结构化 `PATH_DENIED` + 允许清单 + next；`apply` 阶段二次校验 | `4e8ddfb` |
| ④ | `task-de956c65b7` super agent 搭建 | 派发链本已具备（DAG 并行、依赖、重试、熔断、票据、独立 conversationId）；本轮补 `ChildDigest` + `workspace_children`，父会话能汇总子会话结论；分类改 FEAT-1 | `c8d8b71` |
| ⑤ | `task-4730f75e69` 沙箱脆弱 | 隔离参数本已就位（gVisor / cap-drop ALL / no-new-privileges / 只读根 / tmpfs / 资源上限）；本轮补 `SandboxDeniedException`（E-707）六类规则 + 隔离快照写日志 + 运行时不可用绝不静默回落 runc | `6fa64cd` |
| ⑥ | `task-605d5e5fcd` 任务栏版本号 | `GET /git-issues/versions` 给分支/建议版本/标签；`PUT` 改按 key 局部更新（只改版本不再清空标题标签）；工具回显 code/version/labels；前端删掉重复的版本推导 | `ff496d1` |
| ⑦ | `task-a1074891b7` 会话丢失 + 子 agent | 前半：`eea143c` 已落（终态落时间线、去重按会话隔离）。后半：`send` 在两条分支返回 `"err: ..."` 被当 runId 用 → 子会话「派发成功却永不动」；改为抛 `RunException`，派发器改为校验 runId 形状 | `5f71090` |

## 二、P1 两条

| # | 任务 | 后端落地 | 提交 |
|---|---|---|---|
| ⑧ | `task-699cc7692d` 日志不能下滚 | `PipelineEngine.logLines(offset, limit)` + `LogLinePage{offset,limit,total,lines,truncated}`；口径：行尾换行不多算、offset 越界夹紧、先切行再脱敏；`GET /ci/runs/{id}/log/lines` | `bfdb821` |
| ⑨ | `task-6f3620f8f8` 首次发布 | `ReleaseMode.of()`：首次发布是显式正常分支，回滚预检跳过并说明；`versionBranchConflict()` 前置拒绝版本线与分支不一致；运行详情下发 `release{firstRelease,...}` | `9930e82` |

## 三、P2 五条

| # | 任务 | 后端落地 | 提交 |
|---|---|---|---|
| ⑩ | `task-5fdf460ff6` 复用产物 | `PipelineEngine.reusableBuild(runId)`（仅成功且产出镜像才可复用）；`deploy_run` 新增 `build_run_id`，比对提交与版本，不一致明确拒绝；返回 `reusedBuild` | `d26309e` |
| ⑪ | `task-bdaf4497fa` tag 未生效 | `TagState` 枚举：只有 `PUSHED` 算远端可见，`LOCAL` 带 `TAG_PUSH_CREDENTIAL_MISSING` 与「补齐凭据后重推」；`REJECTED` 表示前置拒绝 | `9930e82` |
| ⑫ | `task-90836fe8d5` 发送按钮失效 | 心跳/超时兜底见 ①；另加 `BuildFreshnessCheck`：启动时比对 `target/classes` 与源码时间戳，源码更新就告警「请重启网关」——直指「改了不生效 / 请求挂住」 | `c2821ad` `2fd381e` |
| ⑬ | `task-da2bbce041` 轨迹参数缺失 | 入参归一化扩展到 `tool.started`/`tool.completed`（插件与 MCP 工具走这两条路）；契约：有参给 `args`、无参 `args=null`、拿不到 `args=null + argsUnavailable=true` | `7bd8900` |
| ⑭ | `task-46122095b6` 能力不实用 | `CapabilityState`：`enabled`/`installed`/`available`/`reasonCode`/`reason`/`missingEnv` 分开说；原因码 `CAPABILITY_DISABLED`/`NOT_INSTALLED`/`MISSING_ENV`/`TRANSPORT_UNAVAILABLE`；skill 正文已按需加载（提示词只留 name + 一句话） | `1614c1e` |

## 四、三条结构性判断

1. **真后端 bug 与 UI 分开**：⑨⑩⑪⑬ 靠补字段/接口，⑪⑫ 属实为交互；已在看板把 UI 类降到 P1/P2。
2. **优先级重排**：`task-da2bbce041`（轨迹，影响所有调试）与 `task-90836fe8d5`（挂住不返回）由 P2 升 P0；
   `task-605d5e5fcd`（版本号观感）与 `task-4730f75e69`（已有隔离）P0 降 P1；
   `task-de956c65b7` 由 BUG 改 FEAT-1。口径见 `docs/summary/chore/2026-11_task-board-rebalance.md`。
3. **先修 ③**：它是本轮第一批，也是唯一能一票否决全盘的项。

## 五、附带修掉的两处

- **`task-b19674d242` 后半**：归档目录支持 `--task-code`，编码进目录名与标题（「存档用任务编码作标题」）。`2fd381e`
- **库内两条缺失记录**：`task-76c9432989`、`task-2ee7375068` 在看板里根本不存在，已按报告原文补建。见重排口径文档。

## 六、质量证据

- 全量 `mvn test`：748 用例通过，0 失败（`0.1.0-snapshot` 全 9 模块 + spec-checks）。
- 每批都过 pre-commit 增量测试与 pre-push 全量 spec-checks（架构 A1–A9、禁令 R01–R15、
  错误码契约 EC1–EC5、API 文档基线、Handbook 锚点、归档哈希链）。
- 新增用例 62 个，均为针对缺陷成因的回归（不是覆盖率填充）。
