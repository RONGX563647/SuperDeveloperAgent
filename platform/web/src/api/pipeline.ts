import { apiFetch } from './http'

/** GET /api/v1/ci/pipelines?projectId=RY 的响应。pipelines 是白名单里的流水线 id。 */
export interface PipelineListView {
  engine: string
  pipelines: string[]
}

/** POST /api/v1/ci/pipelines/{id}/run 的响应。 */
export interface TriggerRunView {
  runId: string
  status: string
  env?: string
  version?: string
  tagged?: boolean
}

/** 一次运行的 stage 结果（按执行顺序）。status 为 StageStatus 枚举名。 */
export interface StageView {
  id: string
  status: string
  exitCode: number
  errorCode: string
  summary: string
  durationMs: number
  /** 后端返回该 stage 的产物数量（而非产物明细）。 */
  artifacts: number
}

/** 运行级产物。digest 可能为空串；meta 为部署附加信息（本机模式带前后端端口）。 */
export interface ArtifactView {
  kind: string
  ref: string
  digest: string
  sizeBytes: number
  meta?: Record<string, string | number>
}

/** 运行元信息：触发者 / 环境绑定 / 流水线定义来源（Agent 固化脚本时可见）。 */
export interface RunMetaView {
  actor: string
  bindingId: string
  confirmedBy: string
  definitionSource: string
  definitionNote: string
}

/** 运行中阶段进度（后端 progress；终态运行也可能保留最后一次快照）。 */
export interface RunProgressView {
  currentStageId: string
  stageTotal: number
  completedStages: number
  elapsedMs: number
  stageElapsedMs: number
  cancelRequested: boolean
  forceTerminated: boolean
}

/** 失败自动修复状态：平台是否动手、动了几次、是否已自动续跑。 */
export interface RunAutoRepairView {
  enabled: boolean
  attempts: number
  maxAttempts: number
  resumed: boolean
  lastAction: string
  lastResult: string
}

/** 发布形态事实：后端 releaseFacts 的结构化输出。 */
export interface ReleaseFactsView {
  /** 首次发布（该环境没有上一版）：回滚预检会被跳过，这不是错误 */
  firstRelease: boolean
  /** PUSHED / LOCAL / FAILED / REJECTED / SNAPSHOT */
  tagState: string
  /** 稳定原因码（LOCAL 时为 TAG_PUSH_CREDENTIAL_MISSING） */
  tagReason: string
  /** 面向用户的处理建议 */
  hint: string
  /** 标签是否真的在远端可见（只有 PUSHED 为 true） */
  remoteVisible: boolean
}

/** GET /api/v1/ci/runs/{runId} 的响应；log 为脱敏后的日志尾部。 */
export interface RunView {
  runId: string
  status: string
  stages: StageView[]
  artifacts: ArtifactView[]
  log: string
  meta?: RunMetaView
  progress?: RunProgressView
  autoRepair?: RunAutoRepairView
  /** 发布形态：首次发布 / tag 是否真的生效 / 为什么没生效 */
  release?: ReleaseFactsView
}

/** 与后端 StageStatus.isTerminal() 对齐的终态集合。 */
const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELLED'])

export function isTerminalStatus(status: string): boolean {
  return TERMINAL_STATUSES.has(status)
}

/** 列出项目可用流水线。 */
export function listPipelines(projectId: string): Promise<PipelineListView> {
  return apiFetch<PipelineListView>(`/api/v1/ci/pipelines?projectId=${encodeURIComponent(projectId)}`)
}

/**
 * 按指定提交触发一条流水线；gitSha 需为 7-40 位小写十六进制。deployTarget 可选：docker / host / server。
 *
 * [version] / [tag]：版本号与是否打 tag。打 tag 时 version 必填，本次部署不覆盖旧版本；
 * 不打 tag 时覆盖该环境的默认部署，version 仅作展示标签。
 */
export function triggerPipeline(
  pipelineId: string,
  projectId: string,
  gitSha: string,
  deployTarget?: string,
  env?: string,
  confirmToken?: string,
  version?: string,
  tag?: boolean,
  branch?: string
): Promise<TriggerRunView> {
  return apiFetch<TriggerRunView>(`/api/v1/ci/pipelines/${encodeURIComponent(pipelineId)}/run`, {
    method: 'POST',
    body: JSON.stringify({
      projectId,
      gitSha,
      env: env ?? 'test',
      deployTarget: deployTarget ?? null,
      confirmToken: confirmToken ?? null,
      version: version ?? null,
      tag: tag === true,
      branch: branch ?? null
    })
  })
}

/** dry-run 单个阶段的预检结果。 */
export interface DryRunStageView {
  id: string
  type: string
  action: string
  /** false 表示这一步本次不会执行（reason 给出原因）。 */
  runnable: boolean
  reason: string
  command: string
  scriptSha: string
  builtin: boolean
}

/**
 * 部署预检报告（只读，零 outward 变更）。
 *
 * <p>blockers 非空时后端会拒绝 apply（E-708）；reportHash/fingerprint 绑定
 * 代码、配方、环境与密钥版本，任何变化都会让旧报告失效。
 */
export interface DryRunReportView {
  projectId: string
  env: string
  pipelineId: string
  gitSha: string
  reportHash: string
  fingerprint: string
  ok: boolean
  blockers: string[]
  warnings: string[]
  createdAt: number
  stages: DryRunStageView[]
}

/** 部署预检：不执行任何变更，只返回阻塞项与阶段可执行性。 */
export function dryRunPipeline(
  pipelineId: string, projectId: string, env: string, gitSha: string
): Promise<DryRunReportView> {
  return apiFetch<DryRunReportView>(
    `/api/v1/ci/pipelines/${encodeURIComponent(pipelineId)}/dry-run`,
    { method: 'POST', body: JSON.stringify({ projectId, env, gitSha }) }
  )
}

/** 查询一次运行的状态 / stage / 产物；withLog=false 时不传输整段日志（配合增量日志端点）。 */
export function getRun(runId: string, withLog = true): Promise<RunView> {
  const q = withLog ? '' : '?withLog=false'
  return apiFetch<RunView>(`/api/v1/ci/runs/${encodeURIComponent(runId)}${q}`)
}

/** 增量日志片段。[offset, end) 为已读区间；complete 表示终态且已读到末尾。 */
export interface RunLogSlice {
  offset: number
  end: number
  text: string
  complete: boolean
}

/** 拉取增量日志（从 offset 起的新增文本），供追加式渲染。 */
export function fetchRunLog(runId: string, offset: number): Promise<RunLogSlice> {
  const q = new URLSearchParams({ offset: String(offset) })
  return apiFetch<RunLogSlice>(
    `/api/v1/ci/runs/${encodeURIComponent(runId)}/log?${q}`
  )
}

/** 行分页日志：虚拟滚动与「共 N 行」都靠它。 */
export interface RunLogPage {
  /** 起始行号（0 基，已按实际总行数夹紧） */
  offset: number
  /** 本次生效的行数上限 */
  limit: number
  /** 日志总行数 */
  total: number
  lines: string[]
  /** 还有更多行 */
  truncated: boolean
  /** 已读到末尾 */
  complete: boolean
}

/**
 * 按行取日志窗口。
 *
 * 增量接口只给 text 时前端拿不到总行数：既算不出滚动高度，也只能把整段日志塞进 DOM。
 * 需要虚拟滚动或「跳到末尾」时用这个接口。
 */
export function fetchRunLogLines(
  runId: string,
  offset = 0,
  limit = 500,
): Promise<RunLogPage> {
  const q = new URLSearchParams({ offset: String(offset), limit: String(limit) })
  return apiFetch<RunLogPage>(
    `/api/v1/ci/runs/${encodeURIComponent(runId)}/log/lines?${q}`
  )
}

/** 取消一次运行（只对未终态生效）。 */
export function cancelRun(runId: string): Promise<RunView> {
  return apiFetch<RunView>(`/api/v1/ci/runs/${encodeURIComponent(runId)}/cancel`, {
    method: 'POST'
  })
}

export interface StopLocalView {
  stopped: boolean
  message: string
}

/**
 * 停止开发环境本机前后端（停掉后可用 IDE 启动）。
 *
 * 只停平台「直接部署」托管的进程；IDE 自己起的进程不受影响。
 */
export function stopLocalDeploy(projectId: string, env = 'dev'): Promise<StopLocalView> {
  const q = new URLSearchParams({ projectId, env })
  return apiFetch<StopLocalView>(`/api/v1/ci/deploy/host/stop?${q}`, { method: 'POST' })
}

/** 按版本部署：复用历史镜像 tag，跳过构建/打包，只跑 deploy + health。 */
export function deployImageVersion(
  pipelineId: string,
  projectId: string,
  env: string,
  tag: string,
  gitSha: string,
  version?: string,
  tagged?: boolean,
  branch?: string,
  /** 生产必填：DeployGate 校验测试证据后签发的一次性令牌，不带则 400 CONFIRM_REQUIRED。 */
  confirmToken?: string
): Promise<TriggerRunView> {
  return apiFetch<TriggerRunView>(`/api/v1/ci/pipelines/${encodeURIComponent(pipelineId)}/run`, {
    method: 'POST',
    body: JSON.stringify({
      projectId,
      gitSha,
      env,
      deployImage: tag,
      version: version ?? null,
      tag: tagged === true,
      branch: branch ?? null,
      confirmToken: confirmToken ?? null
    })
  })
}

/** 一条构建历史摘要（按触发时间倒序）。 */
export interface HistoryItem {
  runId: string
  projectId: string
  /** 目标环境（dev / test / prod）。 */
  env: string
  pipelineId: string
  gitSha: string
  /** 发布分支（版本线分支 x.y.0-snapshot）；空 = 旧记录。 */
  branch?: string
  /** 触发时填写的标签版本号（打 tag 时会同步为 git 标签）；空 = 未填写。 */
  version: string
  /** 打 tag 结果：PUSHED=远端可见 / LOCAL=仅本地 / FAILED=失败 / SNAPSHOT=快照；空=未填写版本。 */
  tagStatus?: string
  status: string
  /** 触发时间（epoch 毫秒，0 表示旧格式文件）。 */
  createdAt: number
  /** 构建序号：同项目 + 同环境内从 1 开始递增，也是镜像版本号（vN）。 */
  seq: number
  stageCount: number
  /** 各阶段耗时之和（毫秒）：列表直接显示这次跑了多久。 */
  durationMs: number
}

/** 阶段预览（触发前展示"这次会跑哪些阶段、跑什么命令"）。 */
export interface PreviewStage {
  id: string
  type: string
  action: string
  runner: string
  workdir: string
  /** 非空 = 这次不会执行，值是可读原因（例如 verify 不部署、不产镜像）。 */
  skipReason: string
  /** 目标 shell（pwsh / sh）；平台内置阶段为空。 */
  shell: string
  /** 可读的完整命令（多步用 && 连接）。 */
  command: string
  /** 可复制/下载的脚本内容；平台内置阶段为空。 */
  script: string
  /** 脚本 sha256 前 12 位，用于"所见即所跑"核对。 */
  scriptSha: string
  /** true = 平台内置步骤（部署/健康检查/回滚），没有脚本。 */
  builtin: boolean
  /** deploy 阶段定义里的默认目标（docker / host / server）；其他阶段为 null。 */
  deployTarget?: string | null
}

/**
 * 预览某条流水线在指定环境下会执行的阶段。
 *
 * [gitSha] 可选：仓库里的 ci/pipeline.yml 随提交变化，传入后按该提交预览（dev 忽略，仍用本地工作区）。
 */
export function previewPipeline(
  projectId: string,
  pipelineId: string,
  env: string,
  gitSha?: string
): Promise<{ pipelineId: string, env: string, gitSha?: string, stages: PreviewStage[] }> {
  const q = new URLSearchParams({ projectId, env })
  if (gitSha) q.set('gitSha', gitSha)
  return apiFetch(`/api/v1/ci/pipelines/${encodeURIComponent(pipelineId)}/preview?${q}`)
}

/** 构建历史（按触发时间倒序，offset/limit 分页，单页最多 200）。 */
export function getHistory(projectId?: string, offset = 0, limit = 50): Promise<HistoryItem[]> {
  const q = new URLSearchParams({ offset: String(offset), limit: String(limit) })
  if (projectId) q.set('projectId', projectId)
  return apiFetch<HistoryItem[]>(`/api/v1/ci/history?${q}`)
}

/** 项目现有 git 标签（版本降序）：版本号输入框做候选与重复校验。 */
export async function listGitTags(projectId: string, limit = 30): Promise<string[]> {
  const q = new URLSearchParams({ projectId, limit: String(limit) })
  const res = await apiFetch<{ tags: string[] }>(`/api/v1/ci/tags?${q}`)
  return res.tags ?? []
}

/** 重推已存在的本地标签（幂等）：用当前用户保存的托管账号 token 推送。 */
export interface TagPushResult {
  created: boolean
  pushed: boolean
  message: string
}

export function pushGitTag(projectId: string, tag: string): Promise<TagPushResult> {
  const q = new URLSearchParams({ projectId, tag })
  return apiFetch<TagPushResult>(`/api/v1/ci/tags/push?${q}`, { method: 'POST' })
}

/** 从失败阶段继续：复用后端保留的构建现场，只重跑该阶段及其后续。 */
export function retryStage(runId: string, stageId: string): Promise<{ runId: string, retrying: boolean, message?: string }> {
  const q = new URLSearchParams({ stageId })
  return apiFetch(`/api/v1/ci/runs/${encodeURIComponent(runId)}/retry?${q}`, { method: 'POST' })
}

/** 文件产物下载地址（需要带登录令牌，走 apiBlob 拉取，不要直接当 href 用）。 */
export function artifactFilePath(runId: string, index: number): string {
  return `/api/v1/ci/runs/${encodeURIComponent(runId)}/artifacts/${index}/file`
}

/** 删除一条构建历史（记录 + 该次归档的文件产物）；运行中的会被后端拒绝。 */
export function deleteHistory(runId: string): Promise<{ deleted: boolean, message?: string }> {
  return apiFetch<{ deleted: boolean, message?: string }>(
    `/api/v1/ci/history/${encodeURIComponent(runId)}`, { method: 'DELETE' })
}

/** 一个环境下的镜像版本（来自持久化版本账本，删除构建历史不会丢失）。 */
export interface ImageVersionView {
  tag: string
  digest: string
  sizeBytes: number
  /** 构建序号对应的展示版本（test-v3）；tag 只由环境 + 提交决定。 */
  version: string
  runId: string
  gitSha: string
  status: string
  /** 首次构建时间。 */
  createdAt: number
  /** 最近一次部署到本环境的时间（0 = 尚未部署）。 */
  deployedAt: number
  /** 最近一次部署的操作者（ui:admin / agent / history）。 */
  deployedBy: string
  /** 最近一次部署（当前版本）。 */
  current: boolean
  /** 上一版（回滚目标）。 */
  previous: boolean
}

/** 镜像版本列表：current / previous / 历史，按最近部署时间倒序。 */
export function deployImages(
  projectId: string,
  env: string
): Promise<{ projectId: string, env: string, sourceEnv: string, dockerAvailable: boolean, images: ImageVersionView[] }> {
  const q = new URLSearchParams({ projectId, env })
  return apiFetch(`/api/v1/ci/deploy/images?${q}`)
}
