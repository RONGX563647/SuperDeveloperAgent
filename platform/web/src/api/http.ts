/** 统一 HTTP 封装：注入用户标识、解析统一错误体、附带 traceId。 */

const USER_KEY = 'agent.userId'

/** 骨架阶段用本地标识代替登录态；接入账号体系后改为从会话/Token 取。 */
export function currentUserId(): string {
  let id = localStorage.getItem(USER_KEY)
  if (!id) {
    id = 'dev-' + Math.random().toString(36).slice(2, 8)
    localStorage.setItem(USER_KEY, id)
  }
  return id
}

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
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': currentUserId(),
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
    throw new ApiError(traceId ? `${message}（traceId: ${traceId}）` : message, res.status, traceId)
  }
  return (await res.json()) as T
}
