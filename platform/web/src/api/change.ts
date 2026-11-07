import type { ChangeView, ConfirmResponse } from '../types'
import { apiFetch } from './http'

/** 确认卡片数据。 */
export function getChange(changeId: string): Promise<ChangeView> {
  return apiFetch<ChangeView>(`/api/v1/changes/${changeId}`)
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
