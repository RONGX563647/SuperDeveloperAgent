import type { ChangePatchView, ChangeView, ConfirmResponse } from '../types'
import { apiFetch } from './http'

/** 确认卡片数据。 */
export function getChange(changeId: string): Promise<ChangeView> {
  return apiFetch<ChangeView>(`/api/v1/changes/${changeId}`)
}

/** 补丁类变更的 diff（供人工复核）。 */
export function getChangePatch(changeId: string): Promise<ChangePatchView> {
  return apiFetch<ChangePatchView>(`/api/v1/changes/${encodeURIComponent(changeId)}/patch`)
}

/**
 * 提交人工确认，换取一次性令牌。
 *
 * 高危变更必须带密码；账号体系未接入时后端会失败关闭（返回 400）。
 */
export function confirmChange(changeId: string, password?: string): Promise<ConfirmResponse> {
  return apiFetch<ConfirmResponse>(`/api/v1/changes/${changeId}/confirm`, {
    method: 'POST',
    body: JSON.stringify({ password: password ?? null })
  })
}

/** 带令牌执行 L3 变更的动作结果（与 /actions/{action}/run 同构）。 */
export interface ApplyRunView {
  action: string
  status: string
  exitCode: number
  errorCode: string
  summary: string
  durationMs: number
  artifacts: Array<{ kind: string; ref: string; digest: string; sizeBytes: number }>
  logTail: string
}

/**
 * 凭一次性令牌执行变更（L3 的落地点）。
 *
 * 令牌一次性、5 分钟有效、绑定 planHash；配方在确认后被改动会被拒绝。
 */
export function applyChange(changeId: string, confirmToken: string): Promise<ApplyRunView> {
  return apiFetch<ApplyRunView>(`/api/v1/changes/${encodeURIComponent(changeId)}/apply`, {
    method: 'POST',
    headers: { 'X-Confirm-Token': confirmToken }
  })
}
