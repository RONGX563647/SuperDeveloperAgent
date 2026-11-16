import { effectScope, watch, type EffectScope } from 'vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import { getConversationTranscript, saveConversationTranscript } from '../api/workspace'
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
  /** 本会话选择的模型（null = 设置页默认；选择跟随会话，不写回全局默认） */
  modelConnectionId?: string | null
  modelName?: string | null
}

export interface SessionChatEntry {
  sessionId: string
  chat: ReturnType<typeof useAgentChat>
  context: SessionChatContext
  /** 删除会话时释放：停止读流并丢弃投影，避免后台继续写回已删除的历史。 */
  dispose: () => void
}

/** 本地缓存防抖间隔。 */
const LOCAL_SAVE_DELAY = 400
/** 服务端入库间隔：流式输出期间按固定节奏写，不在每次增量时重置计时器。 */
const SERVER_SAVE_DELAY = 1_000

const entries = new Map<string, SessionChatEntry>()

/** 时间线快照：已落库的消息 + 流式中的半截正文（刷新后至少能看到已产出内容）。 */
function snapshotMessages(entry: SessionChatEntry): ChatMessage[] {
  const projection = entry.chat.projection.value
  const messages: ChatMessage[] = [...projection.messages]
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
  return messages
}

function persist(entry: SessionChatEntry): void {
  saveHistory(entry.sessionId, snapshotMessages(entry), entry.chat.projection.value.tools)
}

function createEntry(sessionId: string, context: SessionChatContext): SessionChatEntry {
  let disposed = false
  let saveTimer: ReturnType<typeof setTimeout> | null = null
  let saveScope: EffectScope | null = null
  // 服务端同步状态：拉取完成前只记录待写入，避免用空/旧快照覆盖数据库
  let serverReady = false
  let serverPending = false
  let serverTimer: ReturnType<typeof setTimeout> | null = null
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
        modelConnectionId: entry.context.modelConnectionId ?? null,
        modelName: entry.context.modelName ?? null,
      }),
      getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
      // 跨路由常驻：离开对话页 / 切换会话都不能中止读流
      autoDispose: false,
    }),
    dispose: () => {
      disposed = true
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = null
      if (serverTimer) clearTimeout(serverTimer)
      serverTimer = null
      saveScope?.stop()
      entry.chat.stop()
    },
  }

  function pushServer(): void {
    if (disposed) return
    try {
      const payload = serializeHistory(snapshotMessages(entry), entry.chat.projection.value.tools)
      void saveConversationTranscript(sessionId, payload).catch(() => {
        // 后端不可用：本地缓存照旧，下一轮变更会重试
      })
    } catch {
      // 序列化失败（异常数据）：跳过本次服务端写入
    }
  }

  function scheduleServerSave(): void {
    if (disposed) return
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

  /**
   * 首次进入会话时与服务端对账：
   * 数据库更新（换了浏览器/设备）就以数据库为准；数据库为空则把本地历史迁进去。
   */
  async function syncFromServer(local: HistorySnapshot | null): Promise<void> {
    try {
      const remote = await getConversationTranscript(sessionId)
      if (disposed) return
      const remoteHistory = remote.found ? parseHistory(remote.payload) : null
      if (remoteHistory && latestMessageAt(remoteHistory) > latestMessageAt(local)) {
        entry.chat.hydrate(remoteHistory.messages, remoteHistory.tools)
        saveHistory(sessionId, remoteHistory.messages, remoteHistory.tools)
      } else if (local) {
        // 数据库没有或比本地旧：把当前快照回传，保证两边收敛到最新
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

  const history = loadHistory(sessionId)
  if (history) entry.chat.hydrate(history.messages, history.tools)
  void syncFromServer(history)

  /**
   * 落盘 watcher 必须挂在独立的 effectScope 上。
   *
   * <p>会话实例是模块级常驻的，但 createEntry 通常在某个视图的 setup 里被调用：
   * watch 会被 Vue 记到该组件的 effectScope 上，离开页面（去流水线/工作区）时自动停止，
   * 而切回对话页只会复用已有实例、不会重建 watcher —— 之后的消息就再也不会写入
   * localStorage，表现为「重启后端/刷新后之前发的一些消息没了」。
   */
  saveScope = effectScope(true)
  saveScope.run(() => {
    watch(entry.chat.projection, () => {
      if (disposed) return
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = setTimeout(() => {
        saveTimer = null
        if (!disposed) persist(entry)
      }, LOCAL_SAVE_DELAY)
      scheduleServerSave()
    })
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
