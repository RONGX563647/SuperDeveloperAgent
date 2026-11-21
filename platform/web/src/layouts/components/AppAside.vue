<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Delete, Document, Folder, FolderOpened, Notebook, Plus, Fold, Expand, Search, Setting, Box, SwitchButton, ArrowRight, ArrowDown, FolderAdd, Warning } from '@element-plus/icons-vue'
import { ApiError } from '../../api/http'
import { logout } from '../../api/auth'
import { listProjects, type ProjectRow } from '../../api/project'
import { appStore, type SessionItem } from '../../stores/app'
import { clearHistory } from '../../state/sessionHistory'
import { disposeSessionChat } from '../../state/sessionChat'
import { deleteConversation, listConversations } from '../../api/workspace'
import { relativeTime } from '../../utils/status'

/**
 * 左侧栏 —— 结构对齐 DSH：
 * 品牌行（Logo + 折叠）/「新会话」软底按钮 / 一组入口（对话、流水线、设置）/「工作区」分组标题
 * （带搜索与更多）/ 会话列表（时间 hover 才出现）/ 底部用户行。
 *
 * 层级全靠白度差：侧栏 --sidebar-background-color 比主区 --bg 略亮一档，
 * 只在对主区一侧画一条发丝线。
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

/**
 * 侧栏宽度：可拖拽调整（240~420），按用户持久化。
 * 之前固定 260px，长工作区/会话名被截断；加宽 + 可调后由用户自己决定。
 */
const ASIDE_WIDTH_KEY = 'agent.aside.width'
const MIN_ASIDE_WIDTH = 240
const MAX_ASIDE_WIDTH = 420
const DEFAULT_ASIDE_WIDTH = 288

function loadAsideWidth(): number {
  try {
    const value = Number(localStorage.getItem(ASIDE_WIDTH_KEY))
    return Number.isFinite(value) && value >= MIN_ASIDE_WIDTH && value <= MAX_ASIDE_WIDTH
      ? value
      : DEFAULT_ASIDE_WIDTH
  } catch {
    return DEFAULT_ASIDE_WIDTH
  }
}

const asideWidth = ref(loadAsideWidth())
const resizing = ref(false)

function onResizeMove(event: PointerEvent): void {
  asideWidth.value = Math.min(MAX_ASIDE_WIDTH, Math.max(MIN_ASIDE_WIDTH, Math.round(event.clientX)))
}

function stopResize(): void {
  if (!resizing.value) return
  resizing.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', stopResize)
  try {
    localStorage.setItem(ASIDE_WIDTH_KEY, String(asideWidth.value))
  } catch {
    // 隐私模式：仅本次会话生效
  }
}

function startResize(event: PointerEvent): void {
  if (props.collapsed || window.matchMedia('(max-width: 900px)').matches) return
  event.preventDefault()
  resizing.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onResizeMove)
  window.addEventListener('pointerup', stopResize)
}

onBeforeUnmount(stopResize)

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

/** 在指定工作区里新建会话并跳转（只在本地换 id，首条消息再落库）。 */
function newSessionIn(projectId: string): void {
  appStore.projectId = projectId
  appStore.worktree = ''
  appStore.newSession()
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
  collapsedGroups.value = { ...collapsedGroups.value, [id]: !groupCollapsed(id) }
}

/** 默认只展开当前工作区，其余分组收起——侧栏首屏不再堆几十条历史会话。 */
function groupCollapsed(id: string): boolean {
  return collapsedGroups.value[id] ?? id !== appStore.projectId
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

/** 新会话只换本地 id：首条消息落地时才落库，避免每点一次都在服务端留下空会话。 */
function newSession(): void {
  appStore.newSession()
  void router.push('/chat')
}

function openSession(id: string) {
  appStore.selectSession(id)
  void router.push('/chat')
}

/**
 * 删除会话：服务端（会话 + 历史运行 + 任务 + 账本 + 记忆）与本地时间线一起清。
 * 服务端未删成功时保留侧栏条目，避免「界面没有了、数据库还在」。
 */
async function removeSession(id: string) {
  disposeSessionChat(id)
  try {
    await deleteConversation(id)
  } catch (e) {
    const status = e instanceof ApiError ? e.status : 0
    // 从未同步到服务端的本地会话，服务端会回「不存在」，按已删除处理
    if (status !== 400 && status !== 404) {
      ElMessage.error(e instanceof Error ? e.message : '删除会话失败')
      return
    }
  }
  clearHistory(id)
  appStore.removeSession(id)
}

async function doLogout() {
  await logout()
  appStore.clearIdentity()
  void router.push('/login')
}

/** 统一初始密码是公开的：未改密时常驻提示，直达设置页的「账号安全」分区。 */
function goChangePassword(): void {
  void router.push({ name: 'settings', query: { section: 'account' } })
}

const permissionHint = computed(() => {
  const list = identity.value.permissions
  if (list.includes('*')) return '全部权限'
  return list.length ? `${list.length} 项权限` : '未加载权限'
})

onMounted(async () => {
  await loadProjects()
  await loadServerSessions()
})

// 切换工作区（含从工作区页返回）时刷新该工作区的服务端会话
watch(() => appStore.projectId, () => void loadServerSessions())
</script>

<template>
  <aside
    class="aside"
    :class="{ collapsed, resizing, 'mobile-open': mobileOpen }"
    :style="{ '--aside-w': `${asideWidth}px` }"
  >
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
        <button type="button" class="new-chat" title="新会话" @click="newSession">
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
            :title="e.label"
          >
            <el-icon><component :is="e.icon" /></el-icon>
            <span>{{ e.label }}</span>
          </router-link>
          <router-link
            to="/settings"
            class="nav-entry"
            :class="{ active: activeName === 'settings' }"
            title="设置"
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
                <span class="ws-name" :title="g.name">{{ g.name }}</span>
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
                :title="groupCollapsed(g.id) ? '展开分组' : '收起分组'"
                @click="toggleGroup(g.id)"
              >
                <el-icon><component :is="groupCollapsed(g.id) ? ArrowRight : ArrowDown" /></el-icon>
              </button>
            </div>
            <div v-if="!groupCollapsed(g.id)" class="ws-sessions">
              <div
                v-for="s in visibleSessions(g)" :key="s.id"
                class="session-row" :class="{ active: s.id === appStore.sessionId }"
              >
                <button type="button" class="session-item" @click="openSession(s.id)">
                  <el-icon class="session-icon"><Document /></el-icon>
                  <span class="session-title" :title="s.title">{{ s.title }}</span>
                  <span class="session-time">{{ relativeTime(s.at) }}</span>
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
        <button
          v-if="identity.usingInitialPassword"
          type="button" class="initial-password-tip"
          @click="goChangePassword"
        >
          <el-icon><Warning /></el-icon>
          <span>仍在使用初始密码，点此修改</span>
        </button>
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
    <div
      class="aside-resizer"
      title="拖动调整侧栏宽度"
      @pointerdown="startResize"
    />
  </aside>
</template>

<style scoped>
.aside {
  position: relative;
  display: flex;
  flex: 0 0 var(--aside-w, 288px);
  flex-direction: column;
  width: var(--aside-w, 288px);
  height: 100%;
  min-height: 0;
  overflow: hidden;
  background: var(--sidebar-background-color);
  border-right: 1px solid var(--line-soft);
  transition: flex-basis var(--dur-base) var(--ease-out), width var(--dur-base) var(--ease-out);
}

/* 折叠 = 收成 64px 图标栏（不是消失）：展开按钮留在栏顶，随时可恢复 */
.aside.collapsed {
  flex-basis: 64px;
  width: 64px;
}
/* 拖拽过程中不要过渡，否则宽度会追着指针跑 */
.aside.resizing {
  transition: none;
}
.aside.collapsed .aside-inner {
  width: 64px;
}
/* 只有桌面端折叠才隐藏文字；移动端抽屉即使处于折叠状态也要完整展示 */
@media (min-width: 901px) {
  .aside.collapsed .brand-text,
  .aside.collapsed .new-chat span,
  .aside.collapsed .nav-entry span,
  .aside.collapsed .group-head,
  .aside.collapsed .ws-search,
  .aside.collapsed .ws-archived,
  .aside.collapsed .workspace-list,
  .aside.collapsed .user-meta,
  .aside.collapsed .logout-btn,
  .aside.collapsed .initial-password-tip {
    display: none;
  }
  .aside.collapsed .aside-top {
    justify-content: center;
    padding: 0;
  }
  .aside.collapsed .brand {
    display: none;
  }
  .aside.collapsed .aside-scroll {
    padding: 4px 10px 8px;
  }
  .aside.collapsed .new-chat,
  .aside.collapsed .nav-entry {
    justify-content: center;
    padding: 0;
    gap: 0;
  }
  .aside.collapsed .aside-bottom {
    padding: 8px 0;
  }
  .aside.collapsed .user-chip {
    justify-content: center;
    padding: 6px 0;
  }
}

/* 宽度拖拽手柄：贴住右边界内侧，hover 才显形 */
.aside-resizer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 2;
  width: 5px;
  cursor: col-resize;
  background: transparent;
  transition: background-color var(--dur-fast) ease;
}
.aside-resizer:hover,
.aside-resizer:active {
  background: var(--accent);
  opacity: 0.35;
}
.aside.collapsed .aside-resizer {
  display: none;
}
.aside-inner {
  display: flex;
  flex: 1;
  flex-direction: column;
  width: var(--aside-w, 288px);
  min-height: 0;
}

/* 窄屏（≤900px）：侧栏变成离屏抽屉 —— 288px 常驻会把主区挤到只剩一百来像素。
   抽屉由 PageHeader 的菜单按钮打开，遮罩 / 路由跳转 / Esc 关闭（见 AppLayout）。 */
@media (max-width: 900px) {
  .aside {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 80;
    flex-basis: auto;
    width: min(86vw, 320px);
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
    width: min(86vw, 320px);
    border-right: 1px solid var(--line-soft);
    transform: translateX(-100%);
  }
  .aside.collapsed.mobile-open {
    transform: translateX(0);
  }
  .aside-inner,
  .aside.collapsed .aside-inner {
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
  width: 26px;
  height: 26px;
  font-size: 12px;
  font-weight: 700;
  color: var(--brand-mark-ink);
  letter-spacing: -0.02em;
  background: var(--brand-mark-bg);
  border-radius: 8px;
  box-shadow: var(--shadow-1);
}
.brand-text {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 图标按钮复用全局 .icon-btn / .icon-btn.sm */

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

/* 新会话：白底卡片做主按钮 —— 比描边按钮实、比实心块轻 */
.new-chat {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--sp-4);
  height: var(--control-lg);
  padding: 0 var(--sp-5);
  margin-bottom: var(--sp-2);
  font-family: inherit;
  font-size: var(--text-sm);
  font-weight: 500;
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
  box-shadow: var(--shadow-2);
}

/* 入口 */
.nav {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 1px;
  margin-top: var(--sp-2);
}
.nav-entry {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  height: var(--control-lg);
  padding: 0 var(--sp-5);
  font-family: inherit;
  font-size: var(--text-sm);
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
  gap: var(--sp-3);
  padding: var(--sp-7) var(--sp-5) var(--sp-2);
}
.group-title {
  flex: 1;
  font-size: var(--text-2xs);
  font-weight: 600;
  color: var(--ink-muted);
  letter-spacing: 0.06em;
  text-transform: uppercase;
}
.group-actions {
  display: flex;
  gap: 2px;
  opacity: 0.55;
  transition: opacity var(--dur-fast) ease;
}
.group-head:hover .group-actions,
.group-head:focus-within .group-actions {
  opacity: 1;
}
/* 触屏没有 hover：操作常显，避免够不到 */
@media (hover: none) {
  .group-actions,
  .ws-group-actions,
  .session-del {
    opacity: 1;
  }
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
/*
 * 选中态只落在「单条会话」上（.session-item.active 的浅灰块 + 左侧蓝条），
 * 工作区只做轻量标识（图标/名称变色加粗），不再给整块分组铺浅蓝底，
 * 否则切换会话时整块工作区一起高亮，看起来像选中了整个工作区。
 */
.ws-group.active .ws-name {
  font-weight: 700;
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
  opacity: 0.55;
  transition: opacity var(--dur-fast) ease;
}
.ws-group-head:hover .ws-group-actions,
.ws-group-head:focus-within .ws-group-actions {
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
.initial-password-tip {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  width: 100%;
  padding: var(--sp-4) var(--sp-5);
  margin-bottom: var(--sp-3);
  font-family: inherit;
  font-size: var(--text-2xs);
  line-height: 1.45;
  color: var(--warn-ink);
  text-align: left;
  cursor: pointer;
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: var(--radius-md);
}
.initial-password-tip:hover {
  background: var(--warn-soft-strong);
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
  width: var(--control-sm);
  height: var(--control-sm);
  padding: 0;
  margin-left: auto;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.logout-btn:hover {
  color: var(--danger);
  background: var(--surface-hover);
}
</style>
