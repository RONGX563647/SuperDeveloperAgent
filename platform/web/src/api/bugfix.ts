import { apiFetch } from './http'

/** Bug 自动修复的阶段（与后端 BugFixStage 对齐）。 */
export type BugFixStage =
  | 'ANALYZE'
  | 'REPRO_DEPLOY'
  | 'REPRODUCE'
  | 'FIX'
  | 'VERIFY_DEPLOY'
  | 'VERIFY'
  | 'PULL_REQUEST'
  | 'DONE'

export type BugFixStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'BLOCKED'
  | 'CANCELLED'

export interface BugFixRunRow {
  id: string
  issueId: string
  userId: string
  projectId: string
  provider: string
  repo: string
  issueNumber: string
  issueTitle: string
  issueBody: string
  branch: string
  stage: BugFixStage
  status: BugFixStatus
  message: string
  analysis: string
  baseSha: string
  fixSha: string
  env: string
  prUrl: string
  evidence: string
  attempt: number
  createdAt: number
  updatedAt: number
  finishedAt: number | null
}

export interface BugFixEventRow {
  id: string
  runId: string
  stage: BugFixStage
  level: 'INFO' | 'WARN' | 'ERROR' | string
  message: string
  createdAt: number
}

export interface BugFixRunDetail {
  run: BugFixRunRow
  progress: number
  resumable: boolean
  events: BugFixEventRow[]
}

export interface BugFixPolicyRow {
  enabled: boolean
  autoDeploy: boolean
  autoCreatePr: boolean
  testEnv: string
  bindingId: string
  maxAttempts: number
  updatedAt: number
}

export interface BugFixPolicyInput {
  enabled: boolean
  autoDeploy: boolean
  autoCreatePr: boolean
  testEnv: string
  bindingId: string
  maxAttempts: number
}

export function listBugFixRuns(issueId?: string): Promise<BugFixRunRow[]> {
  const qs = issueId ? `?issueId=${encodeURIComponent(issueId)}` : ''
  return apiFetch(`/api/v1/bug-fix/runs${qs}`)
}

export function getBugFixRun(id: string): Promise<BugFixRunDetail> {
  return apiFetch(`/api/v1/bug-fix/runs/${encodeURIComponent(id)}`)
}

export function startBugFix(issueId: string): Promise<BugFixRunRow> {
  return apiFetch('/api/v1/bug-fix/runs', {
    method: 'POST',
    body: JSON.stringify({ issueId }),
  })
}

export function retryBugFix(id: string): Promise<BugFixRunRow> {
  return apiFetch(`/api/v1/bug-fix/runs/${encodeURIComponent(id)}/retry`, { method: 'POST' })
}

export function cancelBugFix(id: string): Promise<BugFixRunRow> {
  return apiFetch(`/api/v1/bug-fix/runs/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
}

export function getBugFixPolicy(): Promise<BugFixPolicyRow> {
  return apiFetch('/api/v1/bug-fix/policy')
}

export function saveBugFixPolicy(input: BugFixPolicyInput): Promise<BugFixPolicyRow> {
  return apiFetch('/api/v1/bug-fix/policy', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}
