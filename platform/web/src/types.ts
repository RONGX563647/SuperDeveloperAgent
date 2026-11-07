/** 后端协议类型（与 agent-gateway 的 DTO 对齐）。 */

export interface ChatRequest {
  projectId: string
  env: string
  sessionId?: string
  message: string
}

/** 一条 SSE 事件：type 为后端事件类名，payload 为序列化后的事件体。 */
export interface AgentEventDto {
  type: string
  payload: Record<string, unknown>
}

export type MessageRole = 'user' | 'assistant' | 'step' | 'error'

export interface ChatMessage {
  id: string
  role: MessageRole
  text: string
  /** 工具调用等步骤摘要 */
  label?: string
  at: number
}

/** 确认卡片（GET /api/v1/changes/{id}）。 */
export interface ChangeView {
  changeId: string
  projectId: string
  env: string
  kind: string
  summary: string
  imageDigest: string | null
  highRisk: boolean
  planHash: string
  gate: 'CONFIRM_TOKEN' | 'SECOND_PASSWORD'
}

/** 确认结果（POST /api/v1/changes/{id}/confirm）。 */
export interface ConfirmResponse {
  confirmToken: string
  changeId: string
  planHash: string
  expiresAt: string
  meta: Record<string, string>
}
