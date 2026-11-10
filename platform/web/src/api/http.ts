/** 统一 HTTP 封装：注入登录令牌、解析统一错误体、附带 traceId。 */

import { getToken, notifyUnauthorized } from './auth'

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly traceId?: string
  ) {
    super(message)
  }
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken()
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.headers ?? {})
    }
  })
  if (!res.ok) {
    let message = `请求失败（${res.status}）`
    let traceId: string | undefined
    try {
      const body = await res.json()
      message = body.message ?? message
      traceId = body.traceId
    } catch {
      // 非 JSON 响应，保留默认文案
    }
    // 会话失效：清 token、清身份并跳登录页（只清 token 会让界面停留在"已登录"假象里）
    if (res.status === 401) notifyUnauthorized()
    throw new ApiError(traceId ? `${message}（traceId: ${traceId}）` : message, res.status, traceId)
  }
  return (await res.json()) as T
}

/** 下载二进制（带登录令牌）；调用方负责创建/释放 object URL。 */
export async function apiBlob(path: string): Promise<Blob> {
  const token = getToken()
  const res = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  if (!res.ok) {
    let message = `下载失败（${res.status}）`
    let traceId: string | undefined
    try {
      const body = await res.json()
      message = body.message ?? message
      traceId = body.traceId
    } catch {
      // 非 JSON 响应，保留默认文案
    }
    if (res.status === 401) notifyUnauthorized()
    throw new ApiError(traceId ? `${message}（traceId: ${traceId}）` : message, res.status, traceId)
  }
  return res.blob()
}
