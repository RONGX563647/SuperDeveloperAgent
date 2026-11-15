import { shallowRef, watch } from 'vue'
import type { ShallowRef } from 'vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import { getSupervisor, type ConversationRow } from '../api/workspace'
import { useAgentChat } from '../composables/useAgentChat'
import { loadHistory, saveHistory } from './sessionHistory'
import type { ChatMessage } from '../types'

/**
 * 工作区调度 Agent 的会话状态（按项目常驻）。
 *
 * <p>为什么不放在视图里：SSE 是「客户端断开即取消服务端运行」的形态，
 * 之前 useAgentChat 跟着 WorkspaceHomeView 卸载而 abort，切到别的工作区 /
 * 对话页再回来，正在输出的回答就被掐断、时间线也被 reset 清空。
 * 这里把每个项目的调度会话提升为模块级单例：切页只换视图，读流和输出继续，
 * 回来时直接看到完整（可能仍在增长）的对话。
 */

export interface WorkspaceChatEntry {
  projectId: string
  supervisor: ShallowRef<ConversationRow | null>
  chat: ReturnType<typeof useAgentChat>
  /** 首次进入项目时加载调度会话并恢复本地历史；重复调用复用同一 Promise。 */
  ensure: () => Promise<void>
}

const entries = new Map<string, WorkspaceChatEntry>()

function createEntry(projectId: string): WorkspaceChatEntry {
  const supervisor = shallowRef<ConversationRow | null>(null)
  const chat = useAgentChat({
    url: () => `/api/v1/workspaces/${projectId}/super/stream`,
    buildRequest: () => ({ sessionId: supervisor.value?.id, env: appStore.env }),
    getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
    // 跨路由常驻：卸载视图不能中止读流
    autoDispose: false,
  })

  let ensurePromise: Promise<void> | null = null
  let hydrated = false
  let saveTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 落盘：把流式中的半截正文也写进去。
   * 流式中途刷新/关页时，至少能恢复已经看到的内容，而不是只剩上一条完整消息。
   */
  function persist(): void {
    saveTimer = null
    const current = supervisor.value
    if (!current) return
    const projection = chat.projection.value
    const messages: ChatMessage[] = [...projection.messages]
    const turn = projection.streamingTurn
    if (turn && (turn.text || turn.thinking)) {
      messages.push({
        id: `live-${current.id}`,
        role: 'assistant',
        text: turn.text,
        thinking: turn.thinking || undefined,
        at: Date.now(),
      })
    }
    saveHistory(current.id, messages, projection.tools)
  }

  // projection 是浅响应式对象，每次事件都会整体替换：直接监听引用，不必 deep 遍历大消息列表
  watch(chat.projection, () => {
    if (!supervisor.value || !hydrated) return
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(persist, 600)
  })

  function ensure(): Promise<void> {
    if (ensurePromise) return ensurePromise
    ensurePromise = (async () => {
      const sup = await getSupervisor(projectId)
      supervisor.value = sup
      if (!hydrated) {
        const history = loadHistory(sup.id)
        if (history) chat.hydrate(history.messages, history.tools)
        hydrated = true
      }
    })().catch((error) => {
      // 允许下次进入时重试
      ensurePromise = null
      throw error
    })
    return ensurePromise
  }

  return { projectId, supervisor, chat, ensure }
}

export function getWorkspaceChat(projectId: string): WorkspaceChatEntry {
  let entry = entries.get(projectId)
  if (!entry) {
    entry = createEntry(projectId)
    entries.set(projectId, entry)
  }
  return entry
}
