/** 工作区（= 项目）会话 / 任务 / 运行接口。 */

import { apiFetch } from './http'

export interface ConversationRow {
  id: string
  projectId: string
  title: string
  kind: 'NORMAL' | 'SUPERVISOR' | 'BUGFIX'
  role: string
  env: string
  worktree: string | null
  status: 'IDLE' | 'RUNNING' | 'NEEDS_CONFIRMATION' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  summary: string
  pinned: boolean
  archived: boolean
  lastActivityAt: number
  createdAt: number
  /** 本会话选择的模型连接与模型名（null = 设置页默认） */
  modelConnectionId: string | null
  modelName: string | null
}

export interface RunRow {
  id: string
  projectId: string
  conversationId: string
  goal: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  result: string
  error: string
  createdAt: number
  startedAt: number
  endedAt: number
}

export interface TaskRow {
  id: string
  projectId: string
  conversationId: string | null
  goal: string
  expectedOutput: string
  status: 'TODO' | 'RUNNING' | 'BLOCKED' | 'DONE' | 'CANCELLED'
  priority: number
  createdAt: number
  updatedAt: number
}

export function listConversations(projectId: string, includeArchived = false): Promise<ConversationRow[]> {
  const query = includeArchived ? '?includeArchived=true' : ''
  return apiFetch(`/api/v1/workspaces/${projectId}/conversations${query}`)
}

export function createConversation(
  projectId: string,
  body: { title?: string; env?: string; worktree?: string } = {},
): Promise<ConversationRow> {
  return apiFetch(`/api/v1/workspaces/${projectId}/conversations`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function getSupervisor(projectId: string): Promise<ConversationRow> {
  return apiFetch(`/api/v1/workspaces/${projectId}/super`)
}

export function syncConversations(
  projectId: string,
  sessions: Array<{ id: string; title: string; at: number }>,
): Promise<{ imported: number; skipped: number; rejected: string[] }> {
  return apiFetch(`/api/v1/workspaces/${projectId}/conversations/sync`, {
    method: 'POST',
    body: JSON.stringify({ sessions }),
  })
}

export function updateConversation(
  conversationId: string,
  patch: {
    title?: string
    env?: string
    worktree?: string
    pinned?: boolean
    archived?: boolean
    modelConnectionId?: string | null
    modelName?: string | null
  },
): Promise<ConversationRow> {
  return apiFetch(`/api/v1/conversations/${conversationId}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  })
}

export function deleteConversation(conversationId: string): Promise<{ deleted: boolean }> {
  return apiFetch(`/api/v1/conversations/${conversationId}`, { method: 'DELETE' })
}

export function sendToConversation(conversationId: string, message: string): Promise<{ runId: string }> {
  return apiFetch(`/api/v1/conversations/${conversationId}/send`, {
    method: 'POST',
    body: JSON.stringify({ message }),
  })
}

/** 会话时间线（消息 + 工具结果）从数据库恢复；found=false 表示服务端还没有。 */
export interface ConversationTranscript {
  found: boolean
  payload: string | null
}

export function getConversationTranscript(conversationId: string): Promise<ConversationTranscript> {
  return apiFetch(`/api/v1/conversations/${conversationId}/messages`)
}

export function saveConversationTranscript(
  conversationId: string,
  payload: string,
  keepalive = false,
): Promise<{ saved: boolean }> {
  return apiFetch(`/api/v1/conversations/${conversationId}/messages`, {
    method: 'PUT',
    body: JSON.stringify({ payload }),
    keepalive,
  })
}

export function listRuns(projectId: string, limit = 50): Promise<RunRow[]> {
  return apiFetch(`/api/v1/workspaces/${projectId}/runs?limit=${limit}`)
}

/** 跨引擎运行账本（对话/修复/流水线/发布），用于「最近运行」一屏查看失败。 */
export interface RunLedgerRow {
  id: string
  kind: 'CHAT' | 'DEPLOY' | 'WORKSPACE' | 'BUGFIX' | 'PIPELINE'
  projectId: string
  subjectId: string
  state: 'QUEUED' | 'RUNNING' | 'BLOCKED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  summary: string
  error: string
  createdAt: number
  updatedAt: number
}

export function listRunLedger(projectId: string, limit = 50): Promise<RunLedgerRow[]> {
  return apiFetch(`/api/v1/run-ledger?projectId=${encodeURIComponent(projectId)}&limit=${limit}`)
}

export function listConversationRuns(conversationId: string, limit = 20): Promise<RunRow[]> {
  return apiFetch(`/api/v1/conversations/${conversationId}/runs?limit=${limit}`)
}

export function cancelRun(runId: string): Promise<{ cancelled: boolean }> {
  return apiFetch(`/api/v1/runs/${runId}/cancel`, { method: 'POST' })
}

export function listTasks(projectId: string): Promise<TaskRow[]> {
  return apiFetch(`/api/v1/workspaces/${projectId}/tasks`)
}

/** 环境矩阵：一个版本在某环境里的部署产物与运行状态。 */
export interface DeploymentEnvColumn {
  key: string
  label: string
}

export interface DeploymentCell {
  env: string
  version: string
  state: 'RUNNING' | 'STOPPED' | 'UNKNOWN'
  target: 'DOCKER' | 'HOST'
  running: boolean
  current: boolean
  containers: string[]
  image: string | null
  health: string | null
  ports: string | null
  url: string | null
  deployedAt: number
  detail: string
  /** 当前发布提交（HOST 的 current 指针）。 */
  release: string | null
  /** HOST 进程 pid（仅运行中）。 */
  pid: number
  /** 远程部署服务器名（SERVER 目标；本地部署为空）。 */
  server?: string | null
}

/** 保留的镜像版本（来自镜像账本，可按版本重新部署）。 */
export interface RetainedImage {
  tag: string
  version: string
  gitSha: string
  runId: string
  sizeBytes: number
  status: string
  createdAt: number
  deployedAt: number
}

export interface DeploymentMatrix {
  environments: DeploymentEnvColumn[]
  versions: string[]
  cells: DeploymentCell[]
  dockerAvailable: boolean
  note: string
  images?: RetainedImage[]
}

export interface DeploymentActionResult {
  ok: boolean
  message: string
  state: string
}

export function getDeploymentMatrix(projectId: string): Promise<DeploymentMatrix> {
  return apiFetch(`/api/v1/workspaces/${projectId}/deployments`)
}

export function startDeployment(
  projectId: string,
  env: string,
  version?: string,
): Promise<DeploymentActionResult> {
  return apiFetch(`/api/v1/workspaces/${projectId}/deployments/${env}/start`, {
    method: 'POST',
    body: JSON.stringify({ version: version ?? null }),
  })
}

export function stopDeployment(
  projectId: string,
  env: string,
  version?: string,
): Promise<DeploymentActionResult> {
  return apiFetch(`/api/v1/workspaces/${projectId}/deployments/${env}/stop`, {
    method: 'POST',
    body: JSON.stringify({ version: version ?? null }),
  })
}

/** 重启：沿用原端口，只有端口被占用才漂移。 */
export function restartDeployment(
  projectId: string,
  env: string,
  version?: string,
): Promise<DeploymentActionResult> {
  return apiFetch(`/api/v1/workspaces/${projectId}/deployments/${env}/restart`, {
    method: 'POST',
    body: JSON.stringify({ version: version ?? null }),
  })
}

/** 运行日志：DOCKER 为容器日志，HOST 为 app.log 尾部。 */
export interface DeploymentLogs {
  source: 'DOCKER' | 'HOST'
  container: string | null
  containers: string[]
  lines: string[]
  note: string
}

export function getDeploymentLogs(
  projectId: string,
  env: string,
  params: { version?: string; container?: string; tail?: number; sinceMinutes?: number } = {},
): Promise<DeploymentLogs> {
  const query = new URLSearchParams()
  if (params.version) query.set('version', params.version)
  if (params.container) query.set('container', params.container)
  if (params.tail) query.set('tail', String(params.tail))
  if (params.sinceMinutes) query.set('sinceMinutes', String(params.sinceMinutes))
  const qs = query.toString()
  return apiFetch(
    `/api/v1/workspaces/${projectId}/deployments/${env}/logs${qs ? `?${qs}` : ''}`,
  )
}

export function createTask(
  projectId: string,
  body: { goal: string; expectedOutput?: string; priority?: number; conversationId?: string },
): Promise<TaskRow> {
  return apiFetch(`/api/v1/workspaces/${projectId}/tasks`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateTask(
  projectId: string,
  taskId: string,
  body: { goal?: string; expectedOutput?: string; status?: string },
): Promise<TaskRow> {
  return apiFetch(`/api/v1/workspaces/${projectId}/tasks/${taskId}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}
