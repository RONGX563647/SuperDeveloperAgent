import { apiFetch, authHeaders } from './http'
import { streamChatOnce } from './sse'
import type { AgentEvent } from '../types'

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
  confirmToken?: string,
  deployTarget?: string,
  version?: string,
  tag?: boolean
): Promise<{ runId: string, env: string, status: string, deployTarget?: string }> {
  const q = new URLSearchParams({ env, sha })
  if (confirmToken) q.set('confirmToken', confirmToken)
  // 目标必须显式下发：不带时后端按固化脚本里的 target 执行，面板上选的方式会被静默丢弃
  if (deployTarget) q.set('deployTarget', deployTarget)
  // 版本号 + 打 tag 必须随重放下发：有固化脚本时主发布走的就是重放路径，
  // 少了这两个参数，用户填的版本/打的 tag 会在网关丢失（引擎按快照跑、历史空白）。
  if (version) q.set('version', version)
  if (tag) q.set('tag', 'true')
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

export interface DeployAgentRequest {
  env: string
  sha: string
  pipelineId?: string
  /** 已人工确认的环境绑定；Agent 只在绑定范围内执行。 */
  bindingId?: string
  sessionId?: string
}

/**
 * 发布 Agent：服务端按参数拼装任务，前端只消费事件流。
 *
 * <p>不传自由文本——任务内容是固定的「自检 → 构建 → 部署 → 验证」，
 * 与通用对话共用一套事件格式（agent 帧）。
 */
export async function streamDeployAgent(
  projectId: string,
  req: DeployAgentRequest,
  onEvent: (event: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  await streamChatOnce(
    `/api/v1/deploy/${encodeURIComponent(projectId)}/agent`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...authHeaders(),
      },
      body: JSON.stringify(req),
    },
    onEvent,
    signal,
  )
}
