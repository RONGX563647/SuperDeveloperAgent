<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：other

41 个会话；检索用 `python tools/docs/find.py --module other`。

## 2026-11-22 · 刷新 0.1 Code Wiki 到封版状态并准备 0.1.2 tag（docs `6296a44`）
- 问题：0.1 分支 Code Wiki 基线停在 e9a39d3（2026-11-20），此后 0.1 的生产 SERVER 网络/账本口径修复、pr…
- 方案：以 0.1 代码为准逐篇核对，刷新 12 篇 wiki：补 0.1 近期部署/沙箱/token/CI/前端事实；纠正 @Tool 数为实测 90…
- 验证：所有写入事实均用 grep/read 在 0.1 worktree 代码核实（含 90 @Tool/24 工具类、R15=waitFor 无界读…

## 2026-11-20 · 防旧归档模型回潮：validate 拦截 docs/process 复活（chore `dc587a0`）
- 问题：旧归档模型可被旧基点分支合并静默带回（今晚已实际发生，gate.py 被回退、500+ 文件复活），validate 不拦。
- 方案：validate 增加防回潮守卫：docs/process 出现 manifest.json 即失败并指向 migrate-to-ledger.…
- 验证：gate self-test OK（含新用例）；validate 176 份链全绿。

## 2026-11-20 · 补齐 skill 路由层：三张新卡 + 生成式路由索引（chore `3dc7dc1`）
- 问题：skill 路由层有三个高频域没有知识卡（对话运行时/治理确认/BugFix 闭环），且缺路由索引——agent 面对 9 张卡仍要逐个打开才知…
- 方案：补 chat-runtime/governance-changes/bugfix-loop 三张卡（类名逐一核对）；docs/skills/in…
- 验证：build.py sync/check 一致（16 个文件）；gate validate 170 份、self-test OK；skills/i…

## 2026-11-20 · 补周报视图，校正 README 阅读成本账（chore `bd18e87`）
- 问题：G 方案实用功能清单的周报视图未落地；README 的『全树 ≈ 40 万 token』是改造前的旧账，会误导后来者做预算。
- 方案：build.py 增加 views/weekly.md（ISO 周聚合，生成物不带时间戳）；README 改为实测 370k（ledger 约 …
- 验证：build.py sync 后 check 一致（15 个文件）；weekly 分组输出正确（2026-W47）；commit 前置自检放行（纯…

## 2026-11-20 · docs 体系改造：ledger 单一写入 + skills/views/find 三层消费（chore `a381003`）
- 问题：docs/ 539 个文件里 89% 是会话归档与每会话两份重复总结，分类混杂、agent 无路由入口、全读约 40 万 token，散文总结大…
- 方案：换成一份事实多种投影：ledger JSONL 唯一写入点（165 份 manifest 原样迁入，哈希链不变）、build.py 生成 vie…
- 验证：gate self-test OK；validate 165 份链全绿；views check 14 文件一致；sweep --dry-run …

## 2026-11-20 · Code Wiki 刷新：镜像就绪、沙箱配置、归档链诊断（docs `ceef158`）
- 问题：Code Wiki 落后于今天的改动：执行面只有一句 ensureImageReady()，镜像缺失如何被发现/失败如何退避/状态存在哪都没写；…
- 方案：按 06/07/08/09/10/11 与 index 逐篇补齐：新增镜像就绪自动收敛小节、迁移与表清单、只读接口、组件与纯函数边界、6 个 p…
- 验证：内容逐条对照代码核实：ImageProvisioner 只信 docker image inspect、退避公式为 min(max, base*…

## 2026-11-20 · 分支清理与归档诊断工具收录（chore `b1a8628`）
- 问题：分支清理前：sync 分支（已落后 164 提交、树差 395 文件）上有唯一有用的 diag-chain.py 归档断链诊断工具，0.1 没有…
- 方案：清理前摘取 diag-chain.py 到 0.1.0-snapshot（tools/session-archive/），实测 bug 链诊断输…
- 验证：diag-chain.py bug 4 输出「链连续」；

## 2026-11-20 · 收录 fd26ec8 生产按版本部署修复提交（chore `fd26ec8`）
- 问题：远端并入了 fd26ec8（生产按版本部署选项修复），无归档 manifest 覆盖，归档链断
- 方案：intake 归档收录 fd26ec8 提交
- 验证：gate.py validate 通过

## 2026-11-20 · 预推门禁前端自检输出编码修复（chore `7573c5c`）
- 问题：预推门禁前端自检输出编码修复
- 方案：fix(process): 预推门禁前端自检按 UTF-8 读取输出，不再因 GBK 解码抛线程异常
- 验证：Tests run: 0, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-20 · 分支整理与归档断链修复（chore `591cffc`）
- 问题：分支整理与归档断链修复
- 方案：chore(process): 重链 6 处归档断链（历史重写后的等价提交）
- 验证：Tests run: 0, Failures: 0, Errors: 0, Skipped: 0

## 2026-11-20 · 移除误提交的临时排查文件（chore `ce30f40` `a368565`）
- 问题：移除误提交的临时排查文件
- 方案：chore: .gitignore 忽略 .tmp-* 临时排查文件
- 验证：删除 5 个误提交的 .tmp-* 文件（挑选性 git add），并在 .gitignore 增加 .tmp-* 规则；

## 2026-11-20 · FEAT-1/2/3 的真实缺口核实（docs `83c702f`）
- 问题：看板剩 3 条 FEAT（全 P0），但凭前几轮的教训，我不打算再写代码——因为机制存在不等于功能可用。
- 方案：把这层区别写成 docs/summary/chore/2026-11_dispatch-gap.md：1) 列出数据库事实（3 SUPERVIS…
- 验证：三个脚本运行正常并输出上述事实；

## 2026-11-19 · 合入主干并重排归档链（chore `0a04b9c`）
- 问题：PR #29 的 backend 与 docs 两项都报「feat/2026-11-19_2326_feat-5-usage-hitrate-p…
- 方案：合入 origin/0.1.0-snapshot（11 个提交）后执行 reseal_chain 重排 feat 分类，让我的归档接在最新链尾之…
- 验证：python tools/session-archive/gate.py validate：142 份通过

## 2026-11-19 · 同步执行器锚点（docs `c21e04a`）
- 问题：推送命中率面板这批改动时，pre-push 报「Harness Handbook 已漂移」。
- 方案：先恢复并行工作线的改动，再执行 handbook sync，让锚点基于真实代码计算；提交 handbook 后单独推送 docs。
- 验证：python tools/handbook/build.py sync：锚点 11 个已刷新；

## 2026-11-19 · 同步 handbook 锚点（docs `48a8c15`）
- 问题：推送 docs/round-status 时 spec-checks 报「架构 + 契约」未通过，31 个用例里只有 HandbookDrift…
- 方案：跑 tools/handbook/build.py sync 让生成区跟随代码更新，提交后再推送；同步只改行号（5 行新增/删除），锚点的 sy…
- 验证：python tools/handbook/build.py sync：锚点 11 个已刷新；

## 2026-11-19 · 本轮收口结果与三类假问题（docs `a48c78e`）
- 问题：本轮结束时需要一个可交接的结论，但成果散在七八个提交与四次 CI 往返里；更麻烦的是这一轮有三次「看起来是代码写错」的失败，其实都不是代码问题—…
- 方案：写 docs/summary/chore/2026-11_round-status.md：1) 本轮落地的四批代码与对应提交、主干 CI 连续三…
- 验证：git commit 成功；

## 2026-11-19 · 修正 cherry-pick 后归档记录的旧 sha（chore `a054113`）
- 问题：PR #27 的 docs job 报「会话归档校验失败：2 项」，指出 feat/1747 与 feat/1820 两份归档里的 commit…
- 方案：用 append-cherry-pick.py 把两份归档的旧 sha 改记为新 sha 并重算 chain.self；feat 分类因多个并行…
- 验证：python tools/session-archive/gate.py validate：132 份通过

## 2026-11-19 · 提交用量命中率归档文档（docs `cfaf487`）
- 问题：纯文档归档本身没有问题，但它必须有一个活跃会话才能提交——这是门禁的规则，与内容无关。
- 方案：按规则补一个 docs 类别会话并立即 finish：docs 类别不要求新增测试用例，只要求写入 problem/solution，因此正好用…
- 验证：git commit 成功；

## 2026-11-19 · 契约审计识别 apiBlob 的 POST 下载（chore `0f6b8f6`）
- 问题：契约审计 extractCalls 把 apiBlob 硬编码成 GET，而 apiBlob 早已与 apiFetch 同签名、二进制下载可以走…
- 方案：apiBlob 与 apiFetch 共用 parseMethodAlternatives，三元表达式/变量引用/缺省 GET 全部复用；新增单…
- 验证：audit 单测 12 通过（原 11）；真实仓库审计 200 处调用 / 187 paths，0 error / 0 warning。

## 2026-11-19 · 补 CI 修复的最终验收记录（chore `70a808f`）
- 问题：CI 编码门禁的修复虽然已在 PR #23 通过并合并，但归档里缺最关键的一条证据：远端主干 CI 到底绿没绿。
- 方案：把远端最终验收写进归档：PR #23 五项检查全绿（backend 2m56s / contract / docs / frontend / s…
- 验证：gh run view 44525721043 结论 success；

## 2026-11-19 · 补 CI 门禁的第二组失败证据（chore `2e8b205`）
- 问题：修完 PR #21 那条后，PR #22 合并又产生一条 58c383a「feat(task): ?????????(PR #22)」，同一位置…
- 方案：补第二组证据：重新统计主干损坏提交，已达 13 条且全部是平台合并（subject 结尾 (PR #n)）、非平台损坏 0 条，说明规律稳定成立…
- 验证：两次原位重放均 exit 0；

## 2026-11-19 · CI 平台合并提交按特征放行（chore `1104fb9`）
- 问题：PR #21 合并后 CI 变红，报「1 条已损坏（? 不可逆）」指向 1d03365。
- 方案：门禁加 PLATFORM_MERGE_SUBJECT 自动识别：只认 subject 结尾的 (PR #n)（正文提及不算）。
- 验证：CI 原位重放 --base b82fe79 --head 1d03365：修复前 exit 1、修复后 exit 0 并给出平台合并提示；

## 2026-11-19 · BUG 清零：补 BUG-1 实机证据并关闭（chore `3e38d12`）
- 问题：BUG-1 是最后一条 BUG，但它的描述很笼统（「agent 能力太弱，遇到不同环境经常中断」），而两个真实成因此前已修完。
- 方案：从 37 份运行历史里取实证后关闭，并把证据写进任务描述：1) run-c641e08b 的 rollback 阶段是 SUCCEEDED，日志…
- 验证：task-board/open.py 显示 OPEN 4 条且全部为 FEAT，BUG 清零；

## 2026-11-19 · 关闭 FEAT-4 与 FEAT-7（chore `d10be49`）
- 问题：FEAT-4（任务）与 FEAT-7（会话）的四项需求都已完成，但看板仍显示 OPEN——其中 FEAT-4 的两项跨越了多个批次（标题带编码在…
- 方案：用脚本 close-feat4.py / close-feat7.py 逐条写入完成记录（每项对应提交号与复核用例）并关闭，脚本本身入库以便追溯…
- 验证：task-board/open.py 显示 OPEN 5 条；

## 2026-11-19 · 门禁按改动性质分派测试范围，堵住前端改动跑后端全量（chore `02ddb88`）
- 问题：用户反馈每次改动都要跑全量单测，浪费时间与 token。
- 方案：changed_modules 改为返回 (模块列表, 是否含前端)；incremental_test_command 按改动性质分四路：后端模…
- 验证：真实提交实测：纯前端 1.4s（旧 325s）、单后端模块 42s/80 用例；纯前端 feat 会话可正常 finish 归档；gate se…

## 2026-11-19 · 最终状态与 BUG 收口结论（docs `3de2084`）
- 问题：本轮把 BUG 从 17 条收口到 1 条，但缺少一份能一眼看懂的收尾结论：看板变化、四批新增里哪些已进主干哪些还在分支、BUG-1 为什么仍 …
- 方案：新增 docs/summary/chore/2026-11_final-status.md：看板 23→7 / BUG 17→1 的变化表、本轮…
- 验证：python tools/session-archive/gate.py validate 通过；

## 2026-11-19 · Dockerfile 提示会话归档与看板脚本入库（chore `e413c8f`）
- 问题：两件事：1) 提交 Dockerfile 预检提示的归档时，pre-commit 直接抛 TypeError: unhashable type:…
- 方案：把工作区里已适配的 gate.py（record_module_green(changed_modules(staged)[0])）重新暂存后再…
- 验证：gate self-test OK；

## 2026-11-19 · 看板收口：关闭已修项并补进展记录（chore `5789f3a`）
- 问题：看板与实际不符：本轮已修完 10 条任务（BUG-2/3/4/5/6/7/9/14/15/16），但看板仍显示 23 条 OPEN，优先级判断因…
- 方案：逐条关闭已修任务并把「已修 + 提交号 + 复核用例」写进描述（10 条），关闭依据是 merge-base 验证修复提交已在 origin/0…
- 验证：tools/task-board/verify.py 退出码 0（编码唯一、前缀一致、连续）；

## 2026-11-19 · 修复归档 manifest 指向游离提交导致 CI 校验失败（chore `232abdb`）
- 问题：bug-24 的归档 manifest 引用了提交 5fc1ab1，该提交因并行会话重写历史成为游离提交：本地 git cat-file 找得到…
- 方案：manifest 的 sha 改为内容等价的正式提交 1b01d77（git diff 为空），去掉 subject 的 BOM，并用 rese…
- 验证：gate.py validate 通过（106 份归档）；handbook check 11 锚点 / 11 篇 L3 通过；重新扫描全部 ma…

## 2026-11-19 · 14 条遗留项逐条对账（docs `6f8fc5a`）
- 问题：本轮 14 批修复完成后，14 条清单、提交号与三条结构性判断的落地情况散在 14 份归档里，没有一张能直接核对的表：想确认「某条到底修没修、落…
- 方案：新增 docs/summary/bug/2026-11_v010-carryover-ledger.md：按 P0/P1/P2 三条清单逐条列出…
- 验证：python tools/session-archive/gate.py validate：92 份归档通过

## 2026-11-19 · BUG-4 任务看板优先级与分类重排（chore `8141447`）
- 问题：用户的结构性判断没有被执行：看板 32 条任务的优先级按「现象刺眼程度」而不是影响面标注——版本号太丑（纯观感）标 P0，而已修目录里的轨迹参数…
- 方案：按影响面重排 32 条任务：P0 收缩到能一票否决的七条（写不进 src/main、sqlite 丢数据、轨迹参数缺失、发送按钮挂住、对话流中断…
- 验证：python tools/task-board/verify.py 退出码 0；

## 2026-11-18 · 门禁安全扫描与红队自审（chore `e9a6461`）
- 问题：门禁安全扫描与红队自审
- 方案：feat(gate): CI 安全扫描、红队自审与耗时预算校准
- 验证：red-team 实测 24.3s：注入 R03/R04/R08/R11 违规样本后扫描必红并报出规则编号；gate.py metrics 显示…

## 2026-11-18 · 门禁多会话与可观测性（chore `52841be`）
- 问题：门禁多会话与可观测性
- 方案：feat(gate): 多会话并行、环境自检、耗时观测与 hooks 随仓库走
- 验证：多会话实测：主树 key=1b43af8a0063 与 worktree key=0ea4c24b292d 各开一个会话，gate.py sta…

## 2026-11-18 · 修正合并提交误纳入的编辑工具草稿文件（docs ）
- 问题：合并提交误纳入未完成的编辑工具草稿，且 ToolCatalog 残留 EditorTools 注册导致编译失败
- 方案：剔除 ToolCatalog 中的 EditorTools 注册（回退为远端版本），批次4文件随合并提交一并纳入并已通过全量测试门禁
- 验证：Tests run: 698, Failures: 0, Errors: 0, Skipped: 1

## 2026-11-18 · 归档落盘互斥与链重排（chore `115e5c5`）
- 问题：并行会话各自 finish 时读到同一旧链尾，合并后出现多处 chain.prev 断链，pre-push 直接拦下推送；
- 方案：归档落盘走公共 .git 下的 archive.lock 互斥（复用 TestLock，支持自定义标签），并在同一临界区内按 createdAt…
- 验证：gate.py self-test OK；

## 2026-11-17 · 当前工作区任务存在问题：右侧任务栏是独立新写的存储，未复用已有工具栏的任务数据与工具，同时已有工具栏UI存在边界未占满、（docs ）
- 问题：AI 开发会话（无代码提交）：当前工作区任务存在问题：右侧任务栏是独立新写的存储，未复用已有工具栏的任务数据与工具，同时已有工具栏UI存在边界未…
- 方案：用户反馈当前工作区任务存在问题：右侧任务栏是独立新写的存储，未复用已有工具栏的任务数据与工具，同时已有工具栏UI存在边界未占满、缺少P0/P1/…
- 验证：无代码改动（自动对账生成）；

## 2026-11-17 · 修复能力市场不可达的TLS证书不受信bug，根因为本地SteamTools/Watt Toolkit代理工具对GitHu（docs ）
- 问题：AI 开发会话（无代码提交）：修复能力市场不可达的TLS证书不受信bug，根因为本地SteamTools/Watt Toolkit代理工具对Gi…
- 方案：修复能力市场不可达的TLS证书不受信bug，根因为本地SteamTools/Watt Toolkit代理工具对GitHub相关域名做了TLS中间…
- 验证：无代码改动（自动对账生成）；

## 2026-11-17 · 会话删除功能存在问题，仅删除前端和会话主表记录会导致数据库残留，删除的会话刷新后重新出现（docs ）
- 问题：AI 开发会话（无代码提交）：会话删除功能存在问题，仅删除前端和会话主表记录会导致数据库残留，删除的会话刷新后重新出现
- 方案：用户反馈会话删除功能存在问题，仅删除前端和会话主表记录会导致数据库残留，删除的会话刷新后重新出现。
- 验证：无代码改动（自动对账生成）；

## 2026-11-17 · 对话围绕增强自动修复环境问题的Agent能力展开，完成了多项关键修复与优化：1. 修复自动续跑使用旧流水线定义的问题，续（docs ）
- 问题：AI 开发会话（无代码提交）：对话围绕增强自动修复环境问题的Agent能力展开，完成了多项关键修复与优化：1. 修复自动续跑使用旧流水线定义的问…
- 方案：本次对话围绕增强自动修复环境问题的Agent能力展开，完成了多项关键修复与优化：1. 修复自动续跑使用旧流水线定义的问题，续跑前重新解析最新配置…
- 验证：无代码改动（自动对账生成）；

## 2026-11-16 · pre-push 阻止未归档代码提交（chore `255874f`）
- 问题：pre-push 只校验 active 会话与归档提交状态；
- 方案：pre-push 增加锚点校验：取最新归档的最后一个提交，逐条检查其后提交，凡含非豁免路径改动且未被任何归档登记即拒绝推送
- 验证：在临时 clone 中验证：干净 clone 通过；

## 2026-11-16 · 修复 JaCoCo 覆盖率未生成（chore `7acfa87`）
- 问题：summary 的 lineCoverage 为 N/A：父 pom 的 surefire argLine 写死，覆盖了 jacoco prep…
- 方案：surefire argLine 改为 @{argLine} --enable-native-access=ALL-UNNAMED，保留 JDK…
- 验证：mvn -B -pl spec-checks -am test 全绿；

