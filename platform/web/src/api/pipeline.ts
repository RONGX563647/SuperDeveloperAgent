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

/** 运行级产物。digest 可能为空串。 */
export interface ArtifactView {
  kind: string
  ref: string
  digest: string
  sizeBytes: number
}

/** GET /api/v1/ci/runs/{runId} 的响应；log 为脱敏后的日志尾部。 */
export interface RunView {
  runId: string
  status: string
  stages: StageView[]
  artifacts: ArtifactView[]
  log: string
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

/** 可选部署目标（平台部署器）。 */
export interface DeployTargetOption {
  target: string
  label: string
  available: boolean
  detail: string
}

export function listDeployTargets(): Promise<DeployTargetOption[]> {
  return apiFetch<DeployTargetOption[]>('/api/v1/ci/deploy/targets')
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

/** 一条构建历史摘要（按触发时间倒序）。 */
export interface HistoryItem {
  runId: string
  projectId: string
  pipelineId: string
  gitSha: string
  status: string
  /** 触发时间（epoch 毫秒，0 表示旧格式文件）。 */
  createdAt: number
  stageCount: number
}

/** 最近构建历史（最多 50 条）。 */
export function getHistory(projectId?: string): Promise<HistoryItem[]> {
  const query = projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''
  return apiFetch<HistoryItem[]>(`/api/v1/ci/history${query}`)
}
