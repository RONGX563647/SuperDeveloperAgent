import { reactive } from 'vue'

/** 会话条目（侧栏「工作区列表」展示，本地持久化）。 */
export interface SessionItem {
  id: string
  title: string
  at: number
}

const SESSION_KEY = 'agent.sessions'
const MAX_SESSIONS = 30

function loadSessions(): SessionItem[] {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw) as SessionItem[]) : []
  } catch {
    return []
  }
}

function persist(sessions: SessionItem[]) {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify(sessions))
  } catch {
    // 隐私模式下不可写，忽略
  }
}

function newId(): string {
  return 's-' + Math.random().toString(36).slice(2, 10)
}

/**
 * 极轻量全局状态：只放「布局与页面共享」的少量字段。
 *
 * <p>不引入 pinia：这里只有项目/环境/会话三个字段，reactive 足够。
 */
export const appStore = reactive({
  projectId: 'RY',
  env: 'test',
  sessionId: newId(),
  sessions: loadSessions() as SessionItem[],

  /** 新建会话（侧栏「新会话」与页面「新会话」都走这里）。 */
  newSession() {
    appStore.sessionId = newId()
  },

  /** 选中历史会话。 */
  selectSession(id: string) {
    appStore.sessionId = id
  },

  /** 记录/更新一条会话摘要（首条用户消息作为标题）。 */
  touchSession(title: string) {
    const id = appStore.sessionId
    const text = title.trim().slice(0, 40)
    const existing = appStore.sessions.find((s) => s.id === id)
    if (existing) {
      existing.at = Date.now()
      if (text && existing.title === '新会话') existing.title = text
    } else {
      appStore.sessions.unshift({ id, title: text || '新会话', at: Date.now() })
    }
    appStore.sessions = appStore.sessions
      .sort((a, b) => b.at - a.at)
      .slice(0, MAX_SESSIONS)
    persist(appStore.sessions)
  }
})
