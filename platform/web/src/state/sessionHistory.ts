import type { ChatMessage, ToolActivity } from '../types'

/**
 * 会话历史本地持久化。
 *
 * <p>后端按 (userId, sessionId) 保存模型上下文，但前端时间线是投影出来的，刷新/切会话就丢。
 * 这里把「消息 + 工具调用结果」按会话存到 localStorage，切回会话时原样恢复，
 * 让侧栏的会话条目真正可用（不需要新增后端接口）。
 *
 * <p>容量策略：每个会话最多保留最近 200 条消息；单次写入失败（隐私模式 / 配额满）
 * 直接忽略，不影响对话本身。
 */

const KEY_PREFIX = 'agent.history.'
const MAX_MESSAGES = 200
/** 单条工具结果最长保留字符数，避免日志把 localStorage 撑爆。 */
const MAX_TOOL_RESULT = 20_000

export interface HistorySnapshot {
  messages: ChatMessage[]
  tools: Record<string, ToolActivity>
}

export function loadHistory(sessionId: string): HistorySnapshot | null {
  if (!sessionId) return null
  try {
    const raw = localStorage.getItem(KEY_PREFIX + sessionId)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<HistorySnapshot>
    if (!Array.isArray(parsed.messages)) return null
    return {
      messages: parsed.messages,
      tools: parsed.tools && typeof parsed.tools === 'object' ? parsed.tools : {},
    }
  } catch {
    return null
  }
}

/** 删除某个会话的本地时间线（侧栏删除会话时同步清理，避免残留占配额）。 */
export function clearHistory(sessionId: string): void {
  if (!sessionId) return
  try {
    localStorage.removeItem(KEY_PREFIX + sessionId)
  } catch {
    // 隐私模式下不可写，忽略
  }
}

export function saveHistory(
  sessionId: string,
  messages: ChatMessage[],
  tools: Record<string, ToolActivity>,
): void {
  if (!sessionId) return
  try {
    const trimmed = messages.slice(-MAX_MESSAGES)
    const safeTools: Record<string, ToolActivity> = {}
    for (const [id, tool] of Object.entries(tools)) {
      safeTools[id] = tool.resultText && tool.resultText.length > MAX_TOOL_RESULT
        ? { ...tool, resultText: tool.resultText.slice(0, MAX_TOOL_RESULT) }
        : tool
    }
    localStorage.setItem(
      KEY_PREFIX + sessionId,
      JSON.stringify({ messages: trimmed, tools: safeTools }),
    )
  } catch {
    // 隐私模式 / 配额满：忽略
  }
}
