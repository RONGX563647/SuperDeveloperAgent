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

/* ---------------------------------------------------------------------------
 * 事件协议（对齐 ruoyi-copilot 的 harness 事件溯源模型 + 本项目 AgentEventMapper）
 *
 * 后端 SSE 帧：`event:<type>\ndata:{"type":…,"payload":…,"sequence":…}\n\n`
 * reducer 只认稳定的事件 type 枚举；payload 尽力提取，缺字段不崩。
 * ------------------------------------------------------------------------- */

/** 运行状态（reducer 投影使用，超出枚举按 null 处理）。 */
export type RunStatus =
  | 'QUEUED' | 'RUNNING' | 'WAITING_FOR_APPROVAL' | 'WAITING_FOR_INPUT'
  | 'SUSPENDED' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

/** 传输层解析出的一条事件（SSE 帧 → 归一化后的事件对象）。 */
export interface AgentEvent {
  /** 事件类型：优先取 payload.type，退化为 SSE event: 行；error 帧固定为 'error'。 */
  type: string
  /** 单调递增序号（SSE id 或 payload.sequence），用于断线续传去重。 */
  sequence: number
  payload: Record<string, unknown>
}

/** 流式 assistant 正文/思考增量（reducer 投影）。 */
export interface StreamingTurn {
  effectId: string
  text: string
  thinking: string
  completed: boolean
}

/** 工具调用活动（reducer 投影）。 */
export interface ToolActivity {
  toolCallId: string
  toolName: string
  arguments?: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  code?: string
}

/** reducer 投影：消息列表 + 流式轮次 + 工具表 + 运行状态 + 最后序号。 */
export interface AgentProjection {
  lastSequence: number
  messages: ChatMessage[]
  streamingTurn: StreamingTurn | null
  tools: Record<string, ToolActivity>
  status: RunStatus | null
  pendingChangeId: string | null
}
