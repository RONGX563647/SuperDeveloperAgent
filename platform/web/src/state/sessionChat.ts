import { shallowRef, watch } from 'vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import { useAgentChat } from '../composables/useAgentChat'
import { loadHistory, saveHistory } from './sessionHistory'
import type { ChatMessage } from '../types'

/**
 * 对话会话状态（按 sessionId 常驻）。
 *
 * <p>SSE 是「客户端断开即取消服务端运行」的形态：之前 useAgentChat 跟着
 * ChatView 卸载/切换会话而 abort，切到别的会话后整轮运行就被掐断。
 * 这里把每个会话的读流提升为模块级实例：切换会话只换视图，
 * 后台运行与输出继续，切回来能看到完整（可能仍在增长）的回答。
 */

/** 发送请求所需的会话上下文（会话所属项目固定，能力选择由当前视图同步进来）。 */
export interface SessionChatContext {
  projectId: string
  env: string
  worktree: string
  skills?: string[]
  mcpServers?: string[]
}

export interface SessionChatEntry {
  sessionId: string
  chat: ReturnType<typeof useAgentChat>
  context: SessionChatContext
  /** 删除会话时释放：停止读流并丢弃投影，避免后台继续写回已删除的历史。 */
  dispose: () => void
}

const entries = new Map<string, SessionChatEntry>()

function persist(entry: SessionChatEntry): void {
  const projection = entry.chat.projection.value
  const messages: ChatMessage[] = [...projection.messages]
  // 流式中的半截正文也落盘：刷新/关页后至少能看到已经产出的内容
  const turn = projection.streamingTurn
  if (turn && (turn.text || turn.thinking)) {
    messages.push({
      id: `live-${entry.sessionId}`,
      role: 'assistant',
      text: turn.text,
      thinking: turn.thinking || undefined,
      at: Date.now(),
    })
  }
  saveHistory(entry.sessionId, messages, projection.tools)
}

function createEntry(sessionId: string, context: SessionChatContext): SessionChatEntry {
  let disposed = false
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  const entry: SessionChatEntry = {
    sessionId,
    context,
    chat: useAgentChat({
      // 上下文按会话快照：后台会话不会因为当前视图切到别的项目而串项目
      buildRequest: () => ({
        projectId: entry.context.projectId,
        env: entry.context.env,
        sessionId,
        worktree: entry.context.worktree,
        permission: appStore.permission,
        skills: entry.context.skills,
        mcpServers: entry.context.mcpServers,
      }),
      getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
      // 跨路由常驻：离开对话页 / 切换会话都不能中止读流
      autoDispose: false,
    }),
    dispose: () => {
      disposed = true
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = null
      entry.chat.stop()
    },
  }

  const history = loadHistory(sessionId)
  if (history) entry.chat.hydrate(history.messages, history.tools)

  watch(entry.chat.projection, () => {
    if (disposed) return
    if (saveTimer) clearTimeout(saveTimer)
    saveTimer = setTimeout(() => {
      saveTimer = null
      if (!disposed) persist(entry)
    }, 400)
  })

  return entry
}

/** 取会话的常驻实例；首次访问时恢复本地历史。 */
export function getSessionChat(
  sessionId: string,
  context: SessionChatContext,
): SessionChatEntry {
  let entry = entries.get(sessionId)
  if (!entry) {
    entry = createEntry(sessionId, { ...context })
    entries.set(sessionId, entry)
  }
  return entry
}

/** 删除会话时释放常驻实例（停止读流，后续不再写回历史）。 */
export function disposeSessionChat(sessionId: string): void {
  const entry = entries.get(sessionId)
  if (!entry) return
  entries.delete(sessionId)
  entry.dispose()
}
