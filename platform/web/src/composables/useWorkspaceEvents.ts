import { onScopeDispose, ref } from 'vue'
import { SseFrameDecoder } from '../api/sse'
import { notifyUnauthorized } from '../api/auth'

/** 工作区聚合事件（与 WorkspaceEventController 的 payload 对齐）。 */
export interface WorkspaceEvent {
  type: string
  conversationId?: string
  runId?: string
  status?: string
  text?: string
  at?: number
}

interface UseWorkspaceEventsOptions {
  /** 事件流地址，函数形式保证项目切换后取到最新地址。 */
  url: () => string
  getHeaders: () => Record<string, string>
  onEvent: (event: WorkspaceEvent) => void
  /** 连接状态变化（用于界面提示与轮询兜底判断）。 */
  onConnectedChange?: (connected: boolean) => void
}

/**
 * 工作区事件订阅：SSE 推事件、状态真相仍以 REST 为准。
 *
 * - 断线后指数退避重连（1s → 15s），页面隐藏时由调用方暂停；
 * - 单帧解析失败只丢弃该帧，不影响后续事件；
 * - 组件卸载自动断开。
 */
export function useWorkspaceEvents(options: UseWorkspaceEventsOptions) {
  const connected = ref(false)
  let controller: AbortController | null = null
  let stopped = true
  let retry = 0

  function setConnected(value: boolean): void {
    if (connected.value === value) return
    connected.value = value
    options.onConnectedChange?.(value)
  }

  async function connect(): Promise<void> {
    if (stopped) return
    controller = new AbortController()
    try {
      const response = await fetch(options.url(), {
        method: 'GET',
        headers: { Accept: 'text/event-stream', ...options.getHeaders() },
        signal: controller.signal,
      })
      if (response.status === 401) notifyUnauthorized()
      if (!response.ok || !response.body) {
        throw new Error(`事件流连接失败（${response.status}）`)
      }
      const contentType = response.headers.get('content-type')?.toLowerCase() ?? ''
      if (!contentType.includes('text/event-stream')) {
        throw new Error('事件流返回了非 text/event-stream 内容')
      }
      setConnected(true)
      retry = 0
      const reader = response.body.getReader()
      const textDecoder = new TextDecoder()
      const decoder = new SseFrameDecoder()
      while (!stopped) {
        const { done, value } = await reader.read()
        const frames = decoder.push(
          textDecoder.decode(value ?? new Uint8Array(), { stream: !done }),
          done,
        )
        for (const frame of frames) {
          if (frame.event === 'error') continue
          let parsed: WorkspaceEvent
          try {
            parsed = JSON.parse(frame.data) as WorkspaceEvent
          } catch {
            continue // 单帧损坏不影响后续事件
          }
          if (parsed && typeof parsed.type === 'string') options.onEvent(parsed)
        }
        if (done) break
      }
      reader.releaseLock()
      throw new Error('事件流已断开')
    } catch (error) {
      if (stopped || (error instanceof DOMException && error.name === 'AbortError')) return
      setConnected(false)
    }
    if (stopped) return
    // 指数退避：1s、2s、4s、8s，封顶 15s
    const delay = Math.min(15_000, 1000 * 2 ** Math.min(retry++, 4))
    await new Promise((resolve) => setTimeout(resolve, delay))
    if (!stopped) void connect()
  }

  /** 开始/重新开始订阅（项目切换时调用）。 */
  function start(): void {
    stop()
    stopped = false
    void connect()
  }

  function stop(): void {
    stopped = true
    controller?.abort()
    controller = null
    setConnected(false)
  }

  onScopeDispose(stop)

  return { connected, start, stop }
}
