/** 后端协议类型（与 agent-gateway 的 DTO 对齐）。 */

import type { SessionCost, UsageCost } from './utils/deepseekPricing'

export type { SessionCost, UsageCost }

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
  /** 助手思考过程（与正文一起按 SSE 增量累积，完成后保留供折叠查看） */
  thinking?: string
  /** 工具调用等步骤摘要 */
  label?: string
  /** 步骤消息对应的工具调用 id（用于回查工具结果） */
  toolCallId?: string
  /** 该条消息对应的模型费用（仅 deepseek 系列且拿到 usage 时存在） */
  cost?: UsageCost
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
  /** 本次模型调用的费用（模型调用结束时到达，落盘消息时一并带走） */
  cost?: UsageCost
}

/** 工具调用活动（reducer 投影）。 */
export interface ToolActivity {
  toolCallId: string
  toolName: string
  arguments?: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  code?: string
  /** 工具返回的文本（流式累积；用于展示"这一步实际拿到了什么"） */
  resultText?: string
}

/** 轨迹中的一步（由消息派生，不落盘）。 */
export interface TraceStep {
  id: string
  /** text = 模型正文片段；tool = 工具调用；error = 运行错误。 */
  kind: 'text' | 'tool' | 'error'
  title: string
  text: string
  toolCallId?: string
  at: number
  /** 估算耗时：本条到下一步（或轮次结束）的间隔，用于时间轴比例。 */
  durationMs: number
  status?: 'RUNNING' | 'COMPLETED' | 'FAILED'
}

/**
 * 轨迹导航的一轮（由 messages 派生，不落盘）。
 *
 * <p>一轮 = 一条 user 消息 + 其后到下一个 user 之前的所有 assistant / step 消息，
 * 用于右侧定位条与轨迹视图：列表看步数/工具数，时间轴看模型与工具的时间分布。
 */
export interface TraceTurn {
  /** 该轮 user 消息 id（同时也作为滚动锚点）。 */
  id: string
  /** 从 1 开始的轮次序号。 */
  index: number
  /** 问题摘要（单行，已截断）。 */
  question: string
  /** 问题原文（详情面板展示）。 */
  userText: string
  at: number
  /** 首条事件时间（= at）。 */
  startedAt: number
  /** 末条事件时间（无后续消息时等于 at）。 */
  endedAt: number
  endAt: number
  /** 正文 + 工具步骤总数。 */
  steps: number
  /** 其中工具调用数。 */
  tools: number
  failed: boolean
  /** 微条分段：text = 正文，tool = 工具调用（按时间先后）。 */
  segments: Array<'text' | 'tool'>
  /** 逐步明细：轨迹视图的列表展开与右侧详情。 */
  items: TraceStep[]
}

/** reducer 投影：消息列表 + 流式轮次 + 工具表 + 运行状态 + 最后序号。 */
export interface AgentProjection {
  lastSequence: number
  messages: ChatMessage[]
  streamingTurn: StreamingTurn | null
  tools: Record<string, ToolActivity>
  status: RunStatus | null
  pendingChangeId: string | null
  /** 本会话累计费用（本地计算，仅 deepseek 系列累加） */
  sessionCost: SessionCost
}
