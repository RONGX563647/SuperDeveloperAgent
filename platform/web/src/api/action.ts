import { apiFetch } from './http'

/** GET /api/v1/actions 的单项；commands 是命令原文（后端已把 argv 拼成一行）。 */
export interface ActionView {
  action: string
  level: string
  source: string
  humanAuthored: boolean
  commands: string[]
  produce: string[]
  timeoutMinutes: number
}

/** POST /api/v1/actions/{action}/run 的执行结果。 */
export interface ActionRunView {
  action: string
  status: string
  exitCode: number
  errorCode: string
  summary: string
  durationMs: number
  artifacts: Array<{ kind: string; ref: string; digest: string; sizeBytes: number }>
  logTail: string
}

/** POST /api/v1/actions/{action}/request 的变更登记结果。 */
export interface ActionRequestView {
  changeId: string
  projectId: string
  env: string
  kind: string
  summary: string
  planHash: string
  highRisk: boolean
}

/** 列出项目/环境下可用动作。 */
export function listActions(projectId: string, env: string): Promise<ActionView[]> {
  const query = new URLSearchParams({ projectId, env })
  return apiFetch<ActionView[]>(`/api/v1/actions?${query}`)
}

/** 查看某动作的计划（命令原文、来源、超时、产物）。 */
export function getActionPlan(projectId: string, action: string): Promise<ActionView> {
  const query = new URLSearchParams({ projectId })
  return apiFetch<ActionView>(`/api/v1/actions/${encodeURIComponent(action)}/plan?${query}`)
}

/** 直接执行 L2 动作；生产环境会被后端拒绝。 */
export function runAction(projectId: string, env: string, action: string): Promise<ActionRunView> {
  const query = new URLSearchParams({ projectId, env })
  return apiFetch<ActionRunView>(`/api/v1/actions/${encodeURIComponent(action)}/run?${query}`, {
    method: 'POST'
  })
}

/** 登记 L3 变更，返回 changeId / planHash，后续走变更确认流程。 */
export function requestAction(projectId: string, env: string, action: string): Promise<ActionRequestView> {
  const query = new URLSearchParams({ projectId, env })
  return apiFetch<ActionRequestView>(`/api/v1/actions/${encodeURIComponent(action)}/request?${query}`, {
    method: 'POST'
  })
}
