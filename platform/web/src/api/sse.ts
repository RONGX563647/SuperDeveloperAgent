import type { AgentEvent } from '../types'

/**
 * SSE 传输层（参考 ruoyi-copilot 的 harnessSse.ts 移植）：
 *
 * - fetch + ReadableStream 而非 EventSource：需要 POST / 自定义头 / 手动重连；
 * - SseFrameDecoder：跨 chunk 分帧、4 MiB 帧上限、支持 event/id/data/retry 字段；
 * - streamAgentEvents：指数退避重连（500ms → 8s），`afterSequence` 续传 +
 *   `Last-Event-ID` 头；4xx（非 408/429）不重试；event:error 帧直接抛出；
 * - 后端当前是 POST /api/v1/chat 单流（无 runId 游标），因此本模块同时暴露
 *   streamChatOnce（POST 单流）与 streamEvents（GET 续传流，后端补端点后启用）。
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

class NonRetryableStreamError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NonRetryableStreamError'
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
export function normalizeAgentEvent(frame: SseFrame, fallbackSequence: number): AgentEvent {
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

export interface AgentEventStreamOptions {
  /** 断线续传游标；后端支持 afterSequence 时才生效。 */
  afterSequence?: number
  signal: AbortSignal
  onEvent: (event: AgentEvent) => void | Promise<void>
  onConnectionChange?: (state: 'CONNECTING' | 'OPEN' | 'RECONNECTING' | 'CLOSED') => void
  fetchImpl?: typeof fetch
  initialRetryMillis?: number
  maxRetryMillis?: number
}

const terminalEvents = new Set(['run.completed', 'run.failed', 'run.cancelled'])

function abortError(): DOMException {
  return new DOMException('The operation was aborted', 'AbortError')
}

function wait(milliseconds: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.reject(abortError())
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      globalThis.clearTimeout(timeout)
      reject(abortError())
    }
    const timeout = globalThis.setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, milliseconds)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * GET 事件流（带重连 + 续传）。后端补齐 `GET /api/v1/runs/{runId}/events/stream` 后启用；
 * 终端事件（run.completed/failed/cancelled）到达后正常返回。
 */
export async function streamEvents(
  url: string,
  headersInit: HeadersInit,
  options: AgentEventStreamOptions,
): Promise<number> {
  const fetchImpl = options.fetchImpl ?? fetch
  let cursor = Math.max(0, options.afterSequence ?? 0)
  let retryMillis = Math.max(10, options.initialRetryMillis ?? 500)
  const maxRetryMillis = Math.max(retryMillis, options.maxRetryMillis ?? 8_000)
  let firstAttempt = true

  while (!options.signal.aborted) {
    options.onConnectionChange?.(firstAttempt ? 'CONNECTING' : 'RECONNECTING')
    const query = new URLSearchParams({ afterSequence: String(cursor) })
    const headers = new Headers(headersInit)
    headers.set('Accept', 'text/event-stream')
    if (cursor > 0) headers.set('Last-Event-ID', String(cursor))

    try {
      const response = await fetchImpl(`${url}${url.includes('?') ? '&' : '?'}${query}`, {
        method: 'GET',
        headers,
        signal: options.signal,
      })
      if (!response.ok || !response.body) {
        const payload = await readErrorPayload(response)
        const message = payload.message ?? `事件流连接失败 (${response.status} ${response.statusText})`
        if (response.status >= 400 && response.status < 500
          && response.status !== 408 && response.status !== 429) {
          throw new NonRetryableStreamError(message)
        }
        throw new Error(message)
      }
      const contentType = response.headers.get('content-type')?.toLowerCase() ?? ''
      if (!contentType.includes('text/event-stream')) {
        throw new NonRetryableStreamError(
          `事件流返回了错误的 Content-Type: ${contentType || 'missing'}`,
        )
      }
      options.onConnectionChange?.('OPEN')
      firstAttempt = false
      retryMillis = Math.max(10, options.initialRetryMillis ?? 500)
      const reader = response.body.getReader()
      const textDecoder = new TextDecoder()
      const decoder = new SseFrameDecoder()
      let terminal = false
      let fallbackSequence = cursor
      while (!terminal) {
        const { done, value } = await reader.read()
        const frames = decoder.push(
          textDecoder.decode(value ?? new Uint8Array(), { stream: !done }),
          done,
        )
        for (const frame of frames) {
          if (frame.retry !== undefined) {
            retryMillis = Math.max(10, Math.min(frame.retry, maxRetryMillis))
          }
          const event = normalizeAgentEvent(frame, fallbackSequence + 1)
          if (event.sequence <= cursor) continue
          await options.onEvent(event)
          cursor = event.sequence
          fallbackSequence = cursor
          terminal = terminalEvents.has(event.type)
          if (terminal) break
        }
        if (done) break
      }
      reader.releaseLock()
      if (terminal) {
        options.onConnectionChange?.('CLOSED')
        return cursor
      }
    } catch (error) {
      if (options.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) {
        options.onConnectionChange?.('CLOSED')
        throw abortError()
      }
      if (error instanceof NonRetryableStreamError || error instanceof SseProtocolError) {
        options.onConnectionChange?.('CLOSED')
        throw error
      }
    }

    await wait(retryMillis, options.signal)
    retryMillis = Math.min(retryMillis * 2, maxRetryMillis)
  }
  options.onConnectionChange?.('CLOSED')
  throw abortError()
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
