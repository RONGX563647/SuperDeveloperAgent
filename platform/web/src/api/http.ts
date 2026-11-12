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

/** 后端统一错误体（兼容 msg / message 两种字段名）。 */
export interface ErrorPayload {
  code?: number
  message?: string
  traceId?: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * 解析错误响应体。http / sse 两条链路共用，避免后端改字段名时三处各改各的。
 *
 * <p>响应可能已被读取，因此一律走 clone()。
 */
export async function parseErrorPayload(response: Response): Promise<ErrorPayload> {
  const payload = await response.clone().json().catch(() => null) as unknown
  if (!isRecord(payload)) {
    const text = await response.clone().text().catch(() => '')
    return text.trim() ? { message: text.slice(0, 200) } : {}
  }
  const rawCode = payload.code
  const code = typeof rawCode === 'number' ? rawCode
    : typeof rawCode === 'string' && /^\d+$/.test(rawCode) ? Number(rawCode) : undefined
  const rawMessage = payload.msg ?? payload.message
  const traceId = typeof payload.traceId === 'string' && payload.traceId ? payload.traceId : undefined
  return {
    code,
    message: typeof rawMessage === 'string' && rawMessage.trim() ? rawMessage : undefined,
    traceId
  }
}

async function throwIfNotOk(res: Response, fallback: string): Promise<void> {
  if (res.ok) return
  const { message, traceId } = await parseErrorPayload(res)
  const text = message ?? `${fallback}（${res.status}）`
  // 会话失效：清 token、清身份并跳登录页（只清 token 会让界面停留在"已登录"假象里）
  if (res.status === 401) notifyUnauthorized()
  throw new ApiError(traceId ? `${text}（traceId: ${traceId}）` : text, res.status, traceId)
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
  await throwIfNotOk(res, '请求失败')
  return (await res.json()) as T
}

/** 下载二进制（带登录令牌）；调用方负责创建/释放 object URL。 */
export async function apiBlob(path: string): Promise<Blob> {
  const token = getToken()
  const res = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  await throwIfNotOk(res, '下载失败')
  return res.blob()
}
