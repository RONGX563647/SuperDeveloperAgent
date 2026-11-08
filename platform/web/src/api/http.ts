/** 统一 HTTP 封装：注入登录令牌、解析统一错误体、附带 traceId。 */

import { getToken, setToken } from './auth'

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
    // 会话失效：清掉本地 token，让路由守卫弹登录页
    if (res.status === 401) setToken('')
    throw new ApiError(traceId ? `${message}（traceId: ${traceId}）` : message, res.status, traceId)
  }
  return (await res.json()) as T
}
