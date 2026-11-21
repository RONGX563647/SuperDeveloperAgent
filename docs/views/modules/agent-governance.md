<!-- 由 tools/docs/build.py 生成，勿手改 -->
# 模块：agent-governance

15 个会话；检索用 `python tools/docs/find.py --module agent-governance`。

## 2026-11-21 · 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档（chore `d0aaa4d` `4bcbb32` `539d4ba` `dbfa7c2` `ca0a837` `e9e294a` `b6487a3` `781ba1f` `d4320a7` `cd05f58`）
- 问题：0.2.0-snapshot 上 8 个 refactor 提交（d0aaa4d4..781ba1f5）由并行会话推送时未走会话归档，pre-p…
- 方案：开 chore 会话把 baseHead 定在 c784ff18 补录 8 个 refactor 提交的归档缺口；
- 验证：pre-commit 门禁增量测试 PASSED（agent-executor: DockerActionRunnerPrepareTest/D…

## 2026-11-20 · 同步 0.1 分支到 0.2 分支（合并 + 重封归档链）（chore `dbeb728` `fe0b85f` `2a4591d` `cfaf487` `ac40c70` `6a8d0b9` `a054113` `9b96b52` `d6d5f25` `60accb8` `d2ce0ba` `0ef4309` `a0d886b` `f231e12` `ad02dad` `a48c78e` `6a0c837` `48a8c15` `501439d` `3e39c2c` `a3e360a` `b33cb9e` `239ee27` `ce82776` `c21e04a` `e2e69b6` `0a04b9c` `d9c2541` `25c7716` `f0a5776` `1b998b8` `1f84d49` `ab86d4e` `cc2256c` `c16ed3d` `d3cb637` `83c702f` `3f42162` `e92e7a0` `bea5a2a` `6acdb24` `b0124a9` `d103bd6` `fd01545` `d70eb9f` `16c6f4c` `0054f5f` `fd2636d` `e788458` `2674270` `ce30f40` `a368565` `3981a7c` `591cffc` `b6a0ca8` `7573c5c` `fafb765` `03f5aee` `f380411` `3bccf5c` `cc48ad1` `4c91eed` `2b50269` `976815b` `6b132e9` `7e41654` `d1e15aa` `e72ff5d` `70a395c` `fd26ec8` `c079f5d` `e57b713` `d7b3cf7` `6142e67` `631b391` `d39c02f` `da6cfff` `d1bc7cc` `b519605` `da667d4` `4656464` `c1276b1` `fc8eab2` `0e1f446` `97df414` `e1583e5` `9abc943` `616e7d1` `b1a8628` `e9a39d3`）
- 问题：0.2 线停在 11-19，落后 0.1 线 98 个提交；两条线各自维护归档链，直接合会断链，handbook manifest 也是生成物。
- 方案：在 0.2 线执行 merge 0.1.0-snapshot：6 个 feat manifest 与 handbook manifest 的冲突…
- 验证：gate validate 通过（170 份归档）；handbook check 通过（11 锚点）；reactor test-compile …

## 2026-11-20 · 0.1.1 OPEN bug 批量修复（27+3 条）（bug `6acdb24`）
- 问题：PG 工单 30 条 OPEN BUG（0.1.1 的 27 条 + 无版本 3 条）逐一按根因分析修复
- 方案：7 个并行修复域：工具面收敛（28/29/30/31）、执行器（32/35/38/39/41/46）、进程管理（40/42/43）、gitiss…
- 验证：agent-domain 97 / agent-infra 162 / agent-executor 258 / agent-tools 85 …

## 2026-11-18 · 门禁加固：CI、规则自测、覆盖率闸门、前端自检（chore `4efd514`）
- 问题：门禁加固：CI、规则自测、覆盖率闸门、前端自检
- 方案：feat(gate): 真实 CI、规则 fixture 自测、覆盖率闸门与前端自检
- 验证：全量 mvn verify 144.7s rc=0（含 jacoco check，All coverage checks have been m…

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

## 2026-11-18 · Phase 2：全部 Store 迁移到 JPA 统一持久化（feat `4dd3af6`）
- 问题：持久化层写死 SQLite：18 个 Store 各自打开分域 .db 文件并手写连接池/事务/DDL，无法通过配置切换 PostgreSQL；…
- 方案：43 张表全部映射为 @Entity（含 9 个复合主键 @IdClass.Key），Store 统一通过 PlatformDb / Gover…
- 验证：mvn -B test -Djacoco.skip=true（排除 4 个依赖 Docker/历史归档的环境用例）：570 用例，0 失败 0 …

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

## 2026-11-17 · 接入 springdoc、前端契约审计与错误码收敛（feat `e075cd6` `e2202b3` `979d6dd` `7941122`）
- 问题：缺少可机器校验的 API 契约，前后端类型漂移；
- 方案：接入 springdoc 生成 OpenAPI 与类型快照，增加前端契约审计与 Mock 联调；
- 验证：Tests run: 484, Failures: 0, Errors: 0, Skipped: 2（排除 ApiDocsSnapshotTes…

## 2026-11-17 · Harness Handbook 三级文档体系（锚点生成 + 漂移门禁）（feat `7a1d78a`）
- 问题：工程文档只有零散的 spec 与 schema，改运行时行为时无法快速定位到源码；
- 方案：docs/handbook 三级结构（L1 总览/L2 阶段/L3 实现单元），L3 由代码内 @handbook 锚点脚本生成行为→源码位置表…
- 验证：Tests run: 507, Failures: 0, Errors: 0, Skipped: 2

