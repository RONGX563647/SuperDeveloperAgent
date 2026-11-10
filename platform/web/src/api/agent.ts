import { apiFetch } from './http'

/**
 * Agent 的系统提示词与运行画像（只读）。
 *
 * <p>轨迹视图用它回答"这个 Agent 现在被怎么约束"——提示词只描述角色与流程，
 * 真正的门禁在工具与变更确认层，所以这里也不返回任何密钥。
 */
export interface AgentProfile {
  name: string
  prompt: string
  projectId: string
  env: string
  userId: string
}

export function agentProfile(projectId: string, env: string): Promise<AgentProfile> {
  const q = new URLSearchParams({ projectId, env })
  return apiFetch(`/api/v1/agent/profile?${q}`)
}
