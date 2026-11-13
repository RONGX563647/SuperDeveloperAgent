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
  | 'WAITING_MERGE'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'BLOCKED'
  | 'CANCELLED'

export interface BugFixRunArtifacts {
  analysis: string
  analysisJson: string
  fixSummary: string
  baseSha: string
  fixSha: string
  env: string
  prUrl: string
  evidence: string
}

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
  artifacts: BugFixRunArtifacts
  attempt: number
  createdAt: number
  updatedAt: number
  finishedAt: number | null
}

/** 单个阶段的执行记录：尝试次数、耗时与每阶段证据（验证失败回到修复后仍保留）。 */
export interface BugFixStageRunRow {
  id: number
  runId: string
  stage: BugFixStage
  attempt: number
  status: string
  message: string
  artifact: string
  startedAt: number
  endedAt: number
  durationMs: number
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
  stages: BugFixStageRunRow[]
}

export interface BugFixPolicyRow {
  enabled: boolean
  autoDeploy: boolean
  autoCreatePr: boolean
  testEnv: string
  bindingId: string
  maxAttempts: number
  baseUrl: string
  buildCommand: string
  testCommand: string
  updatedAt: number
}

export interface BugFixPolicyInput {
  enabled: boolean
  autoDeploy: boolean
  autoCreatePr: boolean
  testEnv: string
  bindingId: string
  maxAttempts: number
  baseUrl: string
  buildCommand: string
  testCommand: string
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

/** 读取策略；传 projectId 读取项目级覆盖（未配置时后端回退平台默认）。 */
export function getBugFixPolicy(projectId?: string): Promise<BugFixPolicyRow> {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''
  return apiFetch(`/api/v1/bug-fix/policy${qs}`)
}

export function saveBugFixPolicy(
  input: BugFixPolicyInput & { projectId?: string },
): Promise<BugFixPolicyRow> {
  return apiFetch('/api/v1/bug-fix/policy', {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}
