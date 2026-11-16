import { shallowRef, watch } from 'vue'
import type { ShallowRef } from 'vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import {
  getConversationTranscript,
  getSupervisor,
  saveConversationTranscript,
  type ConversationRow,
} from '../api/workspace'
import { useAgentChat } from '../composables/useAgentChat'
import {
  latestMessageAt,
  loadHistory,
  parseHistory,
  saveHistory,
  serializeHistory,
  type HistorySnapshot,
} from './sessionHistory'
import type { ChatMessage } from '../types'

/**
 * 工作区调度 Agent 的会话状态（按项目常驻）。
 *
 * <p>为什么不放在视图里：SSE 是「客户端断开即取消服务端运行」的形态，
 * 之前 useAgentChat 跟着 WorkspaceHomeView 卸载而 abort，切到别的工作区 /
 * 对话页再回来，正在输出的回答就被掐断、时间线也被 reset 清空。
 * 这里把每个项目的调度会话提升为模块级单例：切页只换视图，读流和输出继续，
 * 回来时直接看到完整（可能仍在增长）的对话。
 *
 * <p>时间线同时写入服务端数据库：localStorage 只是本机缓存，换浏览器后仍可恢复。
 */

const LOCAL_SAVE_DELAY = 600
const SERVER_SAVE_DELAY = 1_000

export interface WorkspaceChatEntry {
  projectId: string
  supervisor: ShallowRef<ConversationRow | null>
  chat: ReturnType<typeof useAgentChat>
  /** 首次进入项目时加载调度会话并恢复历史；重复调用复用同一 Promise。 */
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
  // 服务端同步状态：拉取完成前只记录待写入，避免用空/旧快照覆盖数据库
  let serverReady = false
  let serverPending = false
  let serverTimer: ReturnType<typeof setTimeout> | null = null

  /** 时间线快照：已落库消息 + 流式中的半截正文（刷新后至少能看到已产出内容）。 */
  function snapshotMessages(): ChatMessage[] {
    const current = supervisor.value
    if (!current) return []
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
    return messages
  }

  function persist(): void {
    saveTimer = null
    const current = supervisor.value
    if (!current) return
    saveHistory(current.id, snapshotMessages(), chat.projection.value.tools)
  }

  function pushServer(): void {
    const current = supervisor.value
    if (!current) return
    const payload = serializeHistory(snapshotMessages(), chat.projection.value.tools)
    void saveConversationTranscript(current.id, payload).catch(() => {
      // 后端不可用：本地缓存照旧，下一轮变更会重试
    })
  }

  function scheduleServerSave(): void {
    if (!hydrated) return
    if (!serverReady) {
      serverPending = true
      return
    }
    // 流式输出会持续触发投影变化：计时器已在跑就不重置，保证至少每秒落库一次
    if (serverTimer) return
    serverTimer = setTimeout(() => {
      serverTimer = null
      pushServer()
    }, SERVER_SAVE_DELAY)
  }

  /** 与服务端对账：数据库更新就以数据库为准；数据库为空或更旧则回传本地快照。 */
  async function syncFromServer(sessionId: string, local: HistorySnapshot | null): Promise<void> {
    try {
      const remote = await getConversationTranscript(sessionId)
      const remoteHistory = remote.found ? parseHistory(remote.payload) : null
      if (remoteHistory && latestMessageAt(remoteHistory) > latestMessageAt(local)) {
        chat.hydrate(remoteHistory.messages, remoteHistory.tools)
        saveHistory(sessionId, remoteHistory.messages, remoteHistory.tools)
      } else if (local) {
        serverPending = true
      }
    } catch {
      // 接口不可用：退回纯本地模式
    } finally {
      serverReady = true
      if (serverPending) {
        serverPending = false
        scheduleServerSave()
      }
    }
  }

  // projection 是浅响应式对象，每次事件都会整体替换：直接监听引用，不必 deep 遍历大消息列表
  watch(chat.projection, () => {
    if (!supervisor.value || !hydrated) return
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(persist, LOCAL_SAVE_DELAY)
    scheduleServerSave()
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
        void syncFromServer(sup.id, history)
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
