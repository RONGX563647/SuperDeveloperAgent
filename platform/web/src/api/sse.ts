import type { AgentEvent } from '../types'
import { notifyUnauthorized } from './auth'

/**
 * SSE 传输层（参考 ruoyi-copilot 的 harnessSse.ts 移植）：
 *
 * - fetch + ReadableStream 而非 EventSource：需要 POST / 自定义头 / 手动重连；
 * - SseFrameDecoder：跨 chunk 分帧、4 MiB 帧上限、支持 event/id/data/retry 字段；
 * - streamAgentEvents：指数退避重连（500ms → 8s），`afterSequence` 续传 +
 *   `Last-Event-ID` 头；4xx（非 408/429）不重试；event:error 帧直接抛出；
 * - streamChatOnce（POST 单流）：当前后端 /api/v1/chat 的形态。
 */

export interface SseFrame {
  id?: string
  event: string
  data: string
  retry?: number
}

export class SseProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SseProtocolError'
  }
}

/** 跨 chunk 的 SSE 帧解码器；帧超过 4 MiB 视为协议错误并清空缓冲。 */
export class SseFrameDecoder {
  private buffer = ''

  push(chunk: string, final = false): SseFrame[] {
    this.buffer += chunk
    const frames: SseFrame[] = []
    const blocks = this.buffer.split(/\r?\n\r?\n/)
    this.buffer = blocks.pop() ?? ''
    if (this.buffer.length > 4 * 1024 * 1024
      || blocks.some((block) => block.length > 4 * 1024 * 1024)) {
      this.buffer = ''
      throw new SseProtocolError('SSE 事件帧超过 4 MiB 安全上限')
    }
    for (const block of blocks) {
      const frame = parseFrame(block)
      if (frame) frames.push(frame)
    }
    if (final && this.buffer.trim()) {
      const frame = parseFrame(this.buffer)
      this.buffer = ''
      if (frame) frames.push(frame)
    }
    return frames
  }
}

function parseFrame(block: string): SseFrame | null {
  let event = 'message'
  let id: string | undefined
  let retry: number | undefined
  const data: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue // 注释/心跳
    const separator = line.indexOf(':')
    const field = separator < 0 ? line : line.slice(0, separator)
    let value = separator < 0 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value || 'message'
    if (field === 'id' && !value.includes('\0')) id = value
    if (field === 'data') data.push(value)
    if (field === 'retry' && /^\d+$/.test(value)) retry = Number(value)
  }
  if (!data.length) return null
  return { id, event, data: data.join('\n'), retry }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

async function readErrorPayload(response: Response): Promise<{ code?: number, message?: string }> {
  const payload = await response.clone().json().catch(() => null) as unknown
  if (!isRecord(payload)) {
    const text = await response.clone().text().catch(() => '')
    return text.trim() ? { message: text.slice(0, 200) } : {}
  }
  const rawCode = payload.code
  const code = typeof rawCode === 'number' ? rawCode
    : typeof rawCode === 'string' && /^\d+$/.test(rawCode) ? Number(rawCode) : undefined
  const rawMessage = payload.msg ?? payload.message
  return { code, message: typeof rawMessage === 'string' && rawMessage.trim() ? rawMessage : undefined }
}

function sequenceOf(frame: SseFrame, payload: Record<string, unknown>, fallback: number): number {
  const raw = payload.sequence
  if (typeof raw === 'number' && Number.isFinite(raw)) return raw
  if (typeof raw === 'string' && /^\d+$/.test(raw)) return Number(raw)
  if (frame.id && /^\d+$/.test(frame.id)) return Number(frame.id)
  return fallback
}

/**
 * 归一化 SSE 帧 → AgentEvent。
 * type 优先取 payload.type（AgentEventMapper 的稳定枚举），退化用 SSE event: 行。
 */
function normalizeAgentEvent(frame: SseFrame, fallbackSequence: number): AgentEvent {
  let parsed: unknown
  try {
    parsed = JSON.parse(frame.data) as unknown
  } catch {
    if (frame.event === 'error') {
      return { type: 'error', sequence: fallbackSequence, payload: { message: frame.data } }
    }
    throw new SseProtocolError(`无法解析 ${frame.event} SSE 事件`)
  }
  if (!isRecord(parsed)) throw new SseProtocolError('SSE 事件必须是对象')
  const type = typeof parsed.type === 'string' && parsed.type && parsed.type !== 'error'
    ? parsed.type
    : frame.event !== 'message' ? frame.event : 'error'
  return {
    type,
    sequence: sequenceOf(frame, parsed, fallbackSequence),
    payload: isRecord(parsed.payload) ? parsed.payload : {},
  }
}

/** 单次 POST 事件流（当前后端 /api/v1/chat 的形态；不重连，错误帧直接抛出）。 */
export async function streamChatOnce(
  url: string,
  init: RequestInit,
  onEvent: (event: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(url, { ...init, signal })
  if (!response.ok || !response.body) {
    if (response.status === 401) notifyUnauthorized()
    const payload = await readErrorPayload(response)
    throw new SseProtocolError(payload.message ?? `建立事件流失败（${response.status}）`)
  }
  const contentType = response.headers.get('content-type')?.toLowerCase() ?? ''
  if (!contentType.includes('text/event-stream')) {
    throw new SseProtocolError(`接口返回了非事件流 Content-Type: ${contentType || 'missing'}`)
  }
  const reader = response.body.getReader()
  const textDecoder = new TextDecoder()
  const decoder = new SseFrameDecoder()
  let fallbackSequence = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      const frames = decoder.push(
        textDecoder.decode(value ?? new Uint8Array(), { stream: !done }),
        done,
      )
      for (const frame of frames) {
        const event = normalizeAgentEvent(frame, ++fallbackSequence)
        // event:error 帧或 type === 'error'：流式失败，交给上层统一处理
        onEvent(event)
      }
      if (done) break
    }
  } finally {
    reader.releaseLock()
  }
}
