<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：agent-runtime

29 个会话；检索用 `python tools/docs/find.py --module agent-runtime`。

## 2026-11-20 · 补齐 token 治理三条量化验收与插件分项归因（feat `e94171d`）
- 问题：token 爆炸专项验收 9 项里 6 项功能面通过，但 3 项量化指标（inputTokens 降 40%/命中率 60%/真实请求 toke…
- 方案：新增 TokenBaselineTest 用真实工具面+评测指令锁定按需 schema 下降≥40%（最坏59.1%）防回涨；
- 验证：隔离 worktree 全量 verify 1020 例 0失败0错误7跳过；

## 2026-11-20 · 会话能力页增加内置工具展示并改造界面与市场多源接入（feat `a381003` `df34598` `59f07ad` `bd18e87` `7296fc8` `b9add58` `415b525` `43eb19c` `8139279` `35611ee`）
- 问题：会话能力页缺少内置工具展示、界面信息层级差、MCP/Skill 市场仅单一仓库来源
- 方案：能力页新增内置工具区（复用 AgentToolCatalog 投影）并重做三卡概览界面；
- 验证：独立跑通 spec-checks 35 例、市场相关 10 例、前端 13 个测试文件 23 例、vue-tsc 类型检查全绿；

## 2026-11-20 · 派发票据回写撞主键（closeTicket 误用 INSERT）（bug `03f5aee`）
- 问题：调度派发票据关单时 closeTicket 对已存在票据用 saveTask（INSERT），每次回写终态都撞 workspace_tasks_…
- 方案：closeTicket 改走 updateTask（UPDATE）；
- 验证：去掉修复时该测试失败（复现 duplicate key）；

## 2026-11-20 · 0.1.1 OPEN bug 批量修复（27+3 条）（bug `6acdb24`）
- 问题：PG 工单 30 条 OPEN BUG（0.1.1 的 27 条 + 无版本 3 条）逐一按根因分析修复
- 方案：7 个并行修复域：工具面收敛（28/29/30/31）、执行器（32/35/38/39/41/46）、进程管理（40/42/43）、gitiss…
- 验证：agent-domain 97 / agent-infra 162 / agent-executor 258 / agent-tools 85 …

## 2026-11-19 · BUG-24 工具调用悬挂导致会话永久中断（bug `1b01d77`）
- 问题：BUG-24 工具调用悬挂导致会话永久中断
- 方案：﻿fix(agent): 开启悬挂工具调用恢复，堵住会话永久中断
- 验证：Tests run: 66, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-19 · BUG-7 派发返回 runId 契约与子会话失败可见（bug `5f71090`）
- 问题：BUG-7锛堝伐浣滃尯浼氳瘽涓㈠け + 瀛?agent 鏃犳硶姝ｅ父宸ヤ綔锛夌殑鍚庡崐锛氳繘鎵ц璇︽儏澶嶆牳鏃剁‘璁や簡涓€涓湡瀹炲绾︾己…
- 方案：send 鎭㈠涓哄崟涓€濂戠害锛氳涔堣繑鍥炲悎娉?runId锛岃涔堟姏 RunException锛堥噸澶嶇洰鏍囩敤 RUN_INVALID…
- 验证：mvn -pl agent-runtime,agent-gateway test 通过（146 + 64 用例）；

## 2026-11-19 · 调度会话可汇总子会话结论（feat `c8d8b71`）
- 问题：task-de956c65b7（工作区助手 super agent 搭建，实为 FEAT）：supervisor 已经能 conversatio…
- 方案：新增 ChildDigest 记录（会话 id / 标题 / 会话状态 / 最近运行 / 结论 / 错误 / 目标 / 更新时间），一行摘要优先…
- 验证：Tests run: 194, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-18 · 补丁守卫放行 src-main 并返回结构化拒绝（feat `4e8ddfb`）
- 问题：task-9779e75d91（P0 真阻塞）：propose_patch 的路径守卫白名单过窄，agent 连自己仓库的 src/main 都…
- 方案：把业务源码 src/main/** 与测试代码 src/test/** 纳入允许清单；新增拒绝清单（优先级高于允许）覆盖 .git/、targe…
- 验证：Tests run: 424, Failures: 0, Errors: 0, Skipped: 3

## 2026-11-18 · 门禁加固：CI、规则自测、覆盖率闸门、前端自检（chore `4efd514`）
- 问题：门禁加固：CI、规则自测、覆盖率闸门、前端自检
- 方案：feat(gate): 真实 CI、规则 fixture 自测、覆盖率闸门与前端自检
- 验证：全量 mvn verify 144.7s rc=0（含 jacoco check，All coverage checks have been m…

## 2026-11-18 · 服务端时间线落盘+任务票据化与断线续传/看门狗（feat `c969d20`）
- 问题：会话事件只在内存总线里滚动，刷新/换端/进程重启后看不到历史；运行卡死时一直显示运行中并占着会话并发位。
- 方案：新增 conversation_timeline 落盘与游标读取、派发任务票据化；新增 SSE 时间线订阅支持 Last-Event-ID 断线…
- 验证：mvn -B -pl spec-checks -am test 全量通过；新增 ConversationTimelineRecorderTest…

## 2026-11-18 · Token 治理批次1：KV 命中率可观测 + 历史折叠摘要（feat `b95f342`）
- 问题：token 治理的前两层（渐进工具披露、工具输出限流、上下文预算）已落地，但后端没有统一的 KV 缓存命中率口径；上下文裁剪直接丢弃旧历史，ru…
- 方案：1) UsageTotals.cacheHitRate() 统一命中率算法，TokenUsageRecorder 日志追加 hit=xx.x%，…
- 验证：新增 ConversationContextSummaryTest(4)、TokenUsageRecorderTest(3)、UsageTota…

## 2026-11-18 · 合并远端快照并修复 HostDeployerTest 跨平台依赖（chore `898c6e0` `412043c` `c274cc8` `c6a2e4a` `8ef1466` `d8ce889` `769dd14` `4b1bc6c` `0f65030` `d34a378` `6a899f6` `7f9294c` `2702dec` `52ef7e5` `dd45b7c` `36cb5a9` `d2846c3` `aa2a6a8` `a83d4ff` `97f91d6` `f816a5d` `e6380aa` `5c69021` `dd8fd33` `8a19091` `854f4cc`）
- 问题：本地 0.1.0-snapshot 与远端分叉（本地 9 个持久化提交，远端 26 个沙箱/变更卡提交），直接 push 会被拒绝；且两处改动在…
- 方案：merge origin/0.1.0-snapshot：application.yml 两侧改动合并；ChangeRegistry 以 JPA …
- 验证：合并后全量：602 用例，0 失败 0 错误 4 跳过（排除 4 个依赖 Docker/历史归档/Windows 的环境用例）。

## 2026-11-18 · 修复会话时间线被下一轮覆盖的竞态（bug `92ad561`）
- 问题：ConversationManagerTest 偶发失败：waitForRun 返回时时间线可能还没写完，同一会话紧接着的下一轮会读到旧时间线并…
- 方案：WorkspaceRunExecutor.finish 调整为 saveTranscript 先于 runs.updateRun 落终态，保证外…
- 验证：mvn -B test -Djacoco.skip=true（排除 4 个环境用例）：571 用例，0 失败 0 错误 4 跳过；Convers…

## 2026-11-17 · 补录：变更卡状态反馈（amend 后 SHA 变更）（bug `898c6e0`）
- 问题：变更卡状态反馈会话归档记录的是 amend 前的提交 SHA(eaf56bc9)，amend 后实际提交为 898c6e0，导致归档校验把 89…
- 方案：按实际提交 898c6e0 重新计算文件 blobHash 并补一份归档 manifest，接在原归档的链上；
- 验证：git diff-tree 898c6e0 与归档记录文件树一致；

## 2026-11-17 · 变更卡：留痕单不推卡、失败回执与状态查询（bug `898c6e0`）
- 问题：确认卡链路三处口径不一致：留痕单(env_ensure/deploy_save_recipe/env_mapping_draft)也会弹可执行卡…
- 方案：ChangePlan 增加 executable() 与 status(PENDING/CONFIRMED/APPLIED/FAILED) 并持…
- 验证：worktree 干净基线 pre-commit 全量 spec-checks 测试通过；

## 2026-11-17 · 合并远端异常码迁移并适配版本发布改动（chore `4e1e0b9` `8b3dc1b`）
- 问题：推送前发现远端 main 新增「通用异常全量迁移」提交，与本地的版本发布改动在流水线引擎、PipelineController、Handbook…
- 方案：合并 origin/main 并逐处适配：新增的版本/分支/标签校验全部改用 BuildException(BUILD_INVALID_INPU…
- 验证：mvn -B -pl spec-checks -am test：572 个用例通过、0 失败（含 ErrorCodeContractTest 零…

## 2026-11-17 · 异常码全量迁移与零基线门禁（feat `4e1e0b9`）
- 问题：生产代码仍有 641 处未带业务错误码的通用 throw，异常码改造不完整
- 方案：新增 ErrorKind 与各组 INVALID_INPUT/INVALID_STATE/INTERNAL 兜底码；
- 验证：Tests run: 543, Failures: 0, Errors: 0, Skipped: 4

## 2026-11-17 · 插件工具加载提示与轨迹参数写入修复（bug `42fe028`）
- 问题：插件工具（工单写入类 create/update/comment/close_git_issue 等）未加载时模型直接表示做不到，用户看到的是无…
- 方案：tool_search 返回每个命中工具的插件名并直接给出 tool_load 指令；系统提示新增硬规则：所需能力不在工具列表时禁止推脱，必须先…
- 验证：mvn -B -pl agent-domain,agent-gateway -am test -Dtest=ToolPluginIndexTes…

## 2026-11-17 · 工作区子会话消息持久化与派发去重修复（bug `eea143c`）
- 问题：工作区子会话（调度 Agent 派发的会话）运行结束后没有消息记录：WorkspaceRunExecutor 只累积 run.result，时间…
- 方案：WorkspaceRunExecutor.finish 在运行终态时读取已有时间线并追加本轮 user/assistant 消息（结构同前端 m…
- 验证：mvn -B -pl agent-runtime -am test -Dtest=ConversationManagerTest：8 个用例全绿…

## 2026-11-17 · 工作区助手流不结束与 ask 选项卡片（bug `dbadfba` `4899aef` `965c8c1` `4b06e4f` `dd9d791`）
- 问题：工作区助手每轮对话结束必须手动点停止才能发下一条；
- 方案：运行终态（run.completed/failed/cancelled）到达即收尾并断开读流，工作区 SSE 在 AGENT_END 主动关流释…
- 验证：vue-tsc 类型检查通过；

## 2026-11-17 · 能力栏与 Skill/MCP 设置可用性改造（feat `0e2041f`）
- 问题：能力栏与 Skill/MCP 设置不可观测、不可调优：MCP 看不到连接状态与工具清单，Skill 无法验证命中，能力栏缺少搜索筛选排序与 to…
- 方案：能力栏增加搜索/筛选/排序、token 估算、场景预设与设为默认；
- 验证：mvn -B -pl spec-checks -am test 通过（pre-commit 门禁）；

## 2026-11-17 · Worktree 清理、迭代预算与门禁修复（feat `dc67f9f` `cba449e` `4bee2bf` `89a1086`）
- 问题：半残 worktree 删除失败；
- 方案：forceRemoveWorktree 先 prune 再递归删除；
- 验证：Tests run: 503, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

## 2026-11-17 · 接入 springdoc、前端契约审计与错误码收敛（feat `e075cd6` `e2202b3` `979d6dd` `7941122`）
- 问题：缺少可机器校验的 API 契约，前后端类型漂移；
- 方案：接入 springdoc 生成 OpenAPI 与类型快照，增加前端契约审计与 Mock 联调；
- 验证：Tests run: 484, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

## 2026-11-17 · 会话级模型选择、确认卡续跑与多版本部署（feat `89cb898` `8da8302` `886e7a3` `44e4394` `200d1d2` `f2e6ac5` `8d3fb5a` `05621c2` `0b21f44` `249a2a0` `844dff0` `26741f8` `bddd38a` `c6d150d`）
- 问题：会话不能选模型且无用量统计；
- 方案：会话级模型/网关选择、token 统计与会话时间线入库；
- 验证：Tests run: 478, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 环境绑定对话内确认、会话级联删除与能力市场外部目录（feat `240661f` `8accc94` `e91a64b` `d27edd1` `7db1ccc` `3c736c6` `9033a9b` `5e7e945` `15097cc`）
- 问题：环境绑定要跳菜单确认；
- 方案：环境绑定改为对话内确认卡；
- 验证：Tests run: 463, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 平台加固：有界命令、部署配置与镜像自动拉取（feat `30be8dc` `0d1f3de` `f4f9e49` `3d4a763` `ac4534a` `0345fc1` `435c17f`）
- 问题：外部命令无界等待、JDK/镜像硬依赖、Windows 私有仓库认证失败；
- 方案：引入有界命令原语与 OS 自适应解释器、按当前 JVM 解析路径；
- 验证：Tests run: 449, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 工具 schema token 预算：描述瘦身 + 插件收敛 + 输出分级（feat `02bb05e`）
- 问题：77 个工具的完整 schema 每轮常驻（约 20-27k token）：中文长描述里塞流程指令、核心工具过多、未归类工具默认可见、输出截断一…
- 方案：描述压到 ≤40 字符英文并把行为约束迁入系统提示；
- 验证：Tests run: 531, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 工具渐进披露与任务面板改版（feat `4b0a8c4` `ef7eaa8` `2b7dc5f` `b3fbfe7`）
- 问题：每轮对话都把 70+ 个工具 schema、技能全文与 MCP 工具塞进上下文，单次请求工具定义约占两万 token；上下文裁剪过于频繁，模型侧…
- 方案：工具按能力拆成插件，默认只暴露核心工具，其余用 tool_load 按需激活并写入会话状态；技能只给索引、正文用 skill_load 读取，M…
- 验证：Tests run: 516, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · Harness Handbook 三级文档体系（锚点生成 + 漂移门禁）（feat `7a1d78a`）
- 问题：工程文档只有零散的 spec 与 schema，改运行时行为时无法快速定位到源码；
- 方案：docs/handbook 三级结构（L1 总览/L2 阶段/L3 实现单元），L3 由代码内 @handbook 锚点脚本生成行为→源码位置表…
- 验证：Tests run: 507, Failures: 0, Errors: 0, Skipped: 2

