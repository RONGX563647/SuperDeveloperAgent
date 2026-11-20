<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：agent-infra

49 个会话；检索用 `python tools/docs/find.py --module agent-infra`。

## 2026-11-20 · 同步 0.1 分支到 0.2 分支（合并 + 重封归档链）（chore `dbeb728` `fe0b85f` `2a4591d` `cfaf487` `ac40c70` `6a8d0b9` `a054113` `9b96b52` `d6d5f25` `60accb8` `d2ce0ba` `0ef4309` `a0d886b` `f231e12` `ad02dad` `a48c78e` `6a0c837` `48a8c15` `501439d` `3e39c2c` `a3e360a` `b33cb9e` `239ee27` `ce82776` `c21e04a` `e2e69b6` `0a04b9c` `d9c2541` `25c7716` `f0a5776` `1b998b8` `1f84d49` `ab86d4e` `cc2256c` `c16ed3d` `d3cb637` `83c702f` `3f42162` `e92e7a0` `bea5a2a` `6acdb24` `b0124a9` `d103bd6` `fd01545` `d70eb9f` `16c6f4c` `0054f5f` `fd2636d` `e788458` `2674270` `ce30f40` `a368565` `3981a7c` `591cffc` `b6a0ca8` `7573c5c` `fafb765` `03f5aee` `f380411` `3bccf5c` `cc48ad1` `4c91eed` `2b50269` `976815b` `6b132e9` `7e41654` `d1e15aa` `e72ff5d` `70a395c` `fd26ec8` `c079f5d` `e57b713` `d7b3cf7` `6142e67` `631b391` `d39c02f` `da6cfff` `d1bc7cc` `b519605` `da667d4` `4656464` `c1276b1` `fc8eab2` `0e1f446` `97df414` `e1583e5` `9abc943` `616e7d1` `b1a8628` `e9a39d3`）
- 问题：0.2 线停在 11-19，落后 0.1 线 98 个提交；两条线各自维护归档链，直接合会断链，handbook manifest 也是生成物。
- 方案：在 0.2 线执行 merge 0.1.0-snapshot：6 个 feat manifest 与 handbook manifest 的冲突…
- 验证：gate validate 通过（170 份归档）；handbook check 通过（11 锚点）；reactor test-compile …

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

## 2026-11-19 · FEAT-4 新建任务标题自动带任务编码（feat `028d277`）
- 问题：FEAT-4（任务）第 1 项「新建任务标题需要带任务编码，git 提交和存档使用此编码作为标题」只做了一半：归档目录已经支持 --task-c…
- 方案：在 SqliteGitIssueStore.save 里，创建路径把分配到的编码写进标题（withCodePrefix），读回与返回值都带；更新…
- 验证：mvn -pl agent-infra test：7 用例通过；

## 2026-11-19 · 工作区角色 Worker 与事件接入（feat `19bd56e`）
- 问题：工作区角色 Worker 与事件接入
- 方案：feat: 工作区角色Worker与Gitee事件接入
- 验证：Tests run: 33, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-19 · 环境绑定历史可查询，解绑保留记录（feat `e7a886e`）
- 问题：task-2ee7375068：环境卡片只回当前绑定，历史绑定记录既没持久化查询接口、也没有解绑留痕——用户切换或解绑一次后，就再也看不到上次绑…
- 方案：EnvironmentStore 增加 bindings(projectId, env, limit) 全量查询（含草稿/已确认/过期/已解绑，…
- 验证：Tests run: 327, Failures: 0, Errors: 0, Skipped: 3

## 2026-11-18 · 补丁守卫放行 src-main 并返回结构化拒绝（feat `4e8ddfb`）
- 问题：task-9779e75d91（P0 真阻塞）：propose_patch 的路径守卫白名单过窄，agent 连自己仓库的 src/main 都…
- 方案：把业务源码 src/main/** 与测试代码 src/test/** 纳入允许清单；新增拒绝清单（优先级高于允许）覆盖 .git/、targe…
- 验证：Tests run: 424, Failures: 0, Errors: 0, Skipped: 3

## 2026-11-18 · 数据库后端热重载并移除旧库导入（feat `e9a6461` `6b1d82b` `06e1811` `612e675` `3a12b86`）
- 问题：数据库后端切换只能改配置重启，运行中无法换库；同时旧库导入（8 个历史分域 .db）这条需求已取消，但代码仍散落在启动装配、REST 接口、前端…
- 方案：PlatformDb 内部改为 volatile Holder（DataSource+EMF）承载真实后端，read/inTx 每次取当前后端，…
- 验证：提交后全量门禁 685 用例通过（失败 0 错误 0 跳过 3）；agent-infra 140、agent-gateway 157、spec-…

## 2026-11-18 · 门禁加固：CI、规则自测、覆盖率闸门、前端自检（chore `4efd514`）
- 问题：门禁加固：CI、规则自测、覆盖率闸门、前端自检
- 方案：feat(gate): 真实 CI、规则 fixture 自测、覆盖率闸门与前端自检
- 验证：全量 mvn verify 144.7s rc=0（含 jacoco check，All coverage checks have been m…

## 2026-11-18 · 旧库导入从启动链路移到设置页显式操作（feat `b490f43`）
- 问题：启动日志每次都在打「旧库一次性导入完成 ... 新增 N 行」：DataSourceConfig 用 @Bean(initMethod=run)…
- 方案：LegacySqliteImporter 去掉自动执行的 run()，拆成 inspect() 只读巡检（文件/大小/是否已导入/上次导入行数）…
- 验证：agent-infra 142 用例（含 LegacySqliteImporterTest 7、DataSourceConfigTest 3 其…

## 2026-11-18 · 门禁提速：提交只跑变更模块、测试类并行、慢测试分组（chore `18889e7`）
- 问题：门禁提速：提交只跑变更模块、测试类并行、慢测试分组
- 方案：perf(gate): 提交门禁只跑变更模块测试，测试并行与慢测试分组
- 验证：实测（16 核/JDK21/热态）：改 domain 的 pre-commit 171s→13.3s；gateway f=2 40.3s→27.…

## 2026-11-18 · 设置页配置 PostgreSQL 并一键迁移 SQLite 数据（feat `82a5708`）
- 问题：设置页无法配置 PostgreSQL，SQLite 数据也没有迁移通道：切库只能改环境变量重启，历史数据要靠手工导出导入。
- 方案：新增 db-backend.yml 后端配置（密码 AES-256-GCM 密文落盘，启动时解密注入系统属性，环境变量优先）；SqliteToT…
- 验证：agent-infra 137 + agent-gateway 153（提交门禁实测 246 用例，失败 0、错误 0、跳过 2）；spec-c…

## 2026-11-18 · spec-checks 编码读取加固与迁移基线补录；Db* 存储改名收尾与 Flyway 统一迁移（chore `a788ae3` `792c4fd`）
- 问题：上一提交只把 Sqlite* 存储实现文件改名成 Db*，正文类名/构造器/静态引用仍是 Sqlite*，编译与 Spring 装配对不上；同时…
- 方案：补完 Db* 改名的正文（类名、构造器、静态方法引用、对应测试）；agent-infra 新增 Flyway 迁移 V1 建表 / V2 自增表…
- 验证：pre-commit 门禁 mvn -B -pl agent-gateway,agent-infra,!spec-checks -amd tes…

## 2026-11-18 · SQLite 数据丢失：WAL 未落盘与旧库导入默认关闭（bug `b3bbbea` `de813e7` `319ac71`）
- 问题：SQLite 切库后旧库 WAL 未 checkpoint 落盘、且 importLegacy 默认关闭，导致历史数据（run_ledger/t…
- 方案：新增 SqliteWalCheckpointer 启动即对统一库执行 wal_checkpoint(TRUNCATE)；
- 验证：增量测试 8 用例全绿（SqliteWalCheckpointerTest/LegacySqliteImporterTest/AgentDbPr…

## 2026-11-18 · 会话提交改用独立 index（并行 worktree 不争锁、提交后状态干净）（bug `75fd4ed`）
- 问题：多会话共享 bare 仓库时，会话工作树用普通 git add -A/commit 会争用 .git/index.lock；且提交后真实 ind…
- 方案：GitWorktreeManager 新增 commitOnly：临时 index + GIT_INDEX_FILE 执行 read-tree …
- 验证：定向：GitWorktreeManagerTest 13（含新增 commitOnly 用例：未跟踪文件入库、hook 被绕过、提交后 stat…

## 2026-11-18 · 批次4：Agent 写权限（会话独立 worktree + 分支级一次确认）（feat `a6006e3`）
- 问题：Agent 只有白名单补丁 propose_patch，无法编辑业务代码；直接开放写文件会让模型改动用户当前分支且缺少人工确认。
- 方案：新增 SessionWorkspaceEditor 端口 + infra 实现：每会话独立 worktree 与 agent/session-*…
- 验证：定向用例：EditorToolsTest 8、SessionWorktreeEditorTest 3（infra 全量 119 通过）、Chan…

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

## 2026-11-18 · Phase 2：全部 Store 迁移到 JPA 统一持久化（feat `4dd3af6`）
- 问题：持久化层写死 SQLite：18 个 Store 各自打开分域 .db 文件并手写连接池/事务/DDL，无法通过配置切换 PostgreSQL；…
- 方案：43 张表全部映射为 @Entity（含 9 个复合主键 @IdClass.Key），Store 统一通过 PlatformDb / Gover…
- 验证：mvn -B test -Djacoco.skip=true（排除 4 个依赖 Docker/历史归档的环境用例）：570 用例，0 失败 0 …

## 2026-11-18 · 引入 JPA 统一 SQLite 与 PostgreSQL 持久化（feat `e13d0ea` `69c336a` `7b94e92`）
- 问题：持久化层写死 SQLite：18 个 Store 直接打开 .db 文件，连接池、schema 初始化和事务均手写，无法通过配置切换到 Post…
- 方案：引入 Spring Data JPA + Hibernate 7 + HikariCP 作为统一 ORM 基座。
- 验证：567 个用例通过（排除 1 个依赖完整 git 历史的 ProcessArchiveTest、3 个依赖 Docker 的部署测试；本沙箱无 …

## 2026-11-18 · 镜像构建切换到 BuildKit(buildx)（feat `a83d4ff` `97f91d6`）
- 问题：镜像打包走宿主 docker build（runc 冷容器、无 BuildKit 缓存、构建不进沙箱），且配方三处硬编码不一致
- 方案：image_package 统一改为 docker buildx build --load：内置配方、脚手架、Dockerfile 自动补齐三处…
- 验证：mvn -B -pl spec-checks -am test 全绿 610 例；

## 2026-11-18 · 前端构建超时：npm 镜像与 pnpm store 缓存（bug `769dd14`）
- 问题：run-50087672 前端阶段 E-302：pnpm install 冷下载（reused 0，2~43KiB/s，单请求 14~20s）占…
- 方案：DockerSandbox 注入 npm_config_registry（默认 npmmirror，可用 AGENT_SANDBOX_NPM_R…
- 验证：DockerSandboxTest/RecipeScaffolderTest/SandboxConfigTest 通过，网关编译通过；

## 2026-11-17 · 流水线 verify 续跑退化与自愈可靠性修复（bug `c6a2e4a` `8ef1466`）
- 问题：流水线 verify 续跑退化与自愈可靠性修复
- 方案：fix(autofix): 变量齐全的绑定自动确认，工具失败不再记成功
- 验证：Tests run: 601, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 任务关联版本号并在看板展示（bug `b9d8063`）
- 问题：任务看板的任务没有关联版本号，无法区分任务属于当前版本还是计划到下个版本
- 方案：任务新增 version 字段并持久化：创建时默认取当前分支版本线的下一个补丁版本，编辑可改为目标版本；
- 验证：mvn -B -pl spec-checks -am test 通过（pre-commit 门禁）；

## 2026-11-17 · 修复项目健康检查与流水线预检的 Dockerfile 判定冲突（bug `c80a2f4`）
- 问题：项目接入自检说可发布，但流水线预检（ci_dry_run）因 image 阶段缺 Dockerfile 报 E-501；两条链路判定口径不一致，…
- 方案：自检改为与预检同口径：有 image_package/type=image 阶段且缺 Dockerfile 即阻塞；补充 inline map …
- 验证：RecipeCheckTest 8 个用例通过（新增 C6-C8）；spec-checks 580 例通过（临时排除并行会话未完成的 Sqlit…

## 2026-11-17 · 合并远端异常码迁移并适配版本发布改动（chore `4e1e0b9` `8b3dc1b`）
- 问题：推送前发现远端 main 新增「通用异常全量迁移」提交，与本地的版本发布改动在流水线引擎、PipelineController、Handbook…
- 方案：合并 origin/main 并逐处适配：新增的版本/分支/标签校验全部改用 BuildException(BUILD_INVALID_INPU…
- 验证：mvn -B -pl spec-checks -am test：572 个用例通过、0 失败（含 ErrorCodeContractTest 零…

## 2026-11-17 · 任务编码生成并参与修复分支（feat `2b02714`）
- 问题：平台任务只能看到内部 id（task-xxxx）与远端编号，没有稳定、可读、可口头引用的编码；自动修复分支是 agent/bugfix-<随机串…
- 方案：GitIssue 增加 code 字段：项目内按分类自增（FEAT-1 / BUG-2），创建时由 SQLite 存储统一分配、更新时不可变；老…
- 验证：新增 SqliteGitIssueStoreCodeTest：按项目+分类分配（BUG-1/BUG-2/FEAT-1、项目隔离）、更新不改编码、…

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

## 2026-11-17 · 平台提交绕过仓库 hook 与静态页归档（chore `993fe1f` `e128636`）
- 问题：工作区剩余改动未分类提交；
- 方案：commitAll 加 --no-verify 让平台自动提交不执行目标仓库本地 hook，并补单测覆盖；
- 验证：GitWorktreeManagerTest 12 例通过；

## 2026-11-17 · 工作区助手流不结束与 ask 选项卡片（bug `dbadfba` `4899aef` `965c8c1` `4b06e4f` `dd9d791`）
- 问题：工作区助手每轮对话结束必须手动点停止才能发下一条；
- 方案：运行终态（run.completed/failed/cancelled）到达即收尾并断开读流，工作区 SSE 在 AGENT_END 主动关流释…
- 验证：vue-tsc 类型检查通过；

## 2026-11-17 · 能力栏与 Skill/MCP 设置可用性改造（feat `0e2041f`）
- 问题：能力栏与 Skill/MCP 设置不可观测、不可调优：MCP 看不到连接状态与工具清单，Skill 无法验证命中，能力栏缺少搜索筛选排序与 to…
- 方案：能力栏增加搜索/筛选/排序、token 估算、场景预设与设为默认；
- 验证：mvn -B -pl spec-checks -am test 通过（pre-commit 门禁）；

## 2026-11-17 · 任务板统一到 GitIssue：优先级与量化看板（feat `fab91ab`）
- 问题：工作区右栏任务使用独立存储，与任务页 GitIssue 数据割裂；
- 方案：工作区任务统一读写 GitIssue，相关 Agent 工具改走 GitIssueService；
- 验证：Tests run: 524, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

## 2026-11-17 · Worktree 清理、迭代预算与门禁修复（feat `dc67f9f` `cba449e` `4bee2bf` `89a1086`）
- 问题：半残 worktree 删除失败；
- 方案：forceRemoveWorktree 先 prune 再递归删除；
- 验证：Tests run: 503, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

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

## 2026-11-17 · 工具渐进披露与任务面板改版（feat `4b0a8c4` `ef7eaa8` `2b7dc5f` `b3fbfe7`）
- 问题：每轮对话都把 70+ 个工具 schema、技能全文与 MCP 工具塞进上下文，单次请求工具定义约占两万 token；上下文裁剪过于频繁，模型侧…
- 方案：工具按能力拆成插件，默认只暴露核心工具，其余用 tool_load 按需激活并写入会话状态；技能只给索引、正文用 skill_load 读取，M…
- 验证：Tests run: 516, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · Harness Handbook 三级文档体系（锚点生成 + 漂移门禁）（feat `7a1d78a`）
- 问题：工程文档只有零散的 spec 与 schema，改运行时行为时无法快速定位到源码；
- 方案：docs/handbook 三级结构（L1 总览/L2 阶段/L3 实现单元），L3 由代码内 @handbook 锚点脚本生成行为→源码位置表…
- 验证：Tests run: 507, Failures: 0, Errors: 0, Skipped: 2

