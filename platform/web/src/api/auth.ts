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

/** 登录：成功后 token 自动写入本地。 */
export async function login(username: string, password: string): Promise<LoginResult> {
  const result = await apiFetch<LoginResult>('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password })
  })
  setToken(result.token)
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
