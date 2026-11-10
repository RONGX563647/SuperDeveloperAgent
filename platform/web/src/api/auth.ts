import { apiFetch } from './http'

/** 登录响应。 */
export interface LoginResult {
  token: string
  userId: string
  displayName: string
  role: string
  permissions: string[]
  projects: string[]
}

/** 当前身份（校验已存 token 是否仍有效）。 */
export interface MeResult {
  userId: string
  displayName: string
  role: string
  permissions: string[]
}

const TOKEN_KEY = 'agent.token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

/** 会话失效的统一回调（由 router 注入：清身份 + 跳登录页）。 */
let unauthorizedHandler: (() => void) | null = null
/** 并发请求同时 401 时只处理一次，避免重复跳转/重复提示。 */
let unauthorizedNotified = false

export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

/**
 * 会话已失效（接口返回 401）：
 * 清除本地 token 并通知路由层把用户送回登录页。
 *
 * <p>只清 token 不清身份会让界面保持"已登录"的样子，
 * 后续每个请求都继续 401 —— 这正是"明明登录了还提示未登录"的来源。
 */
export function notifyUnauthorized(): void {
  if (unauthorizedNotified) return
  unauthorizedNotified = true
  setToken('')
  unauthorizedHandler?.()
}

/** 登录：成功后 token 自动写入本地。 */
export async function login(username: string, password: string): Promise<LoginResult> {
  const result = await apiFetch<LoginResult>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })
  setToken(result.token)
  unauthorizedNotified = false
  return result
}

/** 登出：清本地 token + 通知后端销毁会话。 */
export async function logout(): Promise<void> {
  try {
    await apiFetch('/api/v1/auth/logout', { method: 'POST' })
  } finally {
    setToken('')
  }
}

/** 用已存 token 换当前身份；token 失效会抛 ApiError(401)。 */
export function me(): Promise<MeResult> {
  return apiFetch<MeResult>('/api/v1/auth/me', { method: 'POST' })
}
