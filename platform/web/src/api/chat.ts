import type { AgentEventDto, ChatRequest } from '../types'
import { currentUserId } from './http'

/**
 * 发起对话并按 SSE 事件流消费。
 *
 * 用 fetch + ReadableStream 而不是 EventSource：EventSource 只支持 GET，而我们需要 POST 传上下文。
 * 回调收到的 event.type 是 SSE 的 event: 行（"agent" / "error"），payload 是 data JSON。
 */
export async function streamChat(
  req: ChatRequest,
  onEvent: (event: AgentEventDto) => void,
  signal?: AbortSignal
): Promise<void> {
  const res = await fetch('/api/v1/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-User-Id': currentUserId()
    },
    body: JSON.stringify(req),
    signal
  })

  if (!res.ok || !res.body) {
    const text = await res.text().catch(() => '')
    throw new Error(`建立事件流失败（${res.status}）${text ? '：' + text : ''}`)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let sep = buffer.indexOf('\n\n')
    while (sep >= 0) {
      const chunk = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      const event = parseSseChunk(chunk)
      if (event) onEvent(event)
      sep = buffer.indexOf('\n\n')
    }
  }
}

/**
 * 解析一个 SSE 帧。
 *
 * <p>必须同时读 {@code event:} 与 {@code data:} 两行：后端把流式异常作为
 * {@code event:error} + {@code data:{"message":…}} 发出，丢了 event 行，
 * 错误就会被当成普通 agent 事件处理，前端永远看不到失败。
 */
function parseSseChunk(chunk: string): AgentEventDto | null {
  const dataLines: string[] = []
  let eventName = 'agent'
  for (const line of chunk.split('\n')) {
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    } else if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    }
  }
  if (dataLines.length === 0) return null
  const raw = dataLines.join('\n')
  if (!raw || raw === '[DONE]') return null
  try {
    return { type: eventName, payload: JSON.parse(raw) as Record<string, unknown> }
  } catch {
    return null
  }
}

/** 从事件里尽力提取可展示的文本增量。 */
export function extractText(event: AgentEventDto): string {
  const p = event.payload ?? {}
  for (const key of ['text', 'delta', 'content', 'message']) {
    const value = (p as Record<string, unknown>)[key]
    if (typeof value === 'string' && value.length > 0) return value
  }
  return ''
}

/** 从文本里识别变更单号（骨架阶段的临时手段，后续应由后端发专用事件）。 */
export function findChangeId(text: string): string | null {
  const match = text.match(/chg-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)
  return match ? match[0] : null
}
