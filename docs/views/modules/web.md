<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：web

58 个会话；检索用 `python tools/docs/find.py --module web`。

## 2026-11-20 · 面板真实渲染校验脚本 + 清掉误生成的 npm 锁文件（chore `e1583e5`）
- 问题：「工具链镜像」面板只过了 build/typecheck/单测，接口字段到表格单元格这一段没有真浏览器验证；另外 platform/web/pa…
- 方案：新增 scripts/sandbox-images-check.mjs（登录 + 深链断言 3 行数据、列名、告警、汇总、退避倒计时、tag 类…
- 验证：隔离实例（8091，独立 DB，不干扰本机 8090）落 3 行真实数据后：11 项断言全绿，截图 1440x1000；清空表后空态断言同样通过…

## 2026-11-20 · 设置页新增工具链镜像面板（就绪/退避可视）（feat `da6cfff`）
- 问题：镜像就绪状态只有一个只读接口，界面上看不到：出问题时运维仍要 curl 或翻日志才知道哪台机器上哪个镜像坏了、还要等多久才重试。
- 方案：设置页新增「工具链镜像」面板（高级分区）：按 host+image 列状态/来源/尝试次数/下次重试/最后错误/更新时间，顶部汇总与失败告警说明…
- 验证：前端 11 个测试文件全部通过（含新增 imageReadiness 5 条断言）；npm run typecheck 通过；npm run b…

## 2026-11-20 · 生产按版本部署：选项改为测试环境成功运行的版本（bug `fd26ec8`）
- 问题：生产按版本部署：选项改为测试环境成功运行的版本
- 方案：fix(deploy): 生产按版本部署的选项改为测试环境成功运行的版本
- 验证：mvn -pl agent-executor,agent-gateway -am test：247 tests 全过（新增 PipelineDe…

## 2026-11-20 · 0.1.1 OPEN bug 批量修复（27+3 条）（bug `6acdb24`）
- 问题：PG 工单 30 条 OPEN BUG（0.1.1 的 27 条 + 无版本 3 条）逐一按根因分析修复
- 方案：7 个并行修复域：工具面收敛（28/29/30/31）、执行器（32/35/38/39/41/46）、进程管理（40/42/43）、gitiss…
- 验证：agent-domain 97 / agent-infra 162 / agent-executor 258 / agent-tools 85 …

## 2026-11-20 · BUG-26/53 环境条默认折叠与抖动修复（bug `e92e7a0`）
- 问题：环境悬浮条全局 localStorage 单 key 导致展开过一次永远弹出；
- 方案：fix(web): key 按 projectId+env 隔离；
- 验证：pnpm test 9 个测试文件全过（含新增 5 例）；

## 2026-11-19 · FEAT-5 用量面板显示各维度缓存命中率（feat `239ee27`）
- 问题：上一批只给 UsageSummary 加了总体命中率组件，三个维度仍是派生方法：Java 能调用、单测也过，但 springdoc 只序列化 r…
- 方案：把 DailyUsage / ModelUsageSummary / ProjectUsageSummary 的 cacheHitRate 改成…
- 验证：mvn -pl agent-domain test：12 用例通过（三个命中率测试类）；

## 2026-11-19 · 同步 schema.d.ts 的 xlsx 媒体类型（chore `a3e360a`）
- 问题：CI frontend 作业报 schema.d.ts 与 openapi 不一致：导出接口改为显式 produces 后，response 的…
- 方案：在干净工作树里用同一份 openapi.json 重新生成 schema.d.ts（仅 1 行 content key 变化），并提交。
- 验证：重新生成后 git diff 只有那 1 行；提交门禁走「前端自检 + 跳过 Maven」。

## 2026-11-19 · 合并基线并重排归档链（chore `dbeb728` `fe0b85f` `2a4591d` `cfaf487` `ac40c70` `a054113` `9b96b52` `60accb8` `d2ce0ba`）
- 问题：PR #28 与基线冲突（dirty）：并行会话在主线实现了等价的 405 诊断（BuildFreshnessCheck 运行期提示 + Glo…
- 方案：代码冲突取主线版本（功能等价，避免重复实现），删掉我这边重复的同名测试用例；对 feat/chore 两条归档链执行 reseal 收敛。
- 验证：编译通过；提交门禁 265 用例 0 失败；validate 136 份通过；导出功能（ExportData / XLSX_MEDIA_TYPE…

## 2026-11-19 · FEAT-5 汇总缓存命中率做成 OpenAPI 组件（feat `218ff86` `dbeb728` `fe0b85f`）
- 问题：FEAT-5 第 3 项「提升 KV cache 命中率」缺度量侧：数据齐全（cachedTokens 已入库、UsageTotals.cach…
- 方案：最终方案：命中率做成记录的一部分而不是派生方法或用 Map 重组。
- 验证：mvn -pl agent-domain,agent-gateway test：171 用例通过；

## 2026-11-19 · 任务栏筛选交互重做并支持导出 Excel（feat `3225462`）
- 问题：任务栏筛选条不好用：三行控件（状态分段按钮+类型下拉、优先级分段按钮+排序下拉、版本下拉）都没有标签，不点一遍猜不出各自管什么，占掉左栏可视高度…
- 方案：筛选条重做成一行自适应网格，每项带标签（状态/类型/优先级/版本/排序）；下方新增「已筛选」chips，单条可撤销加一键清空全部；删除随旧布局失…
- 验证：IssueExcelExporterTest 8 例（部件齐全/表头与数据/转义/空列表/控制字符/列号进位/文件名）、GitIssueCont…

## 2026-11-19 · BUG-10 把发布事实界面与复用产物入口并入主干（chore `4fe1c13` `35188f6` `636a7ce` `ef5fb2b`）
- 问题：BUG-10/BUG-11/BUG-12 的界面部分（发布事实徽标、复用产物部署入口）只落在 feat/release-facts-ui 分支上…
- 方案：把该分支 4 个提交 cherry-pick 到最新主干 06bb27d 之上，形成 fix/release-facts-ui-merge。
- 验证：vue-tsc 退出码 0；

## 2026-11-19 · 任务栏新增按版本号筛选（feat `abe33a1`）
- 问题：任务栏只能按状态/类型/优先级筛选，无法按关联版本号收敛——一个版本发布前想知道这个版本还剩哪些任务只能肉眼翻列表。
- 方案：新增 utils/taskVersionFilter.ts（normalizeVersion / compareVersion / filter…
- 验证：taskVersionFilter.test.ts 17 断言通过（含 0.1.10 必须排在 0.1.2 前、v 前缀归一、未关联归类、空列表…

## 2026-11-19 · FEAT-7 会话支持插图，环境面板默认收起（feat `3cd35a0`）
- 问题：FEAT-7（会话）两项都没做：1) 对话输入框的附件按钮被写死成 disabled（title=附件功能待接入），用户想贴一张截图说明问题只能…
- 方案：插图：点按钮选图、Ctrl+V 粘贴、拖入 三种入口都能上传（复用已有的 uploadIssueImage），上传后以 Markdown 图片语…
- 验证：vue-tsc 通过；

## 2026-11-19 · BUG-12 构建历史提供复用产物部署入口（feat `636a7ce`）
- 问题：BUG-12（为何不能复用前面产物部署）：后端 deploy_run 早已支持跳过构建（触发参数 deployImage、工具参数 build_…
- 方案：HistoryList 对 SUCCEEDED 的运行多一个「复用产物部署」入口（Van 图标，带 tooltip 说明跳过构建）；点击后取该次…
- 验证：vue-tsc 通过；

## 2026-11-19 · BUG-10 界面展示发布事实：首次发布与标签是否生效（feat `c7e483a` `232abdb` `ce48355` `fce29f9` `a03abed` `5789f3a` `12c6e66` `4fe1c13`）
- 问题：BUG-10/BUG-11/BUG-13 的后端部分此前已完成（tag 状态枚举、首次发布标记、日志行分页），但界面一处都没用：release{…
- 方案：PipelineView 头部补两个徽标：首次发布（tooltip 说明这不是错误）、标签状态（PUSHED 正常，LOCAL/FAILED/R…
- 验证：前端四套自检全部通过：agentEventReducer 38、toolArgs 20、releaseTag 14、pipelineLayout…

## 2026-11-19 · BUG-4 工具入参不可用在界面上与无参数区分开（bug `45a0832`）
- 问题：BUG-4（轨迹工具详情参数缺失）的界面一半：后端在批次 9 已把 argsUnavailable 下发下来，但两处界面都把它渲染成「无参数」（…
- 方案：抽出纯函数 utils/toolArgs.ts 统一表达口径，四种状态分开：present（有参原样）/ pending（运行中且暂无，显示等待…
- 验证：toolArgs.test.ts 20 passed；

## 2026-11-19 · 修 CI 第二轮失败：Node 版本、文档契约冲突、dev 依赖漏洞（chore `6f2d9a9`）
- 问题：修 CI 第二轮失败：Node 版本、文档契约冲突、dev 依赖漏洞
- 方案：fix(ci): Node 22、去掉冲突的 doc-marshal、dev 依赖漏洞清零
- 验证：本地 osv-scanner 复扫 Total 0 packages affected by 0 known vulnerabilities（m…

## 2026-11-19 · 修 CI 首次运行的 5 类失败（bug `ce7af61`）
- 问题：修 CI 首次运行的 5 类失败
- 方案：fix(ci): 修复首次 CI 运行的 5 类失败
- 验证：CI 日志定位 5 类失败并修复：Windows 专属测试加 @EnabledOnOs（Linux runner 不再跑）；doc-marsha…

## 2026-11-19 · 能力列表返回可用状态与不可用原因（feat `1614c1e`）
- 问题：task-46122095b6（会话能力 skill/mcp 不实用要大改）：能力接口只返回名字与 installed，用户装完在会话里发现用不…
- 方案：新增 CapabilityState 把四件事分开表达：enabled（用户是否开启）/ installed（是否已落地）/ available…
- 验证：Tests run: 217, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-19 · 轨迹工具详情补齐入参快照与不可用标记（feat `7bd8900`）
- 问题：task-da2bbce041（轨迹工具详情参数缺失）：上一轮只在 TOOL_CALL_DELTA 上把入参归一化成 args，插件类工具与 M…
- 方案：AgentEventMapper 把入参归一化扩展到 tool.started 与 tool.completed，按 args/argument…
- 验证：Tests run: 142, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-19 · 流水线日志支持行分页并返回 total/truncated（feat `bfdb821`）
- 问题：task-699cc7692d（流水线右侧无滚动条、日志不能下滚）：日志接口只按字节 offset 返回 text，前端拿不到「一共多少行」，既…
- 方案：PipelineEngine 新增 logLines(runId, offset, limit) 与 LogLinePage（offset/li…
- 验证：Tests run: 392, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-19 · 环境绑定历史可查询，解绑保留记录（feat `e7a886e`）
- 问题：task-2ee7375068：环境卡片只回当前绑定，历史绑定记录既没持久化查询接口、也没有解绑留痕——用户切换或解绑一次后，就再也看不到上次绑…
- 方案：EnvironmentStore 增加 bindings(projectId, env, limit) 全量查询（含草稿/已确认/过期/已解绑，…
- 验证：Tests run: 327, Failures: 0, Errors: 0, Skipped: 3

## 2026-11-18 · SSE 心跳改为命名事件并在静默超时主动下发 run.failed（feat `c2821ad`）
- 问题：task-76c9432989（对话流中断）与 task-90836fe8d5（发送按钮失效）：SSE 只发注释帧心跳，前端解码器把注释帧直接丢…
- 方案：心跳改成命名事件 heartbeat（带 alive 载荷）；新增静默看门狗，业务事件超过 agent.chat.stall-seconds 无…
- 验证：Tests run: 141, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-18 · 任务版本与标签可读可选取，部分更新不丢字段（feat `ff496d1`）
- 问题：task-605d5e5fcd / task-67bd7174cf / task-a1074891b7 的共同根：任务关联版本号与标签写不进读不…
- 方案：新增 GET /api/v1/git-issues/versions，返回当前分支、按分支版本线推导的建议版本与仓库标签，任务栏与 Agent …
- 验证：Tests run: 191, Failures: 0, Errors: 0, Skipped: 2

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

## 2026-11-18 · 设置页配置 PostgreSQL 并一键迁移 SQLite 数据（feat `82a5708`）
- 问题：设置页无法配置 PostgreSQL，SQLite 数据也没有迁移通道：切库只能改环境变量重启，历史数据要靠手工导出导入。
- 方案：新增 db-backend.yml 后端配置（密码 AES-256-GCM 密文落盘，启动时解密注入系统属性，环境变量优先）；SqliteToT…
- 验证：agent-infra 137 + agent-gateway 153（提交门禁实测 246 用例，失败 0、错误 0、跳过 2）；spec-c…

## 2026-11-18 · 合并 origin/0.1.0-snapshot 并同步 handbook 锚点（docs `115e5c5` `b6b4c64` `134fbe7` `20f1457` `b90a76c`）
- 问题：本地与 origin/0.1.0-snapshot 分叉（本地 7 个提交 / 远端 5 个提交），需要先合并再推送
- 方案：合并 origin/0.1.0-snapshot，解决 handbook manifest 冲突并重建锚点；
- 验证：Tests run: 698, Failures: 0, Errors: 0, Skipped: 1

## 2026-11-18 · 0.1.0 遗留缺陷分批修复（bug `134fbe7` `20f1457`）
- 问题：0.1.0 遗留缺陷：面板选择 docker 部署，重放/生产提升时 deployTarget 未透传，实际按固化脚本 host 执行；
- 方案：deployTarget 从前端→网关→引擎全链路下发并校验白名单，固化脚本写回实际目标；
- 验证：Tests run: 647, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-18 · 流水线右侧日志被裁掉，缺少滚动（bug `8bb486b`）
- 问题：流水线运行详情列用 overflow:hidden，产物/镜像面板一多就把日志区挤出可视区且无法滚动，日志尾部看不见
- 方案：run-detail 改为 overflow-y:auto + overscroll-behavior:contain，日志区 flex:1 1…
- 验证：node --experimental-strip-types platform/web/src/views/pipelineLayout.te…

## 2026-11-18 · 合并远端快照并修复 HostDeployerTest 跨平台依赖（chore `898c6e0` `412043c` `c274cc8` `c6a2e4a` `8ef1466` `d8ce889` `769dd14` `4b1bc6c` `0f65030` `d34a378` `6a899f6` `7f9294c` `2702dec` `52ef7e5` `dd45b7c` `36cb5a9` `d2846c3` `aa2a6a8` `a83d4ff` `97f91d6` `f816a5d` `e6380aa` `5c69021` `dd8fd33` `8a19091` `854f4cc`）
- 问题：本地 0.1.0-snapshot 与远端分叉（本地 9 个持久化提交，远端 26 个沙箱/变更卡提交），直接 push 会被拒绝；且两处改动在…
- 方案：merge origin/0.1.0-snapshot：application.yml 两侧改动合并；ChangeRegistry 以 JPA …
- 验证：合并后全量：602 用例，0 失败 0 错误 4 跳过（排除 4 个依赖 Docker/历史归档/Windows 的环境用例）。

## 2026-11-18 · 前端构建超时：npm 镜像与 pnpm store 缓存（bug `769dd14`）
- 问题：run-50087672 前端阶段 E-302：pnpm install 冷下载（reused 0，2~43KiB/s，单请求 14~20s）占…
- 方案：DockerSandbox 注入 npm_config_registry（默认 npmmirror，可用 AGENT_SANDBOX_NPM_R…
- 验证：DockerSandboxTest/RecipeScaffolderTest/SandboxConfigTest 通过，网关编译通过；

## 2026-11-17 · 补录：变更卡状态反馈（amend 后 SHA 变更）（bug `898c6e0`）
- 问题：变更卡状态反馈会话归档记录的是 amend 前的提交 SHA(eaf56bc9)，amend 后实际提交为 898c6e0，导致归档校验把 89…
- 方案：按实际提交 898c6e0 重新计算文件 blobHash 并补一份归档 manifest，接在原归档的链上；
- 验证：git diff-tree 898c6e0 与归档记录文件树一致；

## 2026-11-17 · 变更卡：留痕单不推卡、失败回执与状态查询（bug `898c6e0`）
- 问题：确认卡链路三处口径不一致：留痕单(env_ensure/deploy_save_recipe/env_mapping_draft)也会弹可执行卡…
- 方案：ChangePlan 增加 executable() 与 status(PENDING/CONFIRMED/APPLIED/FAILED) 并持…
- 验证：worktree 干净基线 pre-commit 全量 spec-checks 测试通过；

## 2026-11-17 · 工具结果 JSON 序列化还原，修复确认卡不出现（bug `5699a53`）
- 问题：AgentScope 会把工具返回的 String 再 JSON 序列化一次，换行变成字面量 \\n，导致 ChangeWire/EnvBind…
- 方案：新增 ToolResultText 还原 JSON 字符串字面量（含转义解码），两处 wire 解析先还原再匹配；
- 验证：mvn -B -pl spec-checks -am test 通过（pre-commit 门禁）；

## 2026-11-17 · 任务关联版本号并在看板展示（bug `b9d8063`）
- 问题：任务看板的任务没有关联版本号，无法区分任务属于当前版本还是计划到下个版本
- 方案：任务新增 version 字段并持久化：创建时默认取当前分支版本线的下一个补丁版本，编辑可改为目标版本；
- 验证：mvn -B -pl spec-checks -am test 通过（pre-commit 门禁）；

## 2026-11-17 · 任务编码生成并参与修复分支（feat `2b02714`）
- 问题：平台任务只能看到内部 id（task-xxxx）与远端编号，没有稳定、可读、可口头引用的编码；自动修复分支是 agent/bugfix-<随机串…
- 方案：GitIssue 增加 code 字段：项目内按分类自增（FEAT-1 / BUG-2），创建时由 SQLite 存储统一分配、更新时不可变；老…
- 验证：新增 SqliteGitIssueStoreCodeTest：按项目+分类分配（BUG-1/BUG-2/FEAT-1、项目隔离）、更新不改编码、…

## 2026-11-17 · 版本线与分支强绑定与快照发布（feat `8b00f39`）
- 问题：版本号与分支没有关系：流水线只能选提交、不能选分支，版本号是自由文本且允许带 v；标签不分基础版/补丁版，补丁发布与版本线无关，容易把 0.2.…
- 方案：新增 domain/release/ReleaseVersion：严格 x.y.z（不带 v）、版本线 x.y 映射长期分支 x.y.0-sna…
- 验证：新增并跑通 ReleaseVersionTest（4）、PipelineVersionPolicyTest（3）、PomVersionSyncT…

## 2026-11-17 · 会话能力栏布局与会话发送回归（bug `c6f1cce` `603cb80`）
- 问题：会话能力栏（Skill / MCP 选择页）可用性差：默认排序把已启用项打散在长列表里，找「这轮开了什么」要反复滚动；两组能力卡片无法折叠，能力…
- 方案：能力栏：默认排序改为已启用优先（按名称排序保留），排序逻辑抽成 orderCapabilities 纯函数便于测试；Skill/MCP 卡片增加…
- 验证：新增 capabilityOrder.test.ts：4 个用例通过（已启用置顶、全开/全停顺序不变、按名称排序且不修改原数组，node + e…

## 2026-11-17 · 首次发布结果项展示与标签推送凭据修复（bug `729dbaf`）
- 问题：首次发布就绪清单把「首次发布」结果项当普通检查项渲染：该项 required=false，但没有成功发布记录时仍渲染红叉，出现「顶部显示可以发布…
- 方案：就绪清单：结果项改用中性信息图标（不影响发布），文案改为「尚无 test 的成功发布记录」并去掉发布入口，结果标签补充说明。
- 验证：新增 DefaultRepoLocatorTagTest：2 个用例通过（无 origin 时只落本地并给出原因、未知标签重推失败、仓库不存在返…

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

## 2026-11-17 · 就绪清单一键生成 Dockerfile（feat `52e05d0`）
- 问题：就绪清单里「仓库没有 Dockerfile」没有修复入口，用户只能去对话或手写
- 方案：新增 POST /projects/{id}/dockerfile 复用 ensure_dockerfile 生成待确认补丁；
- 验证：mvn -B -pl spec-checks -am test 523 通过；

## 2026-11-17 · 环境矩阵显示构建版本号（bug `921ffca` `4a64f83`）
- 问题：工作区环境 Tab 的部署卡片只显示 latest，看不到构建版本号，无法判断当前部署对应哪次构建。
- 方案：流水线部署传给部署器的版本号从 deployVersion 改为 versionLabel：未打 tag 时回落到 <env>-v<seq>，H…
- 验证：Tests run: 519, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 工具渐进披露与任务面板改版（feat `4b0a8c4` `ef7eaa8` `2b7dc5f` `b3fbfe7`）
- 问题：每轮对话都把 70+ 个工具 schema、技能全文与 MCP 工具塞进上下文，单次请求工具定义约占两万 token；上下文裁剪过于频繁，模型侧…
- 方案：工具按能力拆成插件，默认只暴露核心工具，其余用 tool_load 按需激活并写入会话状态；技能只给索引、正文用 skill_load 读取，M…
- 验证：Tests run: 516, Failures: 0, Errors: 0, Skipped: 2

## 2026-11-17 · 会话侧栏选中态 / 能力页改版 / 轨迹参数修复（chore `fe02f3a` `1ec8671` `ef99058`）
- 问题：侧栏整块工作区高亮、会话能力页重文档难用、轨迹工具详情栏参数缺失
- 方案：选中态收敛到单条会话；
- 验证：vue-tsc --noEmit 与 vite build 均通过；

