import { computed, onScopeDispose, shallowRef } from 'vue'
import type { ShallowRef } from 'vue'
import { streamChatOnce } from '../api/sse'
import type {
  AgentEvent, AgentProjection, ChatMessage, SessionTokenUsage, ToolActivity,
} from '../types'
import { emptyAgentProjection, reduceAgentEvent } from '../state/agentEventReducer'
import { addSessionCost, emptySessionCost } from '../utils/deepseekPricing'

/**
 * 会话编排 composable（参考 ruoyi-copilot 的 useHarnessAgent 收敛而来）：
 *
 * - 只保留本项目需要的子集：单会话单运行、SSE 订阅生命周期、事件投影、停止；
 * - View 只做渲染，不直接 fetch / 读流（见 docs/项目规约 11.1 前端分层）；
 * - 事件经 reduceAgentEvent 折叠进 projection，视图层订阅同一个浅响应式对象；
 * - user/greeting 等本地消息由视图层持有（不经过 reducer）；
 * - onScopeDispose 自动 abort，组件卸载不会留下悬挂的读流循环。
 */

export type ConnectionState = 'IDLE' | 'CONNECTING' | 'OPEN' | 'RECONNECTING' | 'CLOSED'

interface UseAgentChatOptions {
  /** 构造 ChatRequest 请求体（projectId/env/sessionId 由调用方持有）。 */
  buildRequest: (message: string) => Record<string, unknown>
  /** 请求路径，默认 /api/v1/chat；传函数可在路由参数变化后取到最新地址。 */
  url?: string | (() => string)
  /** 每次发送时构造请求头（如 Authorization）；函数形式避免 setup 时固化过期 token。 */
  getHeaders?: () => Record<string, string>
  /** 发送前置检查（如 IME 组合中）；返回 false 则不发送。 */
  guard?: () => boolean
  /**
   * 是否在组件卸载时自动中止读流。
   * 页面内会话保持默认 true；跨路由常驻的会话（工作区调度 Agent）传 false，
   * 否则切换页面会把正在跑的流掐断。
   */
  autoDispose?: boolean
}

export function useAgentChat(options: UseAgentChatOptions) {
  const url = () => {
    const value = options.url
    return typeof value === 'function' ? value() : (value ?? '/api/v1/chat')
  }
  const projection: ShallowRef<AgentProjection> = shallowRef(emptyAgentProjection())
  const streaming = shallowRef(false)
  const elapsedMs = shallowRef(0)
  const tokens = shallowRef(0)
  /** 本会话真实 token 累计（由 model.usage 事件累加，刷新后可从服务端回填）。 */
  const usage = shallowRef<SessionTokenUsage>(emptyUsage())
  const connection = shallowRef<ConnectionState>('IDLE')
  const error = shallowRef('')

  let controller: AbortController | null = null
  let timer: ReturnType<typeof setInterval> | null = null
  /**
   * 当前读流的代号：reset / stop / 新一次 send 都会自增。
   * 在途的旧流结束时据此判断自己是否已经过期，避免把新一轮的 streaming 状态改坏。
   */
  let runToken = 0
  /** 本次读流是否由用户主动停止（停止不应该报错，也不该显示「连接中断」）。 */
  let userStopped = false

  const pendingChangeId = computed(() => projection.value.pendingChangeId)
  const pendingBindingId = computed(() => projection.value.pendingBindingId)
  const streamingTurn = computed(() => projection.value.streamingTurn)

  function emptyUsage(): SessionTokenUsage {
    return { calls: 0, inputTokens: 0, cachedTokens: 0, outputTokens: 0, totalTokens: 0 }
  }

  function addUsage(base: SessionTokenUsage, raw: unknown): SessionTokenUsage {
    const value = (raw ?? {}) as Record<string, unknown>
    const num = (v: unknown): number =>
      typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.floor(v) : 0
    const input = num(value.inputTokens ?? value.prompt_tokens)
    const output = num(value.outputTokens ?? value.completion_tokens)
    const cached = Math.min(
      num(value.cachedTokens
        ?? value.prompt_cache_hit_tokens
        ?? (value.prompt_tokens_details as { cached_tokens?: number } | undefined)?.cached_tokens),
      input,
    )
    return {
      calls: base.calls + 1,
      inputTokens: base.inputTokens + input,
      cachedTokens: base.cachedTokens + cached,
      outputTokens: base.outputTokens + output,
      totalTokens: base.totalTokens + input + output,
    }
  }

  /** 用服务端累计覆盖本地（刷新/切换会话后回填真实值）。 */
  function applyUsage(totals: Partial<SessionTokenUsage> | null): void {
    if (!totals) return
    usage.value = {
      calls: totals.calls ?? 0,
      inputTokens: totals.inputTokens ?? 0,
      cachedTokens: totals.cachedTokens ?? 0,
      outputTokens: totals.outputTokens ?? 0,
      totalTokens: totals.totalTokens ?? 0,
    }
  }

  function isAbortError(value: unknown): boolean {
    return value instanceof DOMException
      ? value.name === 'AbortError'
      : (value as Error | null)?.name === 'AbortError'
  }

  /** 把流式轮次落成一条正式消息：中断/停止时保留已产出的正文与思考。 */
  function finalizeStreamingTurn(stopped: boolean): void {
    const current = projection.value
    const turn = current.streamingTurn
    if (!turn) return
    projection.value = {
      ...current,
      streamingTurn: null,
      messages: [...current.messages, {
        id: `seq-final-${current.messages.length}-${Date.now()}`,
        role: 'assistant',
        text: turn.text || (stopped ? '（已停止）' : '（连接中断）'),
        thinking: turn.thinking || undefined,
        cost: turn.cost,
        at: Date.now(),
      }],
    }
  }

  function clearRuntime(): void {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    streaming.value = false
    connection.value = 'CLOSED'
  }

  function resetProjection(): void {
    runToken += 1
    userStopped = false
    if (controller) {
      controller.abort()
      controller = null
    }
    clearRuntime()
    projection.value = emptyAgentProjection()
    elapsedMs.value = 0
    tokens.value = 0
    usage.value = emptyUsage()
    connection.value = 'IDLE'
    error.value = ''
  }

  /** 追加一条本地消息（user 侧）：与 reducer 的消息放在同一列表，保证多轮顺序正确。 */
  function appendMessage(message: ChatMessage): void {
    projection.value = {
      ...projection.value,
      messages: [...projection.value.messages, message],
    }
  }

  /** 恢复历史会话快照（切换会话 / 刷新页面时用）。 */
  function hydrate(messages: ChatMessage[], tools: Record<string, ToolActivity>): void {
    // 会话累计费用随历史一起重建，刷新/切回会话不会归零
    const sessionCost = messages.reduce(
      (acc, message) => (message.cost ? addSessionCost(acc, message.cost) : acc),
      emptySessionCost(),
    )
    projection.value = { ...emptyAgentProjection(), messages, tools, sessionCost }
  }

  async function send(text: string): Promise<void> {
    const trimmed = text.trim()
    if (!trimmed || streaming.value) return
    if (options.guard?.() === false) return
    const token = ++runToken
    userStopped = false
    streaming.value = true
    elapsedMs.value = 0
    tokens.value = 0
    error.value = ''
    timer = setInterval(() => (elapsedMs.value += 100), 100)
    controller = new AbortController()
    // 每次发送都是一个独立的 SSE 流（sequence 从 1 重新计数）：
    // 不重置去重游标的话，第二轮的全部事件会被 reducer 当成「迟到事件」丢弃，
    // 表现为第二问发出后完全收不到回复。
    // 新的一轮开始时收起上一轮的绑定确认卡；若本轮又生成草案，后端会重新下发事件。
    projection.value = { ...projection.value, lastSequence: 0, pendingBindingId: null }

    try {
      await streamChatOnce(
        url(),
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
            ...(options.getHeaders?.() ?? {}),
          },
          body: JSON.stringify({ ...options.buildRequest(trimmed), message: trimmed }),
        },
        (event: AgentEvent) => {
          const before = projection.value
          const after = reduceAgentEvent(before, event)
          if (after !== before) {
            projection.value = after
            if (event.type !== 'error') tokens.value += 1
          }
          // 真实 token：每次模型调用结束都带 usage（所有模型都累计）
          if (event.type === 'model.usage') {
            usage.value = addUsage(usage.value, event.payload.usage)
          }
          if (event.type === 'error') {
            error.value = typeof event.payload?.message === 'string'
              ? event.payload.message
              : '对话流异常'
          }
        },
        controller.signal,
      )
    } catch (e) {
      // 已被 stop / reset / 新一轮发送接管：旧流的收尾不再影响当前状态
      if (token !== runToken) return
      if (userStopped || isAbortError(e)) error.value = ''
      else error.value = e instanceof Error ? e.message : String(e)
    } finally {
      if (token === runToken) {
        // 流结束后若有未落盘的流式轮次，落成一条 assistant 消息
        finalizeStreamingTurn(userStopped)
        controller = null
        clearRuntime()
      }
    }
  }

  function stop(): void {
    if (!streaming.value && !controller) return
    userStopped = true
    // 先让在途读流过期，再中止：它的 catch/finally 不会覆盖这里已经收尾的状态
    runToken += 1
    controller?.abort()
    controller = null
    if (projection.value.streamingTurn) {
      finalizeStreamingTurn(true)
    } else {
      // 模型还没吐出任何增量就被停掉：给一条明确提示，避免「点了停止但界面毫无变化」
      appendMessage({ id: `stopped-${Date.now()}`, role: 'assistant', text: '（已停止）', at: Date.now() })
    }
    clearRuntime()
    error.value = ''
  }

  /** 变更已由用户在确认卡上应用（HTTP 侧）：本地清掉待确认标记，时间线不再挂着提示。 */
  function clearPendingChange(changeId?: string): void {
    const current = projection.value
    if (!current.pendingChangeId) return
    if (changeId && current.pendingChangeId !== changeId) return
    projection.value = { ...current, pendingChangeId: null }
  }

  if (options.autoDispose !== false) onScopeDispose(stop)

  return {
    // 状态（只读）
    projection,
    streamingTurn,
    streaming,
    elapsedMs,
    tokens,
    usage,
    connection,
    error,
    pendingChangeId,
    pendingBindingId,
    // 动作
    send,
    stop,
    appendMessage,
    hydrate,
    applyUsage,
    clearPendingChange,
    reset: resetProjection,
  }
}
