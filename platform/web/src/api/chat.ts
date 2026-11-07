import type { AgentEventDto, ChatRequest } from '../types'
import { currentUserId } from './http'

/**
 * 发起对话并按 SSE 事件流消费。
 *
 * 用 fetch + ReadableStream 而不是 EventSource：EventSource 只支持 GET，而我们需要 POST 传上下文。
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

/** 解析一个 SSE 帧；后端每个 data 都是单行 JSON。 */
function parseSseChunk(chunk: string): AgentEventDto | null {
  const dataLines: string[] = []
  for (const line of chunk.split('\n')) {
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }
  if (dataLines.length === 0) return null
  const raw = dataLines.join('\n')
  if (!raw || raw === '[DONE]') return null
  try {
    return JSON.parse(raw) as AgentEventDto
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
