<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：agent-tools

37 个会话；检索用 `python tools/docs/find.py --module agent-tools`。

## 2026-11-21 · 补归档：远端 0.2 八条 refactor 提交并入 0.1 修复收敛（chore `d0aaa4d` `4bcbb32` `539d4ba` `dbfa7c2` `ca0a837` `e9e294a` `b6487a3` `781ba1f` `d4320a7` `cd05f58`）
- 问题：trae 会话在远程 0.2 推送了 8 条 refactor 提交（d0aaa4d4..781ba1f5），未走会话归档门禁，导致 0.1 修…
- 方案：本会话补登记这 8 条远端 refactor 提交（从共同祖先 c784ff18 起），连同 deploy health 修复提交 d4320a…
- 验证：handbook check 10 锚点/10 份 L3 通过；docs/views check 16 文件一致；session-archive…

## 2026-11-20 · 0.1→0.2 同步：token/deploy 工作 + skills 路由 + 并发健壮性入线（chore `e94171d` `3501d3b` `8145b76` `b9add58` `415b525` `3f97378` `b7aaa93` `43eb19c` `8139279` `35611ee` `c8aaf93` `d630a8c` `e6c9349` `363eccd` `a533133` `77b14fe` `3dc7dc1` `b39e594` `737dd49` `dc587a0` `2d470d6`）
- 问题：0.2 线缺今晚后半段工作：skills 路由层、门禁并发健壮性、防回潮守卫、token/deploy 并行会话成果。
- 方案：merge 0.1→0.2：ledger 并行落账按 id 并集（185 行）重封链，views/handbook 生成物重生成，gate.py…
- 验证：gate validate 185 份链全绿；views check 一致；self-test OK（并发契约 + 防回潮用例）；handboo…

## 2026-11-20 · 合并 token/deploy 并行工作并移植门禁并发健壮性（chore `e94171d` `3501d3b` `8145b76` `b9add58` `415b525` `3f97378` `b7aaa93` `43eb19c` `8139279` `35611ee` `c8aaf93` `d630a8c` `e6c9349` `363eccd` `a533133` `77b14fe`）
- 问题：并行分支 81392796 的门禁并发修复写在旧归档模型上，其合并把旧模型整套带回（gate.py 回退、172 份 manifest、346 …
- 方案：模型保持 ledger：172 份按 id 吸收（175 行）、复活散文删除、views 重生成、链重排；把并发修复移植到 ledger 版 g…
- 验证：gate self-test OK（含 PID 判活/僵尸锁回收/活锁不误删）；validate 175 份链全绿；views check 一致…

## 2026-11-20 · 会话能力页增加内置工具展示并改造界面与市场多源接入（feat `a381003` `df34598` `59f07ad` `bd18e87` `7296fc8` `b9add58` `415b525` `43eb19c` `8139279` `35611ee`）
- 问题：会话能力页缺少内置工具展示、界面信息层级差、MCP/Skill 市场仅单一仓库来源
- 方案：能力页新增内置工具区（复用 AgentToolCatalog 投影）并重做三卡概览界面；
- 验证：独立跑通 spec-checks 35 例、市场相关 10 例、前端 13 个测试文件 23 例、vue-tsc 类型检查全绿；

## 2026-11-20 · 同步 0.1 分支到 0.2 分支（合并 + 重封归档链）（chore `dbeb728` `fe0b85f` `2a4591d` `cfaf487` `ac40c70` `6a8d0b9` `a054113` `9b96b52` `d6d5f25` `60accb8` `d2ce0ba` `0ef4309` `a0d886b` `f231e12` `ad02dad` `a48c78e` `6a0c837` `48a8c15` `501439d` `3e39c2c` `a3e360a` `b33cb9e` `239ee27` `ce82776` `c21e04a` `e2e69b6` `0a04b9c` `d9c2541` `25c7716` `f0a5776` `1b998b8` `1f84d49` `ab86d4e` `cc2256c` `c16ed3d` `d3cb637` `83c702f` `3f42162` `e92e7a0` `bea5a2a` `6acdb24` `b0124a9` `d103bd6` `fd01545` `d70eb9f` `16c6f4c` `0054f5f` `fd2636d` `e788458` `2674270` `ce30f40` `a368565` `3981a7c` `591cffc` `b6a0ca8` `7573c5c` `fafb765` `03f5aee` `f380411` `3bccf5c` `cc48ad1` `4c91eed` `2b50269` `976815b` `6b132e9` `7e41654` `d1e15aa` `e72ff5d` `70a395c` `fd26ec8` `c079f5d` `e57b713` `d7b3cf7` `6142e67` `631b391` `d39c02f` `da6cfff` `d1bc7cc` `b519605` `da667d4` `4656464` `c1276b1` `fc8eab2` `0e1f446` `97df414` `e1583e5` `9abc943` `616e7d1` `b1a8628` `e9a39d3`）
- 问题：0.2 线停在 11-19，落后 0.1 线 98 个提交；两条线各自维护归档链，直接合会断链，handbook manifest 也是生成物。
- 方案：在 0.2 线执行 merge 0.1.0-snapshot：6 个 feat manifest 与 handbook manifest 的冲突…
- 验证：gate validate 通过（170 份归档）；handbook check 通过（11 锚点）；reactor test-compile …

## 2026-11-20 · 0.1.1 OPEN bug 批量修复（27+3 条）（bug `6acdb24`）
- 问题：PG 工单 30 条 OPEN BUG（0.1.1 的 27 条 + 无版本 3 条）逐一按根因分析修复
- 方案：7 个并行修复域：工具面收敛（28/29/30/31）、执行器（32/35/38/39/41/46）、进程管理（40/42/43）、gitiss…
- 验证：agent-domain 97 / agent-infra 162 / agent-executor 258 / agent-tools 85 …

## 2026-11-19 · 同步 0.1.0-snapshot 至 0.2.0-snapshot（chore `c7e483a` `232abdb` `ce48355` `fce29f9` `a03abed` `0bf7e38` `5789f3a` `12c6e66` `8249307` `a554923` `59dcd04` `a9335e8` `1eaaa28` `96baa9b` `e413c8f` `de14f98` `3f6d209` `3de2084` `7672d97` `02ddb88` `53293d9` `19bd8dd` `ee6c078` `a3c57e2` `028d277` `766a758` `d69773e` `3cd35a0` `7b34195` `d10be49` `e762f32` `3e38d12` `b9fab0d` `0383892` `15667f5` `abe33a1` `505bff7` `1104fb9` `c327cc1` `2e8b205` `4de0459` `70a808f` `f7c710d` `4fe1c13` `35188f6` `636a7ce` `ef5fb2b` `bcaa271` `3225462` `a24ee2e` `0f6b8f6` `172305f` `218ff86`）
- 问题：同步 0.1.0-snapshot 至 0.2.0-snapshot
- 方案：fix(process): 重排 chore 归档链，修复 2059 归档断链
- 验证：冲突融合（ci.yml/gate.py）+ OpenAPI 快照重生成 + 归档链全量 reseal（19 份）+ handbook 重锚点；

## 2026-11-19 · 修部署失败：拷错 jar 导致无 Main-Class，且容器内沙箱模式不可用（bug `b9fab0d` `0383892`）
- 问题：run-0d8832ee 在 health 阶段失败（退出码 1 / E-401，body 为空），容器日志 no main manifest …
- 方案：仓库 Dockerfile 的 COPY 锁定 *-exec.jar 并写明原因，同时显式设置 AGENT_SANDBOX_MODE=proce…
- 验证：真实 docker build 成功、docker run 后 Up，GET /actuator/health 返回 HTTP 200 且 st…

## 2026-11-19 · BUG-8 任务查询输出字段契约回归（chore `b31ee17`）
- 问题：BUG-8（GitIssueQueryTools 输出丢失 version）：字段本身已在批次 2 随任务版本读写一起补上（get 带 vers…
- 方案：新增 GitIssueQueryToolsOutputTest（3 个用例）：get_git_issue 必须同时输出 code / versi…
- 验证：mvn -pl agent-tools test：61 用例通过（含新增 3 个）

## 2026-11-19 · BUG-17 新增只读变更状态查询工具（feat `ab9280a` `f929bfe`）
- 问题：BUG-17（变更单无状态字段）现象仍成立但根因已过半：ChangePlan 早就有 status 枚举，ChangeController 的 …
- 方案：新增只读工具 change_status(change_id)：readOnly + concurrencySafe，不推卡、不签发令牌、不执行…
- 验证：mvn -pl agent-tools test：58 用例通过（含新增 4 个）；

## 2026-11-19 · 调度会话可汇总子会话结论（feat `c8d8b71`）
- 问题：task-de956c65b7（工作区助手 super agent 搭建，实为 FEAT）：supervisor 已经能 conversatio…
- 方案：新增 ChildDigest 记录（会话 id / 标题 / 会话状态 / 最近运行 / 结论 / 错误 / 目标 / 更新时间），一行摘要优先…
- 验证：Tests run: 194, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-19 · 部署支持复用已构建产物（feat `d26309e`）
- 问题：task-5fdf460ff6（为何不能复用前面产物部署）：部署链路没有「复用已构建产物」的入口，为改一个部署参数或验证同一份产物都要重新跑一遍…
- 方案：PipelineEngine 新增 reusableBuild(runId)：只有成功运行且真的产出镜像产物才算可复用，不可用时带回原因；dep…
- 验证：Tests run: 315, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-18 · 任务版本与标签可读可选取，部分更新不丢字段（feat `ff496d1`）
- 问题：task-605d5e5fcd / task-67bd7174cf / task-a1074891b7 的共同根：任务关联版本号与标签写不进读不…
- 方案：新增 GET /api/v1/git-issues/versions，返回当前分支、按分支版本线推导的建议版本与仓库标签，任务栏与 Agent …
- 验证：Tests run: 191, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-18 · 补丁守卫放行 src-main 并返回结构化拒绝（feat `4e8ddfb`）
- 问题：task-9779e75d91（P0 真阻塞）：propose_patch 的路径守卫白名单过窄，agent 连自己仓库的 src/main 都…
- 方案：把业务源码 src/main/** 与测试代码 src/test/** 纳入允许清单；新增拒绝清单（优先级高于允许）覆盖 .git/、targe…
- 验证：Tests run: 424, Failures: 0, Errors: 0, Skipped: 3

## 2026-11-18 · 门禁加固：CI、规则自测、覆盖率闸门、前端自检（chore `4efd514`）
- 问题：门禁加固：CI、规则自测、覆盖率闸门、前端自检
- 方案：feat(gate): 真实 CI、规则 fixture 自测、覆盖率闸门与前端自检
- 验证：全量 mvn verify 144.7s rc=0（含 jacoco check，All coverage checks have been m…

## 2026-11-18 · 批次4：Agent 写权限（会话独立 worktree + 分支级一次确认）（feat `a6006e3`）
- 问题：Agent 只有白名单补丁 propose_patch，无法编辑业务代码；直接开放写文件会让模型改动用户当前分支且缺少人工确认。
- 方案：新增 SessionWorkspaceEditor 端口 + infra 实现：每会话独立 worktree 与 agent/session-*…
- 验证：定向用例：EditorToolsTest 8、SessionWorktreeEditorTest 3（infra 全量 119 通过）、Chan…

## 2026-11-18 · 合并 origin/0.1.0-snapshot 并同步 handbook 锚点（docs `115e5c5` `b6b4c64` `134fbe7` `20f1457` `b90a76c`）
- 问题：本地与 origin/0.1.0-snapshot 分叉（本地 7 个提交 / 远端 5 个提交），需要先合并再推送
- 方案：合并 origin/0.1.0-snapshot，解决 handbook manifest 冲突并重建锚点；
- 验证：Tests run: 698, Failures: 0, Errors: 0, Skipped: 1

## 2026-11-18 · 0.1.0 遗留缺陷分批修复（bug `134fbe7` `20f1457`）
- 问题：0.1.0 遗留缺陷：面板选择 docker 部署，重放/生产提升时 deployTarget 未透传，实际按固化脚本 host 执行；
- 方案：deployTarget 从前端→网关→引擎全链路下发并校验白名单，固化脚本写回实际目标；
- 验证：Tests run: 647, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-18 · 合并远端快照并修复 HostDeployerTest 跨平台依赖（chore `898c6e0` `412043c` `c274cc8` `c6a2e4a` `8ef1466` `d8ce889` `769dd14` `4b1bc6c` `0f65030` `d34a378` `6a899f6` `7f9294c` `2702dec` `52ef7e5` `dd45b7c` `36cb5a9` `d2846c3` `aa2a6a8` `a83d4ff` `97f91d6` `f816a5d` `e6380aa` `5c69021` `dd8fd33` `8a19091` `854f4cc`）
- 问题：本地 0.1.0-snapshot 与远端分叉（本地 9 个持久化提交，远端 26 个沙箱/变更卡提交），直接 push 会被拒绝；且两处改动在…
- 方案：merge origin/0.1.0-snapshot：application.yml 两侧改动合并；ChangeRegistry 以 JPA …
- 验证：合并后全量：602 用例，0 失败 0 错误 4 跳过（排除 4 个依赖 Docker/历史归档/Windows 的环境用例）。

## 2026-11-18 · 镜像构建切换到 BuildKit(buildx)（feat `a83d4ff` `97f91d6`）
- 问题：镜像打包走宿主 docker build（runc 冷容器、无 BuildKit 缓存、构建不进沙箱），且配方三处硬编码不一致
- 方案：image_package 统一改为 docker buildx build --load：内置配方、脚手架、Dockerfile 自动补齐三处…
- 验证：mvn -B -pl spec-checks -am test 全绿 610 例；

## 2026-11-17 · 补录：变更卡状态反馈（amend 后 SHA 变更）（bug `898c6e0`）
- 问题：变更卡状态反馈会话归档记录的是 amend 前的提交 SHA(eaf56bc9)，amend 后实际提交为 898c6e0，导致归档校验把 89…
- 方案：按实际提交 898c6e0 重新计算文件 blobHash 并补一份归档 manifest，接在原归档的链上；
- 验证：git diff-tree 898c6e0 与归档记录文件树一致；

## 2026-11-17 · 流水线 verify 续跑退化与自愈可靠性修复（bug `c6a2e4a` `8ef1466`）
- 问题：流水线 verify 续跑退化与自愈可靠性修复
- 方案：fix(autofix): 变量齐全的绑定自动确认，工具失败不再记成功
- 验证：Tests run: 601, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 变更卡：留痕单不推卡、失败回执与状态查询（bug `898c6e0`）
- 问题：确认卡链路三处口径不一致：留痕单(env_ensure/deploy_save_recipe/env_mapping_draft)也会弹可执行卡…
- 方案：ChangePlan 增加 executable() 与 status(PENDING/CONFIRMED/APPLIED/FAILED) 并持…
- 验证：worktree 干净基线 pre-commit 全量 spec-checks 测试通过；

## 2026-11-17 · 任务关联版本号并在看板展示（bug `b9d8063`）
- 问题：任务看板的任务没有关联版本号，无法区分任务属于当前版本还是计划到下个版本
- 方案：任务新增 version 字段并持久化：创建时默认取当前分支版本线的下一个补丁版本，编辑可改为目标版本；
- 验证：mvn -B -pl spec-checks -am test 通过（pre-commit 门禁）；

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

## 2026-11-17 · 工作区助手流不结束与 ask 选项卡片（bug `dbadfba` `4899aef` `965c8c1` `4b06e4f` `dd9d791`）
- 问题：工作区助手每轮对话结束必须手动点停止才能发下一条；
- 方案：运行终态（run.completed/failed/cancelled）到达即收尾并断开读流，工作区 SSE 在 AGENT_END 主动关流释…
- 验证：vue-tsc 类型检查通过；

## 2026-11-17 · 任务板统一到 GitIssue：优先级与量化看板（feat `fab91ab`）
- 问题：工作区右栏任务使用独立存储，与任务页 GitIssue 数据割裂；
- 方案：工作区任务统一读写 GitIssue，相关 Agent 工具改走 GitIssueService；
- 验证：Tests run: 524, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

## 2026-11-17 · 部署卡片历史/日志/重启、SSE 401 与状态存储修复（feat `8fc1168` `23ff830` `53f9009` `f51b161` `7207f81` `dfd6290` `3f60317`）
- 问题：部署卡片看不到历史、容器日志与重启入口；
- 方案：部署历史与 git 标签入库，卡片支持日志、重启与访问链接；
- 验证：Tests run: 502, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

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

