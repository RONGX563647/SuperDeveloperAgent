import { computed, onScopeDispose, shallowRef } from 'vue'
import type { ShallowRef } from 'vue'
import { streamChatOnce } from '../api/sse'
import type { AgentEvent, AgentProjection, ChatMessage, ToolActivity } from '../types'
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
  const connection = shallowRef<ConnectionState>('IDLE')
  const error = shallowRef('')

  let controller: AbortController | null = null
  let timer: ReturnType<typeof setInterval> | null = null

  const pendingChangeId = computed(() => projection.value.pendingChangeId)
  const streamingTurn = computed(() => projection.value.streamingTurn)

  function resetProjection(): void {
    if (controller) {
      controller.abort()
      controller = null
    }
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    projection.value = emptyAgentProjection()
    streaming.value = false
    elapsedMs.value = 0
    tokens.value = 0
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
    streaming.value = true
    elapsedMs.value = 0
    tokens.value = 0
    error.value = ''
    timer = setInterval(() => (elapsedMs.value += 100), 100)
    controller = new AbortController()
    // 每次发送都是一个独立的 SSE 流（sequence 从 1 重新计数）：
    // 不重置去重游标的话，第二轮的全部事件会被 reducer 当成「迟到事件」丢弃，
    // 表现为第二问发出后完全收不到回复。
    projection.value = { ...projection.value, lastSequence: 0 }

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
          if (event.type === 'error') {
            error.value = typeof event.payload?.message === 'string'
              ? event.payload.message
              : '对话流异常'
          }
        },
        controller.signal,
      )
    } catch (e) {
      // 流中断且无正文时给出提示；其余错误直接上抛给视图展示
      error.value = e instanceof Error ? e.message : String(e)
    } finally {
      // 流结束后若有未落盘的流式轮次，落成一条 assistant 消息
      const current = projection.value
      if (current.streamingTurn) {
        projection.value = {
          ...current,
          streamingTurn: null,
          messages: [...current.messages, {
            id: `seq-final-${current.messages.length}-${Date.now()}`,
            role: 'assistant',
            text: current.streamingTurn.text || '（连接中断）',
            thinking: current.streamingTurn.thinking || undefined,
            cost: current.streamingTurn.cost,
            at: Date.now(),
          }],
        }
      }
      streaming.value = false
      controller = null
      if (timer) {
        clearInterval(timer)
        timer = null
      }
      connection.value = 'CLOSED'
    }
  }

  function stop(): void {
    controller?.abort()
    controller = null
    streaming.value = false
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    connection.value = 'CLOSED'
  }

  onScopeDispose(stop)

  return {
    // 状态（只读）
    projection,
    streamingTurn,
    streaming,
    elapsedMs,
    tokens,
    connection,
    error,
    pendingChangeId,
    // 动作
    send,
    stop,
    appendMessage,
    hydrate,
    reset: resetProjection,
  }
}
