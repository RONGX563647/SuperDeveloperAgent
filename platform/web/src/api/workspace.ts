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
