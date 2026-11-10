import { reactive } from 'vue'

/** 执行策略：只读诊断 / 每次确认 / 自动展开确认卡（后端变更单仍是最终门禁）。 */
export type PermissionMode = 'readonly' | 'confirm' | 'auto'

/** 会话条目（侧栏「工作区」按 projectId 分组展示，本地持久化）。 */
export interface SessionItem {
  id: string
  title: string
  at: number
  /** 所属工作区（项目 id）。旧数据迁移时补默认值。 */
  projectId: string
}

const SESSION_KEY = 'agent.sessions'
const CURRENT_SESSION_KEY = 'agent.currentSession'
const PERMISSION_KEY = 'agent.permission'
const MAX_SESSIONS = 30
const DEFAULT_PROJECT = 'RY'

function loadSessions(): SessionItem[] {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    const parsed = raw
      ? (JSON.parse(raw) as Array<Partial<SessionItem> & { id: string }>)
      : []
    // 旧版本没有 projectId：统一归到默认工作区，避免历史会话散落在「未分组」里；
    // 同时清掉旧逻辑在每次进入对话页时登记的空白「新会话」，否则侧栏会被占位条目淹没。
    return parsed
      .map((s) => ({
        id: s.id,
        title: s.title ?? '新会话',
        at: s.at ?? Date.now(),
        projectId: s.projectId ?? DEFAULT_PROJECT
      }))
      .filter((s) => s.title.trim() && s.title !== '新会话')
  } catch {
    return []
  }
}

function loadPermission(): PermissionMode {
  try {
    const value = localStorage.getItem(PERMISSION_KEY)
    return value === 'readonly' || value === 'confirm' || value === 'auto'
      ? value
      : 'confirm'
  } catch {
    return 'confirm'
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

/** 记住当前会话 id：刷新页面后仍能回到同一会话与历史消息。 */
function loadCurrentSession(): string {
  try {
    return localStorage.getItem(CURRENT_SESSION_KEY) ?? ''
  } catch {
    return ''
  }
}

function persistCurrentSession(id: string) {
  try {
    localStorage.setItem(CURRENT_SESSION_KEY, id)
  } catch {
    // 隐私模式下不可写，忽略
  }
}

/**
 * 首次进入时生成的会话 id 也要立刻落盘：
 * 之前只在 newSession / selectSession 里持久化，刷新后又会生成新 id，
 * 历史虽写在 localStorage 里却对不上号，表现就是「刷新后对话全没了」。
 */
const initialSessionId = loadCurrentSession() || newId()
persistCurrentSession(initialSessionId)

/**
 * 极轻量全局状态：只放「布局与页面共享」的少量字段。
 *
 * <p>不引入 pinia：项目/环境/会话/执行策略几个字段，reactive 足够。
 */
export const appStore = reactive({
  projectId: DEFAULT_PROJECT,
  env: 'test',
  /** 当前会话使用的工作树（空 = 主仓库）；切换工作区时重置。 */
  worktree: '',
  sessionId: initialSessionId,
  sessions: loadSessions() as SessionItem[],
  /** 当前对话的执行策略（本地持久化，切换页面不丢）。 */
  permission: loadPermission() as PermissionMode,
  /** 登录身份（登录成功后写入；未登录为空）。 */
  identity: {
    userId: '',
    displayName: '',
    role: '',
    permissions: [] as string[]
  },

  /** 写入登录身份。 */
  setIdentity(identity: {
    userId: string
    displayName: string
    role: string
    permissions?: string[]
  }) {
    appStore.identity = { ...identity, permissions: identity.permissions ?? [] }
  },

  /** 清除登录身份。 */
  clearIdentity() {
    appStore.identity = { userId: '', displayName: '', role: '', permissions: [] }
  },

  /** 切换执行策略。 */
  setPermission(mode: PermissionMode) {
    appStore.permission = mode
    try {
      localStorage.setItem(PERMISSION_KEY, mode)
    } catch {
      // 隐私模式下不可写，忽略
    }
  },

  /**
   * 新建会话：只换 id，不登记占位条目。
   * 会话在首条用户消息落地时由 touchSession 写入，侧栏只展示有内容的会话。
   */
  newSession() {
    appStore.sessionId = newId()
    persistCurrentSession(appStore.sessionId)
  },

  /**
   * 删除一条会话：只摘掉侧栏条目（本地时间线由调用方通过 clearHistory 清理）。
   * 删的若是当前会话，回落到同工作区最近的一条，没有就新开一条。
   */
  removeSession(id: string) {
    const index = appStore.sessions.findIndex((s) => s.id === id)
    if (index < 0) return
    appStore.sessions.splice(index, 1)
    persist(appStore.sessions)
    if (appStore.sessionId !== id) return
    const latest = [...appStore.sessions]
      .filter((s) => s.projectId === appStore.projectId)
      .sort((a, b) => b.at - a.at)[0]
    if (latest) appStore.selectSession(latest.id)
    else appStore.newSession()
  },

  /** 选中历史会话；顺带把工作区切到该会话所属项目，避免项目与会话错配。 */
  selectSession(id: string) {
    const target = appStore.sessions.find((s) => s.id === id)
    if (target) appStore.projectId = target.projectId
    appStore.sessionId = id
    persistCurrentSession(id)
  },

  /** 切换当前工作树（空 = 主仓库）。 */
  setWorktree(name: string) {
    appStore.worktree = name
  },

  /** 切换工作区：选中该项目最近的一条会话，没有就新建一条。 */
  switchProject(projectId: string) {
    if (appStore.projectId === projectId) return
    appStore.projectId = projectId
    appStore.worktree = ''
    const latest = [...appStore.sessions]
      .filter((s) => s.projectId === projectId)
      .sort((a, b) => b.at - a.at)[0]
    if (latest) appStore.selectSession(latest.id)
    else appStore.newSession()
  },

  /** 记录/更新一条会话摘要（首条用户消息作为标题）。 */
  touchSession(title: string) {
    const id = appStore.sessionId
    const text = title.trim().slice(0, 40)
    const existing = appStore.sessions.find((s) => s.id === id)
    if (existing) {
      existing.at = Date.now()
      existing.projectId = appStore.projectId
      if (text && existing.title === '新会话') existing.title = text
    } else {
      appStore.sessions.unshift({
        id,
        title: text || '新会话',
        at: Date.now(),
        projectId: appStore.projectId
      })
    }
    appStore.sessions = appStore.sessions
      .sort((a, b) => b.at - a.at)
      .slice(0, MAX_SESSIONS)
    persist(appStore.sessions)
    persistCurrentSession(id)
  }
})
