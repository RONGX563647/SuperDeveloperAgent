# 任务清单：流水线体验重构 + 开发环境本机部署

## M1 前端精简与模式切换

- [x] T1 `HistoryList.vue`：新增环境筛选（全部/开发/测试/生产），只展示匹配记录；保留环境标签。
- [x] T2 `PipelineView.vue`：删除单步动作面板、动作结果区块、L3 变更确认对话框及其状态/函数/导入。
- [x] T3 删除 `components/pipeline/ActionPanel.vue`（确认无其它引用）。
- [x] T4 `TriggerBar.vue`：删除「高级选项」折叠区；新增「打包成镜像 / 直接部署」分段按钮（`modelValue` 双向绑定）。
- [x] T5 `PipelineView.vue`：模式状态与默认值（dev→host，test/prod→server/docker）、主按钮文案映射、触发时传 `deployTarget`。
- [x] T6 `PipelineView.vue`：dev+直接部署 显示本机运行提示与「停止本机服务」按钮，调用停止接口并刷新。
- [x] T7 `api/pipeline.ts`：新增 `stopLocalDeploy(projectId, env)`。

## M2 后端：开发环境本机部署

- [x] T8 新增 `LocalDevHostDeployer`（agent-executor）：pid/log/静态服务器脚本、启动顺序、就绪轮询、`stop`、幂等。
- [x] T9 新增 `StaticServerScript`（生成 `static-server.cjs` 文本，SPA fallback + `/prod-api`、`/dev-api` 反代并去掉前缀）。
- [x] T10 `BuiltInPipelineEngine`：新增 dev 本机分支 `executeLocalDev`（workspace/build/frontend/deploy/health），端口分配（前后端互斥）、容器内网名换宿主机地址、失败走 `recoverIfNeeded`；新增 `stopLocalDev(projectId, env)`。
- [x] T11 `PipelineConfig`：装配 `LocalDevHostDeployer` 并注入引擎（新构造参数，旧构造器保持兼容）。
- [x] T12 `PipelineController`：新增 `POST /api/v1/ci/deploy/host/stop`；产物视图增加 `meta`（本机端口）。
- [x] T13 `LocalProcessServiceRuntime`：补 RuoYi 布局识别（`RuoYi-Vue/ruoyi-admin`、`RuoYi-Vue/ruoyi-ui`）。

## M3 验收

- [x] T14 平台 `mvn -q -DskipTests package` 通过；`DeployersTest` + 新增 `LocalDevHostDeployerTest` 通过。
- [x] T15 重启后端（新 jar）→ `/actuator/health` UP。
- [x] T16 前端页面检查（HMR）：无单步动作/高级选项；历史环境筛选；dev→直接部署、test→打包成镜像联动。
- [ ] T17 dev + 直接部署实跑：日志含 `[dev]` 阶段，前端 2xx，dev 无新增 docker 应用容器。
      实跑已触发（run-914304d0），平台正确执行 workspace→build；失败原因是目标仓库工作区当前有 403 个删除（`com.ruoyi.task`/`com.ruoyi.script` 被删但仍有引用），非平台问题。本机部署运行时链路已用独立验收验证：后端 jar 启动 200、`/prod-api` 反代 200、SPA fallback 200、停止后端口关闭。
- [x] T18 停止本机服务后端口关闭（18701/18702 均无监听，连接被拒）；`POST /deploy/host/stop` 返回正确文案。
- [x] T19 test + 打包成镜像回归成功（容器 healthy）。run-7c76bf67：checkout / image / deploy / health 全部 SUCCEEDED。

## M4 v2：dev 强制本机、禁用 Docker

- [x] T20 `BuiltInPipelineEngine.isLocalDev`：删除 `deployTargetOverride != docker` 条件，dev 恒为本机分支（抽出 `localDevBranch`，不再接受 deployTarget 入参）。
- [x] T21 `BuiltInPipelineEngine.trigger`：dev + `deployTarget=docker` 时写日志「已忽略 docker 目标」，不改变分支。
- [x] T22 `DockerDeployer.stopDevContainers(dir, projectId, env, log)`：清理 `agent-deploy-<project>-<env>` 与旧命名容器，忽略 `No such container`。
- [x] T23 `BuiltInPipelineEngine.executeLocalDev`：workspace 阶段后调用 T22，日志记录清理结果。
- [x] T24 `PipelineView.vue`：dev 隐藏方式切换；dev 主按钮「启动本机前后端」；`canStopLocal` 只看 env；dev 一律 `triggerSelected`（不进入 replay 分支）；`TriggerBar` 无切换时也展示方式说明。
- [x] T25 后端 `mvn -q -pl agent-executor -am -DskipTests install` 通过；`LocalDevHostDeployerTest`（含新增 dev 分支用例）/ `DeployersTest` 通过。
- [x] T26 前端 `npm run build`（`vue-tsc -b && vite build`）通过。
- [x] T27 验收（可自动验证部分已通过）：dev 分支不再接受 deployTarget（单测覆盖 dev/DEV/verify/test/prod/无托管能力）；停止 API 与端口释放此前已验证（T18）；前端开发服务器已加载新代码（`canStopLocal = env==='dev'`、`showModeSwitch = env==='test'` 已生效）。
      - 实跑确认（需用户在 IDEA 重启后端后点一次 dev「启动本机前后端」）：日志应出现 `[dev] 本机模式` 与 `[dev] 已清理遗留 dev 容器 agent-deploy-ruoyiwnz-dev`，且无 `[image]` / `docker →`；当前该容器仍占 8689，属旧进程产物。
      - 目标仓库编译问题已修复：`ruoyi-wnz` 工作区有 403 个已跟踪文件被误删（`com.ruoyi.task`/`com.ruoyi.script` 等模块），从 HEAD 恢复后 `mvn -q -DskipTests package` 与 `npm run build:prod` 均通过。


## M5 构建历史增强：序号 / 滑动删除 / 镜像版本与本地地址

- [x] T28 `RunHistoryStore`：快照持久化 `seq`；`list()` 对旧文件按「项目+环境」创建时间补号（从 1 开始）并立即落盘；新增 `nextSeq(projectId, env)`（synchronized，max+1）与 `delete(runId)`；产物 `meta` 参与 YAML 往返。
- [x] T29 `BuiltInPipelineEngine`：`trigger` 用 `history.nextSeq` 作为构建序号；镜像 tag `ci-<env>-vN-<sha7>`、可读版本 `env-vN`；镜像产物 `meta` 带 `version`/`localImage`/`runId`；新增 `deleteHistory(runId)`（运行中拒绝）。
- [x] T30 `PipelineEngine#deleteHistory` 默认实现 + `RunSummary` 增加 `seq`；`PipelineController` 历史返回 `seq`，新增 `DELETE /api/v1/ci/history/{runId}`。
- [x] T31 前端：`HistoryItem.seq` 与 `deleteHistory` API；`HistoryList` 显示 `#N`、Pointer Events 左滑露出删除（运行中禁用）；`PipelineView` 确认删除 + 刷新 + 关掉被删详情；产物区展示镜像版本与本地镜像地址（点击复制）。
- [x] T32 单测 `RunHistoryStoreTest`（旧文件补号并落盘 / 删除不改他人序号 / meta 往返）通过；`mvn -DskipTests compile` 与 `npm run typecheck` 通过。
- [ ] T33 实跑验收（需重启后端）：历史出现从 `#1` 起的序号；左滑删除生效（运行中拒绝）；镜像产物出现 `test-vN` 与 `ci-test-vN-<sha7>`。

## M6 P0/P1 流水线易用性

- [x] T34 `PipelineEngine`：`StagePreview` 增加 `skipReason`；`RunSummary` 增加 `durationMs`；新增 `retryStage` / `artifactFile` 默认方法。
- [x] T35 `BuiltInPipelineEngine`：preview 输出全部阶段并标注跳过原因；history 汇总耗时；失败运行保留 worktree（`keptRunDir/keptBare` + `artifactMark`）以支持从失败阶段继续；新增 `retryStage` / `executeRetry`（dev 整条重跑，其余复用现场）；`artifactFile` 做归档目录校验；删除历史时清理保留现场。
- [x] T36 `PipelineController`：preview 返回 `skipReason`；history 支持 `offset/limit`（≤200）并返回 `durationMs`；新增 `POST /runs/{runId}/retry`、`GET /runs/{runId}/artifacts/{index}/file`。
- [x] T37 前端 API：`PreviewStage.skipReason`、`HistoryItem.durationMs`、`getHistory(projectId, offset, limit)`、`retryStage`、`artifactFilePath`、`apiBlob`。
- [x] T38 `TriggerBar`：发布 / 只检查构建两张动作卡（步数 + 是否产镜像）；只展示会执行的阶段，并提示「跳过 N 步 + 原因」；主按钮随动作切换。
- [x] T39 `PipelineView`：失败卡片（阶段/退出码/摘要/端口提示 + 复制错误/重试该阶段/整条重跑）；dev 本机成功后「打开前端/后端」；产物下载（file）与 `docker run` 复制（image）；「对比上次」弹窗（阶段耗时/状态 + 产物增删）。
- [x] T40 `HistoryList`：状态筛选 chips + 提交/序号搜索 + 单条耗时 + 「加载更多」（offset 分页累加）。
- [x] T41 实跑验收（已重启后端）：动作卡片切换与跳过说明；失败卡片与阶段重试；历史分页/筛选/耗时；对比弹窗；产物下载与 docker run 复制；dev 打开链接。


## M7 P0-1/P0-3 命令级预览（固化脚本）

- [x] T42 `PipelineEngine.StagePreview` 扩为 11 字段（shell/command/script/scriptSha/builtin）；新增 `preview(projectId, pipelineId, env, gitSha)` 默认重载。
- [x] T43 `BuiltInPipelineEngine.describeStage`：action 阶段按配方渲染脚本，脚本头写明运行目录、变量、超时、产物与 `CI=true`；部署/健康检查/回滚标记为平台内置（无脚本）。
- [x] T44 按 shell 转义：安全字符原样，其余单引号包裹（sh 内单引号 → `'\''`，pwsh → `''`）；`${var}` 占位符不加引号，运行时替换。
- [x] T45 密钥掩码：`--password xxx`、`--token=xxx`、`KEY=xxx`（SECRET/TOKEN/PASSWORD 等）、URL 内嵌凭据；预览与运行日志共用，执行 argv 不受影响。
- [x] T46 预览绑定提交：仓库定义（非 dev）用 `git show <sha>:ci/*` 取到临时目录解析，预览即"这次会检出的定义"；dev 仍用本地工作区。
- [x] T47 接口与前端：`preview` 增加 `gitSha` 参数；`PreviewStage` 增加命令字段；`TriggerBar` 点阶段展开命令面板（复制/下载 .sh/.ps1，占位符提示）；提交切换重取预览（序号防旧响应覆盖）。
- [x] T48 单测 `PipelinePreviewRenderTest`（转义/掩码/脚本头/内置阶段/按提交预览）3 例通过；后端 `mvn compile`、前端 `npm run typecheck` 通过。
- [x] T49 实跑验收（已重启后端）：dev 预览返回 `shell=pwsh / builtin=false / scriptSha / 脚本头`，deploy/health 为 `builtin=true` 且无脚本；verify 预览 `skipReason` 正常；浏览器点「镜像打包」阶段展开命令面板（含脚本与「复制/下载脚本」按钮，内置阶段文案由同一 `builtin` 字段驱动）；test 实跑日志首行 `[image] $ docker build -q -t ci-test-v17-694db4f .` 与预览 `docker build -q -t ${tag} .` 结构一致（占位符运行时替换）。
