import { apiBlob, apiFetch } from './http'
import { getToken } from './auth'

/** 平台任务页的 Issue 任务（与 Git 双向同步）。 */
export type GitIssueProvider = 'GITEE' | 'GITHUB' | 'GITLAB' | ''
export type GitIssueState = 'OPEN' | 'CLOSED'
/** 任务分类：缺陷 / 需求。 */
export type GitIssueKind = 'BUG' | 'FEAT'
/** 任务优先级：P0 最高。 */
export type GitIssuePriority = 'P0' | 'P1' | 'P2'

export interface GitIssueRow {
  id: string
  /** 任务编码：项目内可读编号（FEAT-1 / BUG-3），创建后不变。 */
  code: string
  userId: string
  projectId: string
  provider: GitIssueProvider
  repo: string
  /** 远端编号；Gitee 形如 I1ABCD，GitHub / GitLab 为数字；空 = 仅本地 */
  number: string
  title: string
  body: string
  state: GitIssueState
  kind: GitIssueKind
  priority: GitIssuePriority
  labels: string
  /** 关联版本号（如 0.1.2）；空 = 未关联，创建时默认取当前分支版本。 */
  version: string
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
  kind?: GitIssueKind
  priority?: GitIssuePriority
  labels?: string
  /** 关联版本号；留空 = 创建时取当前分支版本 / 更新时保持原值。 */
  version?: string
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

/** 版本号候选项：当前分支推导的建议值 + 仓库现有标签。 */
export interface GitIssueVersions {
  branch: string
  /** 当前分支推导出的默认版本；空 = 仓库不可用，只能手输 */
  suggested: string
  tags: string[]
}

export function getGitIssueVersions(projectId: string): Promise<GitIssueVersions> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''
  return apiFetch(`/api/v1/git-issues/versions${qs}`)
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

// ---------------------------------------------------------------- 任务配图

/**
 * 上传一张任务配图，返回可直接写进 Markdown 的地址。
 *
 * 不能用 apiFetch：它会强制 JSON Content-Type，multipart 需要浏览器自己带 boundary。
 */
export async function uploadIssueImage(file: File): Promise<string> {
  const body = new FormData()
  body.append('file', file)
  const token = getToken()
  const res = await fetch('/api/v1/git-issues/images', {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body,
  })
  if (!res.ok) {
    let message = `图片上传失败（${res.status}）`
    try {
      const payload = await res.json()
      message = payload.message ?? message
    } catch {
      // 非 JSON 响应，保留默认文案
    }
    throw new Error(message)
  }
  const data = (await res.json()) as { url: string }
  return data.url
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

/**
 * 把「当前筛选出的任务」导出为 xlsx。
 *
 * <p>传 id 而不是筛选条件：类型 / 优先级 / 版本 / 排序都在前端过滤，后端重算会把
 * 同一套规则写两份，迟早出现"界面 20 条、导出 23 条"。只认 id 才能保证所见即所得。
 */
export function exportGitIssues(ids: string[]): Promise<Blob> {
  return apiBlob('/api/v1/git-issues/export', {
    method: 'POST',
    body: JSON.stringify({ ids }),
  })
}
