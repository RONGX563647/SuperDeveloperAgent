import { apiFetch } from './http'

/** 平台任务页的 Issue 任务（与 Git 双向同步）。 */
export type GitIssueProvider = 'GITEE' | 'GITHUB' | 'GITLAB' | ''
export type GitIssueState = 'OPEN' | 'CLOSED'

export interface GitIssueRow {
  id: string
  userId: string
  projectId: string
  provider: GitIssueProvider
  repo: string
  /** 远端编号；Gitee 形如 I1ABCD，GitHub / GitLab 为数字；空 = 仅本地 */
  number: string
  title: string
  body: string
  state: GitIssueState
  labels: string
  url: string
  source: 'PLATFORM' | 'AGENT' | 'GIT' | string
  syncStatus: 'SYNCED' | 'LOCAL' | 'FAILED' | string
  syncError: string
  assignee: string
  createdAt: number
  updatedAt: number
}

export interface GitIssueCommentRow {
  id: string
  issueId: string
  author: string
  body: string
  source: 'PLATFORM' | 'AGENT' | 'GIT' | string
  createdAt: number
}

export interface GitIssueInput {
  projectId?: string
  provider?: GitIssueProvider
  repo?: string
  title: string
  body?: string
  state?: GitIssueState
  labels?: string
  assignee?: string
}

export interface GitIssueQuery {
  provider?: string
  state?: string
  keyword?: string
}

export function listGitIssues(query: GitIssueQuery = {}): Promise<GitIssueRow[]> {
  const params = new URLSearchParams()
  if (query.provider) params.set('provider', query.provider)
  if (query.state) params.set('state', query.state)
  if (query.keyword) params.set('keyword', query.keyword)
  const qs = params.toString()
  return apiFetch(`/api/v1/git-issues${qs ? `?${qs}` : ''}`)
}

export function getGitIssue(id: string): Promise<GitIssueRow> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}`)
}

export function createGitIssue(input: GitIssueInput): Promise<GitIssueRow> {
  return apiFetch('/api/v1/git-issues', { method: 'POST', body: JSON.stringify(input) })
}

export function updateGitIssue(id: string, input: GitIssueInput): Promise<GitIssueRow> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export function deleteGitIssue(id: string): Promise<{ deleted: boolean }> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function closeGitIssue(id: string): Promise<GitIssueRow> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}/close`, { method: 'POST' })
}

export function reopenGitIssue(id: string): Promise<GitIssueRow> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}/reopen`, { method: 'POST' })
}

export function syncGitIssue(id: string): Promise<GitIssueRow> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}/sync`, { method: 'POST' })
}

export function listGitIssueComments(id: string): Promise<GitIssueCommentRow[]> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}/comments`)
}

export function addGitIssueComment(id: string, body: string): Promise<GitIssueCommentRow> {
  return apiFetch(`/api/v1/git-issues/${encodeURIComponent(id)}/comments`, {
    method: 'POST',
    body: JSON.stringify({ body }),
  })
}

export function deleteGitIssueComment(
  id: string,
  commentId: string
): Promise<{ deleted: boolean }> {
  return apiFetch(
    `/api/v1/git-issues/${encodeURIComponent(id)}/comments/${encodeURIComponent(commentId)}`,
    { method: 'DELETE' }
  )
}

// ---------------------------------------------------------------- Issue 同步配置

export interface GitIssueIntegrationView {
  provider: Exclude<GitIssueProvider, ''>
  enabled: boolean
  repoWhitelist: string
  hasWebhookSecret: boolean
  webhookSecretMasked: string | null
  autoCreateTask: boolean
  syncDirection: 'BIDIRECTIONAL' | 'PUSH_ONLY' | 'PULL_ONLY'
  conflictPolicy: 'GIT_WINS' | 'PLATFORM_WINS'
  updatedAt: number
  webhookUrl: string
}

export interface GitIssueIntegrationInput {
  enabled: boolean
  repoWhitelist: string
  autoCreateTask: boolean
  syncDirection: string
  conflictPolicy: string
  /** 留空 = 不修改已保存的密钥 */
  webhookSecret?: string
}

export function listGitIssueIntegrations(): Promise<GitIssueIntegrationView[]> {
  return apiFetch('/api/v1/settings/git-integration')
}

export function saveGitIssueIntegration(
  provider: string,
  input: GitIssueIntegrationInput
): Promise<GitIssueIntegrationView> {
  return apiFetch(`/api/v1/settings/git-integration/${encodeURIComponent(provider)}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export function deleteGitIssueIntegration(provider: string): Promise<{ deleted: boolean }> {
  return apiFetch(`/api/v1/settings/git-integration/${encodeURIComponent(provider)}`, {
    method: 'DELETE',
  })
}
