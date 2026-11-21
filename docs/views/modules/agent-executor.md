<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：agent-executor

61 个会话；检索用 `python tools/docs/find.py --module agent-executor`。

## 2026-11-21 · 修复 refactor 线引入的 A1 分层违规与 EC5 通用 throw（chore `7e02977`）
- 问题：并行会话的 refactor 线推送后 0.2 spec-checks 红：A1 分层——gateway 的 DeploymentControl…
- 方案：HostProcessSupervisor 移入 domain.process（executor 与 gateway 共同可达的唯一层，与 Lo…
- 验证：pre-commit 三模块全量 701 用例 PASSED（0 失败 5 跳过）；

## 2026-11-21 · 修复远端 refactor 引入的规约违规（A1 分层 / EC5 错误码 / R11 禁令）（chore `b908869`）
- 问题：合并 0.1 health 修复到 0.2 后，pre-push 门禁检测到远程 8 条 refactor 遗留的三处规约违规（A1 分层：Ho…
- 方案：A1：将 HostProcessSupervisor 从 executor 移到 domain.process（只依赖 JDK + domain…
- 验证：pre-commit 门禁跑 agent-domain/agent-executor/agent-gateway/spec-checks 共 1…

## 2026-11-21 · 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档（chore `d0aaa4d` `4bcbb32` `539d4ba` `dbfa7c2` `ca0a837` `e9e294a` `b6487a3` `781ba1f` `d4320a7` `cd05f58`）
- 问题：0.2.0-snapshot 上 8 个 refactor 提交（d0aaa4d4..781ba1f5）由并行会话推送时未走会话归档，pre-p…
- 方案：开 chore 会话把 baseHead 定在 c784ff18 补录 8 个 refactor 提交的归档缺口；
- 验证：pre-commit 门禁增量测试 PASSED（agent-executor: DockerActionRunnerPrepareTest/D…

## 2026-11-21 · 补归档：远端 0.2 八条 refactor 提交并入 0.1 修复收敛（chore `d0aaa4d` `4bcbb32` `539d4ba` `dbfa7c2` `ca0a837` `e9e294a` `b6487a3` `781ba1f` `d4320a7` `cd05f58`）
- 问题：trae 会话在远程 0.2 推送了 8 条 refactor 提交（d0aaa4d4..781ba1f5），未走会话归档门禁，导致 0.1 修…
- 方案：本会话补登记这 8 条远端 refactor 提交（从共同祖先 c784ff18 起），连同 deploy health 修复提交 d4320a…
- 验证：handbook check 10 锚点/10 份 L3 通过；docs/views check 16 文件一致；session-archive…

## 2026-11-21 · 修复部署 health 失败：沙箱 fail-closed 从启动期后移到执行期（bug `d4320a7`）
- 问题：部署平台自镜像后 health 阶段失败：容器内 agent.sandbox.mode=docker 但无 Docker daemon，Sand…
- 方案：将 daemon 可用性检查从启动期后移到执行期：SandboxConfig.dockerRunner 不再探测 daemon、仅装配 Dock…
- 验证：mvn -f platform/pom.xml -pl agent-executor -am test -Dsurefire.forkCount…

## 2026-11-21 · 沙箱与 Docker 机制缺陷修复与重复实现收敛（bug `e427338` `7c654cf`）
- 问题：沙箱执行面四类缺陷：隔离策略 require* 为零生产调用的死代码；交付镜像把 AGENT_SANDBOX_MODE 改成 process 拆…
- 方案：接线 require* 隔离策略并在起容器前 fail-closed；Dockerfile 改 dind sidecar + 非 root + …
- 验证：全平台 mvn -o verify 9/9 模块通过（agent-executor 321 绿）；pre-commit spec-checks …

## 2026-11-20 · 修复固化脚本重放路径丢失 version/tag 导致打 tag 未生效（bug `a2aa87e`）
- 问题：修复固化脚本重放路径丢失 version/tag 导致打 tag 未生效
- 方案：fix(deploy): 修复固化脚本重放丢失 version/tag 与部署地址指向 health JSON
- 验证：Tests run: 489, Failures: 0, Errors: 0, Skipped: 6

## 2026-11-20 · 0.1→0.2 同步：新归档模型（ledger/views/skills）带入 0.2 线（chore `ceef158` `a163612` `813904b` `342bfd9` `352fc30` `bb27e58` `c1fc559` `c455510` `a27fe76` `2c4af50` `a381003` `df34598` `59f07ad` `bd18e87` `7296fc8`）
- 问题：0.2 线还停在旧归档格式，两条线门禁模型不一致；合并时暴露迁移版本撞车：两条线各自发过 V5，Flyway 拒启（Found more tha…
- 方案：merge 0.1→0.2：39 个 manifest modify/delete 冲突以 0.2 版本覆盖同 id ledger 行后统一入 …
- 验证：gate validate 177 份链全绿；views/handbook check 一致；迁移锁 8/8；agent-gateway 226…

## 2026-11-20 · 部署地址指向/镜像保留策略/版本区分/环境页镜像面板（bug `813904b` `342bfd9`）
- 问题：四组问题：1)「打开部署地址」跳到 /actuator/health(JSON) 而不是前端页面；
- 方案：三个部署器端点改根路径；
- 验证：agent-executor 264+3 / agent-gateway 222 全绿；

## 2026-11-20 · 同步 0.1 分支到 0.2 分支（合并 + 重封归档链）（chore `dbeb728` `fe0b85f` `2a4591d` `cfaf487` `ac40c70` `6a8d0b9` `a054113` `9b96b52` `d6d5f25` `60accb8` `d2ce0ba` `0ef4309` `a0d886b` `f231e12` `ad02dad` `a48c78e` `6a0c837` `48a8c15` `501439d` `3e39c2c` `a3e360a` `b33cb9e` `239ee27` `ce82776` `c21e04a` `e2e69b6` `0a04b9c` `d9c2541` `25c7716` `f0a5776` `1b998b8` `1f84d49` `ab86d4e` `cc2256c` `c16ed3d` `d3cb637` `83c702f` `3f42162` `e92e7a0` `bea5a2a` `6acdb24` `b0124a9` `d103bd6` `fd01545` `d70eb9f` `16c6f4c` `0054f5f` `fd2636d` `e788458` `2674270` `ce30f40` `a368565` `3981a7c` `591cffc` `b6a0ca8` `7573c5c` `fafb765` `03f5aee` `f380411` `3bccf5c` `cc48ad1` `4c91eed` `2b50269` `976815b` `6b132e9` `7e41654` `d1e15aa` `e72ff5d` `70a395c` `fd26ec8` `c079f5d` `e57b713` `d7b3cf7` `6142e67` `631b391` `d39c02f` `da6cfff` `d1bc7cc` `b519605` `da667d4` `4656464` `c1276b1` `fc8eab2` `0e1f446` `97df414` `e1583e5` `9abc943` `616e7d1` `b1a8628` `e9a39d3`）
- 问题：0.2 线停在 11-19，落后 0.1 线 98 个提交；两条线各自维护归档链，直接合会断链，handbook manifest 也是生成物。
- 方案：在 0.2 线执行 merge 0.1.0-snapshot：6 个 feat manifest 与 handbook manifest 的冲突…
- 验证：gate validate 通过（170 份归档）；handbook check 通过（11 锚点）；reactor test-compile …

## 2026-11-20 · 固化脚本缺 deploy 阶段导致健康检查必败（bug `b519605`）
- 问题：固化脚本（RECIPE 源）有 health 阶段但没有 deploy 阶段时被当'可复现脚本'回放：run-2a5d03c9 / run-89…
- 方案：PipelineDefinitionResolver 新增 satisfiesDeployStage 门：health 有而 deploy 无 …
- 验证：PipelineDefinitionResolverTest 13/13 绿（含新增 recipeWithHealthButNoDeploySt…

## 2026-11-20 · forceTerminate 用例竞态：cancel 落在任务启动前，等 25s 必失败（bug `d7b3cf7` `6142e67`）
- 问题：PipelineEngineLifecycleTest.forceTerminateInterruptsThread 在 forkCount=4…
- 方案：定位为用例自身竞态：submit 后立刻 cancel(true) 却没有等任务开始，FutureTask 在启动前被 cancel 时 cal…
- 验证：修复后 PipelineEngineLifecycleTest 连续 5 次单跑全绿；executor 全量 -Dsurefire.forkCo…

## 2026-11-20 · 生产按版本部署：选项改为测试环境成功运行的版本（bug `fd26ec8`）
- 问题：生产按版本部署：选项改为测试环境成功运行的版本
- 方案：fix(deploy): 生产按版本部署的选项改为测试环境成功运行的版本
- 验证：mvn -pl agent-executor,agent-gateway -am test：247 tests 全过（新增 PipelineDe…

## 2026-11-20 · 镜像就绪状态可观测：只读接口暴露按主机维度的镜像就绪/退避（feat `6b132e9`）
- 问题：镜像拉取失败会落 image_readiness 并退避，但结论只写进运行日志：运维要翻历史才知道机器上哪几个镜像坏了、什么时候自动再试。
- 方案：ImageReadinessStore 增加 listAll()（Db 实现按 host 汇总）；新增只读接口 GET /api/v1/sett…
- 验证：agent-infra DbImageReadinessStoreTest 3 项通过；agent-gateway SandboxImageCo…

## 2026-11-20 · 镜像就绪自动收敛：进度感知拉取+退避重试+缓存源+运行前就绪（feat `cc48ad1` `4c91eed`）
- 问题：run-66c5c523 的 frontend 阶段以 E-302 失败：node:22-bookworm 八层全部 Download comp…
- 方案：把镜像就绪收敛成一套策略：ProcessExecutor.execUntilIdle 按有无新输出判卡死；ImageProvisioner 统一…
- 验证：agent-domain/agent-infra/agent-executor 全量单测 282 项通过；agent-gateway ToolC…

## 2026-11-20 · 0.1.1 OPEN bug 批量修复（27+3 条）（bug `6acdb24`）
- 问题：PG 工单 30 条 OPEN BUG（0.1.1 的 27 条 + 无版本 3 条）逐一按根因分析修复
- 方案：7 个并行修复域：工具面收敛（28/29/30/31）、执行器（32/35/38/39/41/46）、进程管理（40/42/43）、gitiss…
- 验证：agent-domain 97 / agent-infra 162 / agent-executor 258 / agent-tools 85 …

## 2026-11-20 · 修复中断标志泄漏导致回滚必挂；同项目同环境多提交并行构建（bug `c16ed3d`）
- 问题：修复中断标志泄漏导致回滚必挂；
- 方案：fix(pipeline): 中断标志不再泄漏进回滚 DB 读取；
- 验证：Tests run: 350, Failures: 0, Errors: 0, Skipped: 1

## 2026-11-19 · 同步 0.1.0-snapshot 至 0.2.0-snapshot（chore `c7e483a` `232abdb` `ce48355` `fce29f9` `a03abed` `0bf7e38` `5789f3a` `12c6e66` `8249307` `a554923` `59dcd04` `a9335e8` `1eaaa28` `96baa9b` `e413c8f` `de14f98` `3f6d209` `3de2084` `7672d97` `02ddb88` `53293d9` `19bd8dd` `ee6c078` `a3c57e2` `028d277` `766a758` `d69773e` `3cd35a0` `7b34195` `d10be49` `e762f32` `3e38d12` `b9fab0d` `0383892` `15667f5` `abe33a1` `505bff7` `1104fb9` `c327cc1` `2e8b205` `4de0459` `70a808f` `f7c710d` `4fe1c13` `35188f6` `636a7ce` `ef5fb2b` `bcaa271` `3225462` `a24ee2e` `0f6b8f6` `172305f` `218ff86`）
- 问题：同步 0.1.0-snapshot 至 0.2.0-snapshot
- 方案：fix(process): 重排 chore 归档链，修复 2059 归档断链
- 验证：冲突融合（ci.yml/gate.py）+ OpenAPI 快照重生成 + 归档链全量 reseal（19 份）+ handbook 重锚点；

## 2026-11-19 · BUG-1 缺 Dockerfile 的预检提示指向自动生成工具（bug `96baa9b`）
- 问题：BUG-1（流水线部署问题：遇到不同环境经常中断导致运行不成功）的最后一半。
- 方案：抽出 BuiltInPipelineEngine.missingDockerfileBlocker，两条预检路径（有镜像阶段缺文件、DOCKER…
- 验证：mvn -pl agent-executor test：200 用例通过（含新增 3 个）；

## 2026-11-19 · BUG-10 界面展示发布事实：首次发布与标签是否生效（feat `c7e483a` `232abdb` `ce48355` `fce29f9` `a03abed` `5789f3a` `12c6e66` `4fe1c13`）
- 问题：BUG-10/BUG-11/BUG-13 的后端部分此前已完成（tag 状态枚举、首次发布标记、日志行分页），但界面一处都没用：release{…
- 方案：PipelineView 头部补两个徽标：首次发布（tooltip 说明这不是错误）、标签状态（PUSHED 正常，LOCAL/FAILED/R…
- 验证：前端四套自检全部通过：agentEventReducer 38、toolArgs 20、releaseTag 14、pipelineLayout…

## 2026-11-19 · BUG-1 HOST 部署产物复制重试与占用提示（bug `fce29f9`）
- 问题：BUG-1（流水线部署问题：遇到不同环境经常中断导致运行不成功）。
- 方案：新增 copyReplacing：先复制到同目录 .tmp 再原子 move 到目标（避免中途失败在目标位置留下半个 jar，让下次启动拿到坏产…
- 验证：mvn -pl agent-executor test：197 用例通过（含新增 3 个，其中一条用真实文件锁跑重试路径）

## 2026-11-19 · 拦截跨平台绝对路径，堵住 stage.workdir 挂载逃逸（bug `538f0b8` `5f0e4cf` `bd0245b`）
- 问题：resolveWorkdir 只靠 resolved.startsWith(base) 判断越界，而它依赖当前平台是否把该写法认作绝对路径：wo…
- 方案：resolveWorkdir 增加 isAbsoluteAnyPlatform，在 resolve 之前显式拒绝任一端认为绝对的写法：POSIX…
- 验证：PipelineRunModeTest 12 用例全绿，覆盖相对路径上跳、中文目录名、./ 前缀、盘符、反斜杠盘符、盘符相对、POSIX 绝对、…

## 2026-11-19 · 修 CI 首次运行的 5 类失败（bug `ce7af61`）
- 问题：修 CI 首次运行的 5 类失败
- 方案：fix(ci): 修复首次 CI 运行的 5 类失败
- 验证：CI 日志定位 5 类失败并修复：Windows 专属测试加 @EnabledOnOs（Linux runner 不再跑）；doc-marsha…

## 2026-11-19 · 部署支持复用已构建产物（feat `d26309e`）
- 问题：task-5fdf460ff6（为何不能复用前面产物部署）：部署链路没有「复用已构建产物」的入口，为改一个部署参数或验证同一份产物都要重新跑一遍…
- 方案：PipelineEngine 新增 reusableBuild(runId)：只有成功运行且真的产出镜像产物才算可复用，不可用时带回原因；dep…
- 验证：Tests run: 315, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-19 · 首次发布标记与 tag 仅本地原因码（feat `9930e82`）
- 问题：task-6f3620f8f8（首次发布这里不对）与 task-bdaf4497fa（打 tag 未生效）：首次发布没有上一版时回滚预检把它当成…
- 方案：新增 ReleaseMode：首次发布是可显式标记的正常分支，回滚预检据此跳过并说明原因；新增 versionBranchConflict，版本…
- 验证：Tests run: 398, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-19 · 流水线日志支持行分页并返回 total/truncated（feat `bfdb821`）
- 问题：task-699cc7692d（流水线右侧无滚动条、日志不能下滚）：日志接口只按字节 offset 返回 text，前端拿不到「一共多少行」，既…
- 方案：PipelineEngine 新增 logLines(runId, offset, limit) 与 LogLinePage（offset/li…
- 验证：Tests run: 392, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-19 · 沙箱违规返回结构化 SANDBOX_DENIED（feat `6fa64cd`）
- 问题：task-4730f75e69（流水线沙箱脆弱且不安全）：隔离参数（gVisor、cap-drop ALL、no-new-privileges、…
- 方案：新增 SandboxDeniedException（固定 E-707 ENV_POLICY_DENIED），带 NETWORK/MOUNT/PR…
- 验证：Tests run: 246, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-18 · 门禁加固：CI、规则自测、覆盖率闸门、前端自检（chore `4efd514`）
- 问题：门禁加固：CI、规则自测、覆盖率闸门、前端自检
- 方案：feat(gate): 真实 CI、规则 fixture 自测、覆盖率闸门与前端自检
- 验证：全量 mvn verify 144.7s rc=0（含 jacoco check，All coverage checks have been m…

## 2026-11-18 · 门禁提速：提交只跑变更模块、测试类并行、慢测试分组（chore `18889e7`）
- 问题：门禁提速：提交只跑变更模块、测试类并行、慢测试分组
- 方案：perf(gate): 提交门禁只跑变更模块测试，测试并行与慢测试分组
- 验证：实测（16 核/JDK21/热态）：改 domain 的 pre-commit 171s→13.3s；gateway f=2 40.3s→27.…

## 2026-11-18 · 合并 origin/0.1.0-snapshot 并同步 handbook 锚点（docs `115e5c5` `b6b4c64` `134fbe7` `20f1457` `b90a76c`）
- 问题：本地与 origin/0.1.0-snapshot 分叉（本地 7 个提交 / 远端 5 个提交），需要先合并再推送
- 方案：合并 origin/0.1.0-snapshot，解决 handbook manifest 冲突并重建锚点；
- 验证：Tests run: 698, Failures: 0, Errors: 0, Skipped: 1

## 2026-11-18 · 修复流水线与测试红灯：缓存卷初始化、验收用例改走平台部署、SQLite 临时目录释放（bug `4350159`）
- 问题：本机已启动 Docker，但流水线/验收用例仍红：缓存卷初始化在 Windows userns 卷上用 root 写入被拒；PipelineAc…
- 方案：缓存卷初始化按 1000 → root 回退、chown 尽力而为、用 ErrorCode 代替硬编码 E-101；验收用例改用平台内置 ima…
- 验证：mvn -B -pl spec-checks -am test 全绿（655 用例，0 失败/0 错误/1 跳过）；

## 2026-11-18 · 0.1.0 遗留缺陷分批修复（bug `134fbe7` `20f1457`）
- 问题：0.1.0 遗留缺陷：面板选择 docker 部署，重放/生产提升时 deployTarget 未透传，实际按固化脚本 host 执行；
- 方案：deployTarget 从前端→网关→引擎全链路下发并校验白名单，固化脚本写回实际目标；
- 验证：Tests run: 647, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-18 · 合并远端快照并修复 HostDeployerTest 跨平台依赖（chore `898c6e0` `412043c` `c274cc8` `c6a2e4a` `8ef1466` `d8ce889` `769dd14` `4b1bc6c` `0f65030` `d34a378` `6a899f6` `7f9294c` `2702dec` `52ef7e5` `dd45b7c` `36cb5a9` `d2846c3` `aa2a6a8` `a83d4ff` `97f91d6` `f816a5d` `e6380aa` `5c69021` `dd8fd33` `8a19091` `854f4cc`）
- 问题：本地 0.1.0-snapshot 与远端分叉（本地 9 个持久化提交，远端 26 个沙箱/变更卡提交），直接 push 会被拒绝；且两处改动在…
- 方案：merge origin/0.1.0-snapshot：application.yml 两侧改动合并；ChangeRegistry 以 JPA …
- 验证：合并后全量：602 用例，0 失败 0 错误 4 跳过（排除 4 个依赖 Docker/历史归档/Windows 的环境用例）。

## 2026-11-18 · 流水线卡点加固：准备预算、缓存卷并发、健康检查窗口（feat `98ce5db` `c4ff5e1`）
- 问题：流水线存在三类卡点：(1) 准备阶段（镜像拉取/缓存卷初始化）耗时从预算扣掉后把剩余预算夹成 1ms，命令未执行就报'执行超时（0s）'，用户与…
- 方案：DockerActionRunner：准备阶段耗时进日志；准备耗尽预算时直接返回结构化 E-302（失败摘要写明'准备阶段已耗时 X 超过预算 …
- 验证：mvn -B -pl spec-checks -am test：619 例通过、0 失败、2 跳过（原 614，新增 5：准备预算耗尽原因、缓存…

## 2026-11-18 · Docker CLI 可切换为 podman（去 Docker Desktop 硬依赖）（feat `e6380aa` `5c69021` `dd8fd33`）
- 问题：沙箱外仍硬编码 docker：部署器/中间件自愈/运行时巡检/流水线引擎全部调用 docker CLI，换 Podman 或 rootless …
- 方案：抽出 DockerCli 作为可执行文件的唯一来源（agent.sandbox.docker-binary），PipelineConfig 注入…
- 验证：mvn -B -pl spec-checks -am test：614 例通过、0 失败；

## 2026-11-18 · 沙箱接入 gVisor 运行时并加固 root 初始化容器（feat `d2846c3`）
- 问题：沙箱只有 runc 进程级隔离且缓存卷初始化用构建镜像跑 root 容器（无 cap 限制、无网络限制），不可信代码与恶意镜像可逃逸
- 方案：新增 agent.sandbox.runtime 配置接入 gVisor 等运行时（docker run 加 --runtime）；
- 验证：DockerSandboxTest 8 例、DockerSandboxPrepareTest 12 例、SandboxConfigTest 3 …

## 2026-11-18 · 部署产物补证据链字段（feat `52ef7e5` `dd45b7c`）
- 问题：部署产物只记 target/resource，排查端口漂移、镜像与探活路径时要从日志反推
- 方案：deployment 产物 meta 固化实际端口、容器端口、探活路径、镜像（多服务再加 hostPort/containerPort/imag…
- 验证：新增 DeployStageTest.recordsDeploymentEvidence，断言产物 meta 的 port/containerP…

## 2026-11-18 · 健康检查补首页冒烟（bug `7f9294c`）
- 问题：健康检查只探后端接口，前端静态托管/反向代理配错导致首页 5xx 时发布仍被判成功
- 方案：健康接口与容器就绪后补一次首页冒烟：根路径 5xx 判失败，404/401/403 视为无首页，探活地址即根路径时跳过；
- 验证：PipelineRunModeTest 新增 3 例（5xx 失败/404 放行/根路径不重复请求），agent-executor 与 spec…

## 2026-11-18 · host 部署回收同槽遗留进程（bug `d34a378`）
- 问题：HOST 部署时上一次的进程可能残留（pid 文件丢失或被覆盖），旧实例继续占用端口，新进程绑定失败而健康检查打到旧进程
- 方案：槽内追加登记 pid 与启动时刻（app.pids），部署前按登记回收遗留进程；
- 验证：HostDeployerTest 3 项通过（含新增遗留进程回收用例）；

## 2026-11-18 · 前端构建超时：npm 镜像与 pnpm store 缓存（bug `769dd14`）
- 问题：run-50087672 前端阶段 E-302：pnpm install 冷下载（reused 0，2~43KiB/s，单请求 14~20s）占…
- 方案：DockerSandbox 注入 npm_config_registry（默认 npmmirror，可用 AGENT_SANDBOX_NPM_R…
- 验证：DockerSandboxTest/RecipeScaffolderTest/SandboxConfigTest 通过，网关编译通过；

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

## 2026-11-17 · 合并远端异常码迁移并适配版本发布改动（chore `4e1e0b9` `8b3dc1b`）
- 问题：推送前发现远端 main 新增「通用异常全量迁移」提交，与本地的版本发布改动在流水线引擎、PipelineController、Handbook…
- 方案：合并 origin/main 并逐处适配：新增的版本/分支/标签校验全部改用 BuildException(BUILD_INVALID_INPU…
- 验证：mvn -B -pl spec-checks -am test：572 个用例通过、0 失败（含 ErrorCodeContractTest 零…

## 2026-11-17 · 版本线与分支强绑定与快照发布（feat `8b00f39`）
- 问题：版本号与分支没有关系：流水线只能选提交、不能选分支，版本号是自由文本且允许带 v；标签不分基础版/补丁版，补丁发布与版本线无关，容易把 0.2.…
- 方案：新增 domain/release/ReleaseVersion：严格 x.y.z（不带 v）、版本线 x.y 映射长期分支 x.y.0-sna…
- 验证：新增并跑通 ReleaseVersionTest（4）、PipelineVersionPolicyTest（3）、PomVersionSyncT…

## 2026-11-17 · 异常码全量迁移与零基线门禁（feat `4e1e0b9`）
- 问题：生产代码仍有 641 处未带业务错误码的通用 throw，异常码改造不完整
- 方案：新增 ErrorKind 与各组 INVALID_INPUT/INVALID_STATE/INTERNAL 兜底码；
- 验证：Tests run: 543, Failures: 0, Errors: 0, Skipped: 4

## 2026-11-17 · 首次发布结果项展示与标签推送凭据修复（bug `729dbaf`）
- 问题：首次发布就绪清单把「首次发布」结果项当普通检查项渲染：该项 required=false，但没有成功发布记录时仍渲染红叉，出现「顶部显示可以发布…
- 方案：就绪清单：结果项改用中性信息图标（不影响发布），文案改为「尚无 test 的成功发布记录」并去掉发布入口，结果标签补充说明。
- 验证：新增 DefaultRepoLocatorTagTest：2 个用例通过（无 origin 时只落本地并给出原因、未知标签重推失败、仓库不存在返…

## 2026-11-17 · 异常码契约与提交门禁（chore `525fd71`）
- 问题：异常码改造不全：存在 throw new 通用异常(ErrorCode) 的写法，缺少按业务归类的异常类型与提交门禁
- 方案：新增 ErrorGroup/PlatformException 与 8 个分组业务异常，错误码与分组强绑定；
- 验证：mvn -pl spec-checks -am test（排除无 Docker 环境依赖的 DockerDaemonRepairTest/Dep…

## 2026-11-17 · 工作区助手流不结束与 ask 选项卡片（bug `dbadfba` `4899aef` `965c8c1` `4b06e4f` `dd9d791`）
- 问题：工作区助手每轮对话结束必须手动点停止才能发下一条；
- 方案：运行终态（run.completed/failed/cancelled）到达即收尾并断开读流，工作区 SSE 在 AGENT_END 主动关流释…
- 验证：vue-tsc 类型检查通过；

## 2026-11-17 · 任务看板 Unovis 重构与流水线构建修复（feat `47e354f` `dec8a2c` `adcbad8`）
- 问题：任务看板图表与真实任务页脱节且样式简陋，自动修复入口冗余；
- 方案：看板接入 Unovis（KPI 条、堆叠柱、四象限散点、环形图、面积图）并移除自动修复相关 UI/逻辑/样式；
- 验证：npm run typecheck/build 通过；

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

## 2026-11-17 · 流水线 tag 同步与构建历史编号修复（bug `bd3680c` `e99fc8f`）
- 问题：打包镜像模式不产生 git tag，流水线版本输入只能手填且无候选/冲突提示，构建历史序号在重跑后错乱
- 方案：打包镜像时同步创建 git tag；
- 验证：Tests run: 523, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 修复 docker 部署被降级 host / 回滚镜像缺失（bug `2b962bf`）
- 问题：docker 目标被静默降级为 host（固化脚本缺 image_package 却优先回放），回滚时 docker run 缺镜像只报 exi…
- 方案：固化脚本按部署目标自洽校验，过期脚本退回仓库/平台定义；
- 验证：mvn -B -pl spec-checks -am test 全部通过（含新增 staleRecipeWithoutImageStageIsS…

## 2026-11-17 · 环境矩阵显示构建版本号（bug `921ffca` `4a64f83`）
- 问题：工作区环境 Tab 的部署卡片只显示 latest，看不到构建版本号，无法判断当前部署对应哪次构建。
- 方案：流水线部署传给部署器的版本号从 deployVersion 改为 versionLabel：未打 tag 时回落到 <env>-v<seq>，H…
- 验证：Tests run: 519, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · Harness Handbook 三级文档体系（锚点生成 + 漂移门禁）（feat `7a1d78a`）
- 问题：工程文档只有零散的 spec 与 schema，改运行时行为时无法快速定位到源码；
- 方案：docs/handbook 三级结构（L1 总览/L2 阶段/L3 实现单元），L3 由代码内 @handbook 锚点脚本生成行为→源码位置表…
- 验证：Tests run: 507, Failures: 0, Errors: 0, Skipped: 2

