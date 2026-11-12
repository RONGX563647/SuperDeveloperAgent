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

/** GET /api/v1/ci/runs/{runId} 的响应；log 为脱敏后的日志尾部。 */
export interface RunView {
  runId: string
  status: string
  stages: StageView[]
  artifacts: ArtifactView[]
  log: string
  meta?: RunMetaView
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

/** 按指定提交触发一条流水线；gitSha 需为 7-40 位小写十六进制。deployTarget 可选：docker / host / server。 */
export function triggerPipeline(
  pipelineId: string,
  projectId: string,
  gitSha: string,
  deployTarget?: string,
  env?: string,
  confirmToken?: string
): Promise<TriggerRunView> {
  return apiFetch<TriggerRunView>(`/api/v1/ci/pipelines/${encodeURIComponent(pipelineId)}/run`, {
    method: 'POST',
    body: JSON.stringify({
      projectId,
      gitSha,
      env: env ?? 'test',
      deployTarget: deployTarget ?? null,
      confirmToken: confirmToken ?? null
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

/** 一条构建历史摘要（按触发时间倒序）。 */
export interface HistoryItem {
  runId: string
  projectId: string
  /** 目标环境（dev / test / prod）。 */
  env: string
  pipelineId: string
  gitSha: string
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

/** 从失败阶段继续：复用后端保留的构建现场，只重跑该阶段及其后续。 */
export function retryStage(runId: string, stageId: string): Promise<{ runId: string, retrying: boolean, message?: string }> {
  const q = new URLSearchParams({ stageId })
  return apiFetch(`/api/v1/ci/runs/${encodeURIComponent(runId)}/retry?${q}`, { method: 'POST' })
}

/** 文件产物下载地址（需要带登录令牌，走 apiBlob 拉取，不要直接当 href 用）。 */
export function artifactFilePath(runId: string, index: number): string {
  return `/api/v1/ci/runs/${encodeURIComponent(runId)}/artifacts/${index}/file`
}

/** 删除一条构建历史（只删记录，不动仓库与镜像）；运行中的会被后端拒绝。 */
export function deleteHistory(runId: string): Promise<{ deleted: boolean, message?: string }> {
  return apiFetch<{ deleted: boolean, message?: string }>(
    `/api/v1/ci/history/${encodeURIComponent(runId)}`, { method: 'DELETE' })
}
