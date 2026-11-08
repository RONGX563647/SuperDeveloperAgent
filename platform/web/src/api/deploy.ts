import { apiFetch } from './http'

/**
 * 环境级部署接口：每个环境跑通过什么、重放、脚本版本、上服务器前的确认。
 */

export interface EnvStatus {
  env: 'dev' | 'test' | 'prod' | string
  recipeVersion: number
  evidenceRunId: string
  evidenceStatus: string
  gitSha: string
  createdAt: number
  recipeCount: number
  /** 仅 prod 返回：能否上服务器（需测试环境成功案例） */
  promotable?: boolean
  reason?: string
}

export function deployEnvs(projectId: string): Promise<EnvStatus[]> {
  return apiFetch(`/api/v1/deploy/${encodeURIComponent(projectId)}/envs`)
}

/** 上服务器前的确认：校验测试证据并签发一次性令牌。 */
export function deployConfirm(
  projectId: string,
  env: string,
  sha: string
): Promise<{
  allowed: boolean
  reason?: string
  confirmToken?: string
  changeId?: string
  evidence?: { env: string, version: number, runId: string, gitSha: string, status: string }
}> {
  const q = new URLSearchParams({ env, sha })
  return apiFetch(`/api/v1/deploy/${encodeURIComponent(projectId)}/confirm?${q}`, {
    method: 'POST'
  })
}

/** 重放：用该环境已固化的脚本确定性跑一次；prod 需传 confirmToken。 */
export function deployReplay(
  projectId: string,
  env: string,
  sha: string,
  confirmToken?: string
): Promise<{ runId: string, env: string, status: string }> {
  const q = new URLSearchParams({ env, sha })
  if (confirmToken) q.set('confirmToken', confirmToken)
  return apiFetch(`/api/v1/deploy/${encodeURIComponent(projectId)}/replay?${q}`, {
    method: 'POST'
  })
}

export interface RecipeVersion {
  version: number
  evidenceRunId: string
  gitSha: string
  createdAt: number
  createdBy: string
  content: string
}

export function deployRecipes(projectId: string, env: string): Promise<RecipeVersion[]> {
  const q = new URLSearchParams({ env })
  return apiFetch(`/api/v1/deploy/${encodeURIComponent(projectId)}/recipes?${q}`)
}

/** 把某个历史版本重新设为生效（只切脚本，不触发部署）。 */
export function activateRecipe(
  projectId: string,
  env: string,
  version: number
): Promise<{ ok: boolean }> {
  const q = new URLSearchParams({ env, version: String(version) })
  return apiFetch(`/api/v1/deploy/${encodeURIComponent(projectId)}/recipes/activate?${q}`, {
    method: 'POST'
  })
}
