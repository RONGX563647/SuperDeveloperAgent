<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Delete, Document, Folder, FolderOpened, Notebook, Plus, Fold, Expand, Search, Setting, Box, SwitchButton, ArrowRight, ArrowDown, FolderAdd } from '@element-plus/icons-vue'
import { logout } from '../../api/auth'
import { listProjects, type ProjectRow } from '../../api/project'
import { appStore, type SessionItem } from '../../stores/app'
import { clearHistory } from '../../state/sessionHistory'
import { createConversation, listConversations } from '../../api/workspace'

/**
 * 左侧栏 —— 结构对齐 DSH：
 * 品牌行（Logo + 折叠）/「新会话」软底按钮 / 一组入口（对话、流水线、设置）/「工作区」分组标题
 * （带搜索与更多）/ 会话列表（时间 hover 才出现）/ 底部用户行。
 *
 * 层级全靠白度差：侧栏 #f5f6f8 比主区 #f3f4f6 略亮一档，只在对主区一侧画一条发丝线。
 */
const props = defineProps<{ collapsed: boolean; mobileOpen?: boolean }>()
const emit = defineEmits<{
  'update:collapsed': [value: boolean]
  close: []
}>()

/** ≤900px 时侧栏是离屏抽屉：折叠按钮改为关闭抽屉，而不是压缩宽度。 */
function onFold(): void {
  if (window.matchMedia('(max-width: 900px)').matches) emit('close')
  else toggle()
}

const route = useRoute()
const router = useRouter()
const identity = computed(() => appStore.identity)

const entries = [
  { to: '/chat', name: 'chat', icon: ChatDotRound, label: '对话' },
  { to: '/projects', name: 'project-list', icon: FolderOpened, label: '项目' },
  { to: '/pipeline', name: 'pipeline', icon: Notebook, label: '流水线' }
]

const activeName = computed(() => String(route.name ?? ''))
const projects = ref<ProjectRow[]>([])
const searchOpen = ref(false)
const searchText = ref('')
const searchRef = ref<HTMLInputElement | null>(null)
const collapsedGroups = ref<Record<string, boolean>>({})
const expandedGroups = ref<Record<string, boolean>>({})
/** 已归档的工作区（= 项目）：只从侧栏隐藏，不影响项目本身与会话数据。 */
const ARCHIVED_WS_KEY = 'agent.workspaces.archived'
const archivedIds = ref<string[]>(loadArchived())
const showArchived = ref(false)

function loadArchived(): string[] {
  try {
    const raw = localStorage.getItem(ARCHIVED_WS_KEY)
    return raw ? (JSON.parse(raw) as string[]) : []
  } catch {
    return []
  }
}

function persistArchived(): void {
  try {
    localStorage.setItem(ARCHIVED_WS_KEY, JSON.stringify(archivedIds.value))
  } catch {
    // 隐私模式下不可写，忽略
  }
}

async function loadProjects(): Promise<void> {
  try {
    projects.value = await listProjects()
  } catch {
    projects.value = []
  }
}

/**
 * 用服务端会话补全侧栏：换浏览器 / 清缓存后仍能看到已持久化的会话。
 * 只拉当前工作区（其余工作区由项目列表 + 本地会话覆盖），避免打开页面就 N 次请求。
 */
async function loadServerSessions(): Promise<void> {
  const projectId = appStore.projectId
  if (!projectId) return
  try {
    const rows = await listConversations(projectId, false)
    const local = new Map(appStore.sessions.map((s) => [s.id, s]))
    const merged: SessionItem[] = []
    for (const row of rows) {
      // 调度会话只存在于工作区首页，不混进普通会话列表
      if (row.kind === 'SUPERVISOR' || row.archived) continue
      const known = local.get(row.id)
      local.delete(row.id)
      merged.push({
        id: row.id,
        title: row.title || known?.title || '新会话',
        at: Math.max(known?.at ?? 0, row.lastActivityAt),
        projectId: row.projectId
      })
    }
    // 当前工作区以服务端为准，本地其余工作区的会话原样保留
    appStore.sessions = [...merged, ...local.values()]
      .sort((a, b) => b.at - a.at)
      .slice(0, 30)
  } catch {
    // 服务端不可用时退回纯本地会话
  }
}

function projectName(id: string): string {
  return projects.value.find((p) => p.id === id)?.name ?? id
}

/** 工作区 = 项目；每个分组下只放该项目自己的会话。 */
const groups = computed(() => {
  const query = searchText.value.trim().toLowerCase()
  const byProject = new Map<string, SessionItem[]>()
  for (const s of appStore.sessions) {
    if (query
      && !s.title.toLowerCase().includes(query)
      && !projectName(s.projectId).toLowerCase().includes(query)) continue
    const bucket = byProject.get(s.projectId) ?? []
    bucket.push(s)
    byProject.set(s.projectId, bucket)
  }
  // 当前工作区始终保留：项目清单为空（未接入 / 已下线）时也要能点击进入调度页；
  // 用 Set 去重，避免「项目清单 + 当前工作区 + 本地会话」三路来源出现同名分组。
  const ids = [...new Set([
    ...projects.value.map((p) => p.id),
    ...(appStore.projectId ? [appStore.projectId] : []),
    ...byProject.keys()
  ])]
  return ids
    .filter((id) => !archivedIds.value.includes(id))
    .map((id) => ({
      id,
      name: projectName(id),
      sessions: (byProject.get(id) ?? []).sort((a, b) => b.at - a.at),
      active: id === appStore.projectId
    }))
})

/** 已归档工作区（用于侧栏底部恢复入口）。 */
const archivedGroups = computed(() =>
  archivedIds.value.map((id) => ({ id, name: projectName(id) })))

/** 在指定工作区里新建会话并跳转。 */
async function newSessionIn(projectId: string): Promise<void> {
  appStore.projectId = projectId
  try {
    const created = await createConversation(projectId, { title: '新会话', env: appStore.env })
    appStore.selectSession(created.id)
  } catch {
    appStore.newSession()
  }
  void router.push('/chat')
}

/** 添加工作区 = 去「项目接入」登记一个新的本地目录 / 远程仓库。 */
function addWorkspace(): void {
  void router.push('/projects/intake')
}

/** 归档工作区：仅从侧栏移除；若归档的是当前工作区，自动切到剩下的第一个。 */
function archiveWorkspace(projectId: string): void {
  if (archivedIds.value.includes(projectId)) return
  archivedIds.value = [...archivedIds.value, projectId]
  persistArchived()
  if (appStore.projectId === projectId) {
    const next = projects.value.find((p) => !archivedIds.value.includes(p.id))
    if (next) appStore.switchProject(next.id)
  }
}

function restoreWorkspace(projectId: string): void {
  archivedIds.value = archivedIds.value.filter((id) => id !== projectId)
  persistArchived()
}

function visibleSessions(group: { id: string; sessions: SessionItem[] }): SessionItem[] {
  return expandedGroups.value[group.id]
    ? group.sessions
    : group.sessions.slice(0, 6)
}

function toggleGroup(id: string): void {
  collapsedGroups.value = { ...collapsedGroups.value, [id]: !collapsedGroups.value[id] }
}

function toggleExpand(id: string): void {
  expandedGroups.value = { ...expandedGroups.value, [id]: !expandedGroups.value[id] }
}

function toggleSearch(): void {
  searchOpen.value = !searchOpen.value
  if (searchOpen.value) void nextTick(() => searchRef.value?.focus())
  else searchText.value = ''
}

/**
 * 点组头 = 打开该工作区的调度首页（工作区 = 项目）。
 * 这里不再直接落到某条会话：工作区本身有一个统一管理全部会话的调度 Agent。
 */
function selectWorkspace(id: string): void {
  appStore.projectId = id
  void router.push(`/workspace/${id}`)
  emit('close')
}

function toggle() {
  emit('update:collapsed', !props.collapsed)
}

async function newSession() {
  const projectId = appStore.projectId
  try {
    const created = await createConversation(projectId, { title: '新会话', env: appStore.env })
    appStore.selectSession(created.id)
  } catch {
    appStore.newSession()
  }
  void router.push('/chat')
}

function openSession(id: string) {
  appStore.selectSession(id)
  void router.push('/chat')
}

/** 删除会话：侧栏条目 + 本地时间线一起清，删当前会话时 store 会自动回落。 */
function removeSession(id: string) {
  clearHistory(id)
  appStore.removeSession(id)
}

async function doLogout() {
  await logout()
  appStore.clearIdentity()
  void router.push('/login')
}

const permissionHint = computed(() => {
  const list = identity.value.permissions
  if (list.includes('*')) return '全部权限'
  return list.length ? `${list.length} 项权限` : '未加载权限'
})

/** 相对时间：比绝对时间短，且不会出现「11-08 15:42」这种要换算的字符串。 */
function formatTime(ms: number): string {
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

onMounted(async () => {
  await loadProjects()
  await loadServerSessions()
})

// 切换工作区（含从工作区页返回）时刷新该工作区的服务端会话
watch(() => appStore.projectId, () => void loadServerSessions())
</script>

<template>
  <aside class="aside" :class="{ collapsed, 'mobile-open': mobileOpen }">
    <div class="aside-inner">
      <!-- 品牌行 -->
      <div class="aside-top">
        <div class="brand">
          <span class="brand-mark" aria-hidden="true">运</span>
          <span class="brand-text">研发运维智能体</span>
        </div>
        <button type="button" class="icon-btn" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="onFold">
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        </button>
      </div>

      <!-- 滚动区：入口 + 工作区列表 -->
      <div class="aside-scroll">
        <button type="button" class="new-chat" @click="newSession">
          <el-icon><Plus /></el-icon>
          <span>新会话</span>
        </button>

        <nav class="nav" aria-label="主导航">
          <router-link
            v-for="e in entries"
            :key="e.name"
            :to="e.to"
            class="nav-entry"
            :class="{ active: activeName === e.name }"
          >
            <el-icon><component :is="e.icon" /></el-icon>
            <span>{{ e.label }}</span>
          </router-link>
          <router-link
            to="/settings"
            class="nav-entry"
            :class="{ active: activeName === 'settings' }"
          >
            <el-icon><Setting /></el-icon>
            <span>设置</span>
          </router-link>
        </nav>

        <div class="group-head">
          <span class="group-title">工作区</span>
          <span class="group-actions">
            <button
              type="button" class="icon-btn sm" :class="{ on: searchOpen }"
              title="搜索会话 / 项目" @click="toggleSearch"
            >
              <el-icon><Search /></el-icon>
            </button>
            <button
              type="button" class="icon-btn sm" title="添加工作区（项目接入）" @click="addWorkspace"
            >
              <el-icon><FolderAdd /></el-icon>
            </button>
            <button
              type="button" class="icon-btn sm" :class="{ on: showArchived }"
              :disabled="!archivedGroups.length"
              :title="archivedGroups.length ? `已归档 ${archivedGroups.length} 个工作区` : '暂无已归档工作区'"
              @click="showArchived = !showArchived"
            >
              <el-icon><Box /></el-icon>
            </button>
          </span>
        </div>

        <div v-if="searchOpen" class="ws-search">
          <el-icon><Search /></el-icon>
          <input ref="searchRef" v-model="searchText" type="text" placeholder="搜索会话 / 项目" />
        </div>

        <!-- 已归档工作区：默认收起，点开可恢复 -->
        <div v-if="showArchived && archivedGroups.length" class="ws-archived">
          <p class="ws-archived-title">已归档工作区</p>
          <div v-for="a in archivedGroups" :key="a.id" class="ws-archived-row">
            <el-icon><Box /></el-icon>
            <span class="ws-archived-name">{{ a.name }}</span>
            <button type="button" class="ws-restore" @click="restoreWorkspace(a.id)">恢复</button>
          </div>
        </div>

        <!-- 工作区 = 项目：每个项目一个可折叠分组，点组头切换当前工作区 -->
        <div class="workspace-list">
          <section
            v-for="g in groups" :key="g.id"
            class="ws-group" :class="{ active: g.active }"
          >
            <div class="ws-group-head">
              <button
                type="button" class="ws-group-main"
                :title="`切换到工作区 ${g.name}`" @click="selectWorkspace(g.id)"
              >
                <el-icon class="ws-icon"><Folder /></el-icon>
                <span class="ws-name">{{ g.name }}</span>
                <span class="ws-count mono">{{ g.sessions.length }}</span>
              </button>
              <span class="ws-group-actions">
                <button
                  type="button" class="ws-act" title="在此工作区新建会话"
                  @click.stop="newSessionIn(g.id)"
                >
                  <el-icon><Plus /></el-icon>
                </button>
                <button
                  type="button" class="ws-act" title="归档工作区（仅从侧栏隐藏）"
                  @click.stop="archiveWorkspace(g.id)"
                >
                  <el-icon><Box /></el-icon>
                </button>
              </span>
              <button
                type="button" class="ws-caret"
                :title="collapsedGroups[g.id] ? '展开分组' : '收起分组'"
                @click="toggleGroup(g.id)"
              >
                <el-icon><component :is="collapsedGroups[g.id] ? ArrowRight : ArrowDown" /></el-icon>
              </button>
            </div>
            <div v-if="!collapsedGroups[g.id]" class="ws-sessions">
              <div
                v-for="s in visibleSessions(g)" :key="s.id"
                class="session-row" :class="{ active: s.id === appStore.sessionId }"
              >
                <button type="button" class="session-item" @click="openSession(s.id)">
                  <el-icon class="session-icon"><Document /></el-icon>
                  <span class="session-title">{{ s.title }}</span>
                  <span class="session-time">{{ formatTime(s.at) }}</span>
                </button>
                <button
                  type="button" class="session-del" title="删除会话"
                  @click.stop="removeSession(s.id)"
                >
                  <el-icon><Delete /></el-icon>
                </button>
              </div>
              <button
                v-if="g.sessions.length > 6" type="button" class="ws-more"
                @click="toggleExpand(g.id)"
              >
                {{ expandedGroups[g.id] ? '收起' : `还有 ${g.sessions.length - 6} 条会话` }}
              </button>
              <p v-if="!g.sessions.length" class="ws-empty">暂无会话</p>
            </div>
          </section>
          <p v-if="!groups.length" class="session-empty">没有匹配的工作区或会话。</p>
        </div>
      </div>

      <!-- 底部用户行 -->
      <div class="aside-bottom">
        <div class="user-chip">
          <span class="user-avatar" aria-hidden="true">{{ (identity.displayName || identity.userId).slice(0, 1).toUpperCase() }}</span>
          <div class="user-meta">
            <span class="user-name">{{ identity.displayName }}</span>
            <span class="user-hint">{{ identity.role || '未登录' }} · {{ permissionHint }}</span>
          </div>
          <button type="button" class="logout-btn" title="退出登录" @click="doLogout">
            <el-icon><SwitchButton /></el-icon>
          </button>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.aside {
  display: flex;
  flex: 0 0 var(--sidebar-width-260);
  flex-direction: column;
  width: var(--sidebar-width-260);
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--sidebar-background-color);
  border-right: 1px solid var(--line-soft);
  transition: flex-basis var(--dur-base) var(--ease-out), width var(--dur-base) var(--ease-out);
}
.aside.collapsed {
  flex-basis: 0;
  width: 0;
  border-right: none;
}
.aside-inner {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: var(--sidebar-width-260);
  min-height: 0;
}

/* 窄屏（≤900px）：侧栏变成离屏抽屉 —— 260px 常驻会把主区挤到只剩一百来像素。
   抽屉由 PageHeader 的菜单按钮打开，遮罩 / 路由跳转 / Esc 关闭（见 AppLayout）。 */
@media (max-width: 900px) {
  .aside {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 80;
    flex-basis: auto;
    width: min(86vw, 300px);
    border-right: 1px solid var(--line-soft);
    box-shadow: var(--shadow-2);
    transform: translateX(-100%);
    transition: transform var(--dur-base) var(--ease-out);
  }
  .aside.mobile-open {
    transform: translateX(0);
  }
  /* 桌面折叠状态在手机上不生效：抽屉打开时必须完整可见 */
  .aside.collapsed {
    flex-basis: auto;
    width: min(86vw, 300px);
    border-right: 1px solid var(--line-soft);
    transform: translateX(-100%);
  }
  .aside.collapsed.mobile-open {
    transform: translateX(0);
  }
  .aside-inner {
    display: flex;
    width: 100%;
  }
}

/* ---- 品牌行 ---- */
.aside-top {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  height: var(--header-height);
  padding: 0 10px 0 14px;
}
.brand {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 9px;
  min-width: 0;
}
/* 品牌标记：深灰实心块 + 白字 —— 不占用交互蓝，浅色底上也够醒目 */
.brand-mark {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  font-size: 12px;
  font-weight: 700;
  color: var(--brand-mark-ink);
  letter-spacing: -0.02em;
  background: var(--brand-mark-bg);
  border-radius: 7px;
}
.brand-text {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 图标按钮（多处复用） */
.icon-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  font-size: 14px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.icon-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.icon-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.icon-btn.sm {
  width: 22px;
  height: 22px;
  font-size: 13px;
}

/* ---- 滚动区 ---- */
.aside-scroll {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 2px;
  min-height: 0;
  padding: 4px 8px 8px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* 新会话：DSH 用白底卡片做主按钮 —— 比描边按钮实、比实心块轻 */
.new-chat {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 10px;
  margin-bottom: 6px;
  font-family: inherit;
  font-size: 13px;
  color: var(--ink);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-1);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease,
    box-shadow var(--dur-fast) ease;
}
.new-chat:hover {
  border-color: var(--line);
  box-shadow: 0 2px 6px rgb(16 24 40 / 8%);
}

/* 入口 */
.nav {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 1px;
  margin-top: 8px;
}
.nav-entry {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 34px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--ink-soft);
  text-decoration: none;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.nav-entry:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
/* 选中：只提亮文字 + 极浅底，不加蓝条 —— 侧栏要安静 */
.nav-entry.active {
  color: var(--ink);
  background: var(--surface-hover);
  font-weight: 500;
}
.nav-entry.is-muted {
  color: var(--ink-muted);
  cursor: not-allowed;
}

/* 分组标题 */
.group-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 16px 10px 4px;
}
.group-title {
  flex: 1;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  letter-spacing: 0.06em;
}
.group-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.group-head:hover .group-actions {
  opacity: 1;
}

/* 工作区搜索 */
.icon-btn.on {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.ws-search {
  display: flex;
  flex: none;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 9px;
  margin: 4px 2px 2px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.ws-search input {
  flex: 1;
  min-width: 0;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: none;
  border: 0;
  outline: none;
}
.ws-search input::placeholder {
  color: var(--ink-faint);
}

/* 工作区分组：项目为一级，会话为二级 */
.workspace-list {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 2px;
  padding-top: 2px;
}
.ws-group {
  border-radius: var(--radius-sm);
}
.ws-group-head {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 30px;
  padding-right: 2px;
}
.ws-group-main {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 7px;
  min-width: 0;
  height: 100%;
  padding: 0 6px 0 8px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
}
.ws-group-main:hover {
  background: var(--surface-hover);
}
.ws-icon {
  flex: none;
  font-size: 14px;
  color: var(--ink-muted);
}
.ws-group.active .ws-icon {
  color: var(--accent-ink);
}
.ws-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ws-group.active .ws-name {
  color: var(--ink);
}
.ws-count {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ws-caret {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.ws-caret:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
/* 组头悬浮操作：新建会话 / 归档工作区（默认不占视觉，hover 才出现） */
.ws-group-actions {
  display: flex;
  flex: none;
  gap: 1px;
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.ws-group-head:hover .ws-group-actions {
  opacity: 1;
}
.ws-act {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.ws-act:hover {
  color: var(--ink);
  background: var(--surface-active);
}

/* 已归档工作区 */
.ws-archived {
  flex: none;
  padding: 6px 10px 4px;
  margin: 4px 2px 2px;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.ws-archived-title {
  margin: 0 0 2px;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ws-archived-row {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  font-size: 12px;
  color: var(--ink-muted);
}
.ws-archived-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ws-restore {
  padding: 0;
  font-family: inherit;
  font-size: 11px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
}
.ws-sessions {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding: 0 2px 4px 14px;
}
.ws-sessions .session-item {
  height: 29px;
  padding: 0 8px;
  font-size: 12.5px;
}
.ws-more,
.ws-empty {
  padding: 4px 8px;
  margin: 0;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
}
.ws-more:hover {
  color: var(--accent-ink);
}
.ws-empty {
  cursor: default;
  line-height: 1.6;
}

/* 会话条目 */
.session-list {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 1px;
}
/* 一行 = 会话按钮 + hover 出现的删除键（按钮不能嵌套按钮，所以外面加一层容器） */
.session-row {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
}
.session-item {
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
  gap: 7px;
  width: 100%;
  height: 30px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-soft);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.session-item:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
/* 选中：浅灰块 + 左侧 2px 蓝色竖条（用 inset 阴影贴合圆角，避免浮一块色条上去） */
.session-item.active {
  color: var(--ink);
  background: var(--surface-active);
  box-shadow: inset 2px 0 0 var(--accent);
  font-weight: 500;
}
.session-icon {
  flex: none;
  font-size: 13px;
  color: var(--ink-faint);
}
.session-item.active .session-icon {
  color: var(--ink-muted);
}
.session-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 时间常显：列表扫读靠它区分新旧，hover 才显示会让整列忽隐忽现 */
.session-time {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
  font-variant-numeric: tabular-nums;
  transition: opacity var(--dur-fast) ease;
}
/* hover 时时间让位给删除键，避免两个控件叠在一起 */
.session-row:hover .session-time {
  opacity: 0;
}
/* 删除键：透明底、与行同色，hover 才显形；不铺白块才像是行内的一部分 */
.session-del {
  position: absolute;
  right: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  font-size: 13px;
  color: var(--ink-muted);
  cursor: pointer;
  opacity: 0;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
  transition: opacity var(--dur-fast) ease, color var(--dur-fast) ease;
}
.session-row:hover .session-del,
.session-del:focus-visible {
  opacity: 1;
}
.session-del:hover {
  color: var(--danger);
}
.session-empty {
  padding: 6px 10px;
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-muted);
}

/* ---- 底部用户行 ---- */
.aside-bottom {
  flex: none;
  padding: 8px;
  border-top: 1px solid var(--line-soft);
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 8px;
  border-radius: var(--radius-md);
  transition: background-color var(--dur-fast) ease;
}
.user-chip:hover {
  background: var(--surface-hover);
}
.user-avatar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
  background: var(--surface-active);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.user-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}
.user-name {
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.user-hint {
  font-size: 10.5px;
  color: var(--ink-muted);
  white-space: nowrap;
}
.logout-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  margin-left: auto;
  color: rgb(0 0 0 / 45%);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 8px;
}
.logout-btn:hover {
  color: #f56c6c;
  background: rgb(0 0 0 / 5%);
}
</style>
