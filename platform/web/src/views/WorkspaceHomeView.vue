<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Aim, CaretRight, ChatDotRound, Check, Plus, Promotion, Refresh, Search,
} from '@element-plus/icons-vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import { listProjects, type ProjectRow } from '../api/project'
import { useWorkspaceEvents, type WorkspaceEvent } from '../composables/useWorkspaceEvents'
import { getWorkspaceChat } from '../state/workspaceChat'
import { clearHistory } from '../state/sessionHistory'
import ConversationTimeline from '../components/chat/ConversationTimeline.vue'
import PageHeader from '../layouts/components/PageHeader.vue'
import {
  cancelRun, createConversation, createTask, listConversations, listRunLedger,
  listRuns, listTasks, sendToConversation, syncConversations, updateTask,
  type ConversationRow, type RunLedgerRow, type RunRow, type TaskRow,
} from '../api/workspace'
import { envLabel } from '../utils/pipelineLabels'

/**
 * 工作区首页：中间调度 Agent + 右侧任务/运行。
 *
 * 工作区 = 项目；调度 Agent 只做编排（创建/派发/跟踪/汇总），没有任何写工具，
 * 真正执行落到子会话，生产写操作仍由人工确认。
 * 消息区与对话页共用 ConversationTimeline / composer 样式，保持同一套阅读节奏。
 */
const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId ?? appStore.projectId))
const projectName = ref('')
const projects = ref<ProjectRow[]>([])

const conversations = ref<ConversationRow[]>([])
/**
 * 调度会话状态按项目常驻（state/workspaceChat）：切换页面/工作区只换视图，
 * 在途的 SSE 读流不会被打断，回来时还能接着看到正在增长的回答。
 */
const entry = computed(() => getWorkspaceChat(projectId.value))
const supervisor = computed(() => entry.value.supervisor.value)
const projection = computed(() => entry.value.chat.projection.value)
const streaming = computed(() => entry.value.chat.streaming.value)
const elapsedMs = computed(() => entry.value.chat.elapsedMs.value)
const tokens = computed(() => entry.value.chat.tokens.value)
const error = computed(() => entry.value.chat.error.value)
const tasks = ref<TaskRow[]>([])
const runs = ref<RunRow[]>([])
const input = ref('')
const loading = ref(false)
const lastRefreshed = ref(0)
const messageListRef = ref<HTMLElement | null>(null)
const composerRef = ref<HTMLTextAreaElement | null>(null)

const sideTab = ref<'tasks' | 'runs'>('tasks')
const runTab = ref<'active' | 'recent'>('active')
const showDoneTasks = ref(false)

const paletteOpen = ref(false)
const paletteQuery = ref('')
const paletteIndex = ref(0)
const paletteInputRef = ref<HTMLInputElement | null>(null)

const createOpen = ref(false)
const createForm = reactive({ title: '', env: appStore.env, worktree: '', advanced: false })

const taskDialog = ref(false)
const editingTask = ref<TaskRow | null>(null)
const taskForm = reactive({ goal: '', expectedOutput: '', status: 'TODO' })

const runDialog = ref(false)
const runDetail = ref<RunRow | null>(null)
const ledgerRuns = ref<RunLedgerRow[]>([])

const envOptions = ['dev', 'test', 'prod']

const workerConversations = computed(() =>
  conversations.value.filter((c) => c.kind !== 'SUPERVISOR' && !c.archived))

const activeRuns = computed(() => runs.value.filter(
  (run) => run.status === 'QUEUED' || run.status === 'RUNNING'))
const recentRuns = computed(() => runs.value
  .filter((run) => run.status !== 'QUEUED' && run.status !== 'RUNNING')
  .slice(0, 8))
const visibleRuns = computed(() => (runTab.value === 'active' ? activeRuns.value : recentRuns.value))

/** 跨引擎最近运行：流水线/修复/发布也会出现在这里（账本按项目聚合）。 */
const recentLedgerRuns = computed(() => ledgerRuns.value
  .filter((run) => run.state !== 'QUEUED' && run.state !== 'RUNNING')
  .sort((a, b) => b.updatedAt - a.updatedAt)
  .slice(0, 12))
const ledgerFailures = computed(() => ledgerRuns.value
  .filter((run) => run.state === 'FAILED').length)

const openTasks = computed(() => tasks.value
  .filter((t) => t.status === 'TODO' || t.status === 'RUNNING' || t.status === 'BLOCKED')
  .sort((a, b) => (a.priority || 0) - (b.priority || 0) || a.createdAt - b.createdAt))
const doneTasks = computed(() => tasks.value
  .filter((t) => t.status === 'DONE')
  .sort((a, b) => b.updatedAt - a.updatedAt))

const kpis = computed(() => ({
  conversations: workerConversations.value.length,
  activeRuns: activeRuns.value.length,
  openTasks: openTasks.value.length,
}))

const actionCards = [
  { title: '查看工作区状态', desc: '会话、运行与任务一屏概览', prompt: '工作区现在什么状态？' },
  { title: '处理失败运行', desc: '定位失败会话并重新派发', prompt: '列出运行中的会话和最近失败，给出处理建议' },
  { title: '新建修复会话', desc: '创建会话并跟踪到完成', prompt: '新建一个会话修复构建失败，并跟踪到完成' },
  { title: '登记待办任务', desc: '把问题沉淀成可跟踪任务', prompt: '汇总今天的运行，把待办登记成任务' },
]

/** 调度 Agent 的发送/停止都落到常驻会话上，避免视图重建时重建连接。 */
function sendChat(text: string): Promise<void> {
  return entry.value.chat.send(text)
}

function appendUserMessage(text: string): void {
  entry.value.chat.appendMessage({ id: `u-${Date.now()}`, role: 'user', text, at: Date.now() })
}

function stop(): void {
  entry.value.chat.stop()
}

function reset(): void {
  entry.value.chat.reset()
}

const showWelcome = computed(() => !streaming.value && projection.value.messages.length === 0)
const canSend = computed(() => input.value.trim().length > 0 && !streaming.value && !!supervisor.value)

let timer: ReturnType<typeof setInterval> | null = null
let reconcileTimer: ReturnType<typeof setTimeout> | null = null
/** 切工作区时旧项目的请求可能后到：只接受最后一次初始化/加载的结果。 */
let loadToken = 0
let initToken = 0
let mounted = false

const terminalRunStatuses = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

/** 事件驱动的状态更新：先就地打补丁，终态再触发一次 REST 对账。 */
const workspaceEvents = useWorkspaceEvents({
  url: () => `/api/v1/workspaces/${projectId.value}/events`,
  getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
  onEvent: (event) => applyWorkspaceEvent(event),
  onConnectedChange: (connected) => {
    if (connected) void loadAll()
  },
})
const liveConnected = workspaceEvents.connected

function applyWorkspaceEvent(event: WorkspaceEvent): void {
  const at = event.at || Date.now()
  if (event.runId) {
    const run = runs.value.find((row) => row.id === event.runId)
    if (run) {
      if (event.status) run.status = event.status as RunRow['status']
      if (event.status === 'RUNNING') run.startedAt = run.startedAt || at
      if (event.status && terminalRunStatuses.has(event.status)) {
        run.endedAt = at
        if (event.text) run.result = event.text
        scheduleReconcile()
      }
    } else if (event.status && (event.status === 'RUNNING' || terminalRunStatuses.has(event.status))) {
      // 调度 Agent 刚派发的运行还不在本地列表里，对账一次即可
      scheduleReconcile()
    }
  }
  if (event.conversationId && event.status) {
    const conversation = conversations.value.find((row) => row.id === event.conversationId)
    if (conversation) {
      conversation.status = event.status as ConversationRow['status']
      conversation.lastActivityAt = at
      if (event.text) conversation.summary = event.text.slice(0, 200)
    }
  }
}

/** 合并短时间内的多次事件，最多每 1.2 秒对账一次。 */
function scheduleReconcile(): void {
  if (reconcileTimer) return
  reconcileTimer = setTimeout(() => {
    reconcileTimer = null
    if (!document.hidden) void loadAll()
  }, 1200)
}

async function loadAll(): Promise<void> {
  const token = ++loadToken
  const id = projectId.value
  loading.value = true
  try {
    const [list, taskList, runList, ledgerList] = await Promise.all([
      listConversations(id, true),
      listTasks(id),
      listRuns(id, 30),
      // 账本不可用时不影响主视图（例如后端版本较旧）
      listRunLedger(id, 50).catch(() => [] as RunLedgerRow[]),
    ])
    if (token !== loadToken) return
    conversations.value = list
    tasks.value = taskList
    runs.value = runList
    ledgerRuns.value = ledgerList
    lastRefreshed.value = Date.now()
  } catch (e) {
    if (token !== loadToken) return
    ElMessage.error(e instanceof Error ? e.message : '工作区加载失败')
  } finally {
    if (token === loadToken) loading.value = false
  }
}

async function init(): Promise<void> {
  const token = ++initToken
  const id = projectId.value
  const target = getWorkspaceChat(id)
  input.value = ''
  try {
    projects.value = await listProjects()
    if (token !== initToken) return
    projectName.value = projects.value.find((p) => p.id === id)?.name ?? id
  } catch {
    if (token !== initToken) return
    projectName.value = id
  }
  appStore.projectId = id
  const local = appStore.sessions
    .filter((s) => s.projectId === id)
    .map((s) => ({ id: s.id, title: s.title, at: s.at }))
  if (local.length) {
    try {
      await syncConversations(id, local)
    } catch {
      // 同步失败不影响使用
    }
  }
  if (token !== initToken) return
  try {
    // 常驻会话已初始化过就直接复用：不 reset、不重新 hydrate，
    // 正在输出的流与已有时间线都保留
    await target.ensure()
  } catch (e) {
    if (token !== initToken) return
    ElMessage.error(e instanceof Error ? e.message : '工作区助手初始化失败')
  }
  if (token !== initToken) return
  await loadAll()
  if (token !== initToken) return
  await nextTick()
  // 回到工作区时直接定位到最新输出（包括切走期间仍在增长的回答）
  autoStick = true
  scrollToBottom(true)
}

function openConversation(row: ConversationRow): void {
  appStore.projectId = projectId.value
  appStore.env = row.env
  appStore.worktree = row.worktree ?? ''
  appStore.selectSession(row.id)
  appStore.touchSession(row.title)
  void router.push('/chat')
}

function openCreate(): void {
  createForm.title = `新会话 ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  createForm.env = appStore.env
  createForm.worktree = ''
  createForm.advanced = false
  createOpen.value = true
}

async function createNew(): Promise<void> {
  const title = createForm.title.trim()
  if (!title) return
  try {
    const created = await createConversation(projectId.value, {
      title,
      env: createForm.env,
      worktree: createForm.worktree.trim() || undefined,
    })
    createOpen.value = false
    await loadAll()
    openConversation(created)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新建会话失败')
  }
}

async function cancel(run: RunRow): Promise<void> {
  try {
    await cancelRun(run.id)
    runDialog.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  }
}

function openRunDetail(run: RunRow): void {
  runDetail.value = run
  runDialog.value = true
}

function openRunConversation(run: RunRow): void {
  const conversation = conversations.value.find((c) => c.id === run.conversationId)
  if (!conversation) {
    ElMessage.info('该运行所属的会话已不在当前工作区')
    return
  }
  runDialog.value = false
  openConversation(conversation)
}

async function setTaskStatus(task: TaskRow, status: string): Promise<void> {
  try {
    await updateTask(projectId.value, task.id, { status })
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新任务失败')
  }
}

function openTaskCreate(): void {
  editingTask.value = null
  taskForm.goal = ''
  taskForm.expectedOutput = ''
  taskForm.status = 'TODO'
  taskDialog.value = true
}

function openTaskEdit(task: TaskRow): void {
  editingTask.value = task
  taskForm.goal = task.goal
  taskForm.expectedOutput = task.expectedOutput
  taskForm.status = task.status
  taskDialog.value = true
}

async function saveTask(): Promise<void> {
  const goal = taskForm.goal.trim()
  if (!goal) return
  try {
    if (editingTask.value) {
      await updateTask(projectId.value, editingTask.value.id, {
        goal,
        expectedOutput: taskForm.expectedOutput.trim(),
        status: taskForm.status,
      })
    } else {
      await createTask(projectId.value, {
        goal,
        expectedOutput: taskForm.expectedOutput.trim() || undefined,
      })
    }
    taskDialog.value = false
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存任务失败')
  }
}

async function submit(): Promise<void> {
  const text = input.value.trim()
  if (!text || streaming.value || !supervisor.value) return
  // 连点发送 / 回车重复触发时不重复落同一条用户消息（后端也会重复收到）
  const last = projection.value.messages[projection.value.messages.length - 1]
  if (last?.role === 'user' && last.text === text && Date.now() - last.at < 2000) {
    input.value = ''
    return
  }
  input.value = ''
  appendUserMessage(text)
  autoStick = true
  await nextTick()
  scrollToBottom(true)
  await sendChat(text)
  // 发送期间可能已经切走：卸载后的视图不再刷新工作区数据
  if (!mounted) return
  await loadAll()
}

/** 中文输入法组合标记：组合期间回车不应触发发送（避免把半截拼音/标点发出去）。 */
const composing = ref(false)

function onEnter(event: KeyboardEvent): void {
  if (event.isComposing || composing.value || event.keyCode === 229) return
  if (!event.shiftKey) {
    event.preventDefault()
    void submit()
  }
}

/** 输入框随内容长高，最多 30vh 后内部滚动（与对话页一致）。 */
function resizeInput(): void {
  const el = composerRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, Math.round(window.innerHeight * 0.3))}px`
}

watch(input, () => void nextTick(resizeInput))

function usePrompt(text: string): void {
  input.value = text
  void nextTick(() => composerRef.value?.focus())
}

function onEscape(): void {
  if (streaming.value) stop()
}

/** 流式失败后重发上一条用户消息：不新增气泡，避免时间线出现重复提问。 */
function retry(): void {
  if (streaming.value) return
  const lastUser = [...projection.value.messages].reverse().find((m) => m.role === 'user')
  if (!lastUser) return
  void sendChat(lastUser.text)
}

/** 一键诊断：把本地快照拼进提示词，让调度 Agent 用只读工具核对后给结论。 */
function diagnose(): void {
  if (!supervisor.value) {
    ElMessage.info('工作区助手尚未就绪')
    return
  }
  if (streaming.value) {
    ElMessage.info('工作区助手正在处理，请稍候')
    return
  }
  const failed = recentRuns.value.filter((run) => run.status === 'FAILED').slice(0, 3)
  const lines = [
    '请对当前工作区做一次健康诊断：',
    `- 会话 ${workerConversations.value.length} 个，运行中 ${activeRuns.value.length} 个，待办 ${openTasks.value.length} 个`,
    failed.length
      ? `- 最近失败：${failed.map((run) => `${conversationTitle(run.conversationId)}（${run.error || '无错误信息'}）`).join('；')}`
      : '- 最近没有失败运行',
    '请用只读工具核对实际状态，指出最需要处理的 1-3 件事，并直接给出下一步动作。',
  ]
  input.value = lines.join('\n')
  void submit()
}

/** 失败/取消的运行重新派发到原会话。 */
async function retryRun(run: RunRow): Promise<void> {
  if (!run.conversationId) {
    ElMessage.info('该运行没有关联会话，无法重试')
    return
  }
  try {
    await sendToConversation(run.conversationId, run.goal || '继续上一次任务')
    runDialog.value = false
    ElMessage.success('已重新派发到该会话')
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重试失败')
  }
}

interface PaletteCommand {
  id: string
  title: string
  hint: string
  run: () => void
}

const paletteCommands = computed<PaletteCommand[]>(() => {
  const list: PaletteCommand[] = [
    { id: 'diagnose', title: '一键诊断工作区', hint: '健康检查', run: diagnose },
    { id: 'new', title: '新建会话', hint: '⌘N', run: openCreate },
    { id: 'refresh', title: '刷新工作区', hint: 'R', run: () => void loadAll() },
    { id: 'tasks', title: '切换到任务面板', hint: '面板', run: () => { sideTab.value = 'tasks' } },
    { id: 'runs', title: '切换到运行面板', hint: '面板', run: () => { sideTab.value = 'runs' } },
    ...workerConversations.value.slice(0, 8).map((row) => ({
      id: `conv-${row.id}`,
      title: `打开会话：${row.title}`,
      hint: envLabel(row.env),
      run: () => openConversation(row),
    })),
  ]
  const query = paletteQuery.value.trim().toLowerCase()
  if (!query) return list
  return list.filter((item) => `${item.title} ${item.hint}`.toLowerCase().includes(query))
})

function openPalette(): void {
  paletteQuery.value = ''
  paletteIndex.value = 0
  paletteOpen.value = true
  void nextTick(() => paletteInputRef.value?.focus())
}

function runPaletteCommand(command: PaletteCommand): void {
  paletteOpen.value = false
  command.run()
}

function onPaletteKeydown(event: KeyboardEvent): void {
  const list = paletteCommands.value
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    paletteIndex.value = list.length ? (paletteIndex.value + 1) % list.length : 0
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    paletteIndex.value = list.length ? (paletteIndex.value - 1 + list.length) % list.length : 0
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const command = list[paletteIndex.value]
    if (command) runPaletteCommand(command)
  } else if (event.key === 'Escape') {
    event.preventDefault()
    paletteOpen.value = false
  }
}

function onGlobalKeydown(event: KeyboardEvent): void {
  if (!(event.metaKey || event.ctrlKey)) return
  const key = event.key.toLowerCase()
  if (key === 'k') {
    event.preventDefault()
    if (paletteOpen.value) paletteOpen.value = false
    else openPalette()
  } else if (key === 'n' && !event.shiftKey) {
    event.preventDefault()
    openCreate()
  }
}

function onVisibility(): void {
  if (!document.hidden) void loadAll()
}

watch(paletteQuery, () => {
  paletteIndex.value = 0
})

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}

async function clearChat(): Promise<void> {
  if (!supervisor.value) return
  try {
    await ElMessageBox.confirm(
      '只删除本机保存的对话记录，服务端会话与运行记录仍保留。',
      '清空对话',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  clearHistory(supervisor.value.id)
  reset()
  ElMessage.success('已清空对话')
}

function statusLabel(status: string): string {
  return ({
    IDLE: '空闲', RUNNING: '运行中', NEEDS_CONFIRMATION: '待确认',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
    QUEUED: '排队中', TODO: '待办', BLOCKED: '阻塞', DONE: '完成',
  } as Record<string, string>)[status] ?? status
}

function timeAgo(ms: number): string {
  if (!ms) return ''
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return `${Math.floor(diff / 86_400_000)} 天前`
}

const kindLabels: Record<string, string> = {
  WORKSPACE: '会话',
  CHAT: '对话',
  BUGFIX: '修复',
  PIPELINE: '流水线',
  DEPLOY: '发布',
}

function kindLabel(kind: string): string {
  return kindLabels[kind] ?? kind
}

/** 跨引擎运行的跳转目标：会话类回到对话，其余统一落到流水线页。 */
function openLedgerRun(run: RunLedgerRow): void {
  if ((run.kind === 'WORKSPACE' || run.kind === 'CHAT') && run.subjectId) {
    appStore.projectId = projectId.value
    appStore.selectSession(run.subjectId)
    void router.push('/chat')
    return
  }
  void router.push('/pipeline')
}

function formatTime(ms: number): string {
  if (!ms) return '—'
  return new Date(ms).toLocaleString('zh-CN', { hour12: false })
}

function runDuration(run: RunRow): string {
  const start = run.startedAt || run.createdAt
  if (!start) return ''
  const end = run.endedAt || Date.now()
  const seconds = Math.max(0, Math.round((end - start) / 1000))
  if (seconds < 60) return `${seconds}s`
  return `${Math.floor(seconds / 60)}m${seconds % 60}s`
}

function conversationTitle(id: string): string {
  return conversations.value.find((c) => c.id === id)?.title ?? id.slice(0, 12)
}

function shortId(id: string): string {
  return id.length > 14 ? `${id.slice(0, 14)}…` : id
}

// 流式文本每个事件都会触发一次；合并到下一帧，避免每个 token 都强制布局
let scrollFrame = 0
/**
 * 是否跟随输出自动贴底：用户主动向上翻看时不再强行拽回底部，
 * 回到底部附近后自动恢复跟随。
 */
let autoStick = true

function onChatScroll(): void {
  const el = messageListRef.value
  if (!el) return
  autoStick = el.scrollHeight - el.scrollTop - el.clientHeight < 96
}

function scrollToBottom(force = false): void {
  if (!force && !autoStick) return
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    if (!force && !autoStick) return
    const el = messageListRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

watch(() => projection.value.messages.length, async () => {
  await Promise.resolve()
  scrollToBottom()
})

watch(() => projection.value.streamingTurn?.text, async () => {
  await Promise.resolve()
  scrollToBottom()
})

/**
 * 顶栏切换项目时 appStore.projectId 先变、路由还没变：
 * 这里把路由对齐，工作区页面才能真正切到新项目（之前只改了 store，页面完全不动）。
 */
watch(() => appStore.projectId, (id) => {
  if (!id || id === String(route.params.projectId ?? '')) return
  void router.replace({ name: 'workspace', params: { projectId: id } })
})

watch(projectId, () => {
  conversations.value = []
  tasks.value = []
  runs.value = []
  ledgerRuns.value = []
  workspaceEvents.start()
  void init()
})

onMounted(async () => {
  mounted = true
  document.addEventListener('keydown', onGlobalKeydown)
  document.addEventListener('visibilitychange', onVisibility)
  await init()
  workspaceEvents.start()
  // 事件流断开时才启用低频兜底轮询，避免大规模工作区反复全量拉取
  timer = setInterval(() => {
    if (!document.hidden && !workspaceEvents.connected.value) void loadAll()
  }, 10_000)
})

onUnmounted(() => {
  mounted = false
  if (timer) clearInterval(timer)
  if (reconcileTimer) clearTimeout(reconcileTimer)
  if (scrollFrame) cancelAnimationFrame(scrollFrame)
  document.removeEventListener('keydown', onGlobalKeydown)
  document.removeEventListener('visibilitychange', onVisibility)
  workspaceEvents.stop()
  // 注意：不调用 stop()。调度会话是跨页常驻的，离开页面时读流继续；
  // 用户要中断只能显式点「停止」或按 Esc。
})
</script>

<template>
  <div class="workspace">
    <PageHeader title="工作区" context context-env>
      <template #actions>
        <button type="button" class="icon-btn" title="命令面板（Ctrl/⌘ + K）" @click="openPalette">
          <el-icon><Search /></el-icon>
        </button>
        <button type="button" class="btn ghost" title="一键诊断工作区" @click="diagnose">
          <el-icon><Aim /></el-icon>诊断
        </button>
        <button type="button" class="icon-btn" title="刷新" :disabled="loading" @click="loadAll">
          <el-icon><Refresh /></el-icon>
        </button>
        <button type="button" class="btn primary" @click="openCreate">
          <el-icon><Plus /></el-icon>新建会话
        </button>
      </template>
    </PageHeader>

    <div class="ws-bar">
      <div class="ws-bar-left">
        <h2>工作区助手</h2>
        <span class="tag">只读 · 负责编排</span>
        <span class="live" :class="{ on: liveConnected }">
          <i class="live-dot"></i>{{ liveConnected ? '实时' : '轮询兜底' }}
        </span>
      </div>
      <div class="ws-bar-right">
        <span class="ws-kpis">
          {{ kpis.conversations }} 会话 · {{ kpis.activeRuns }} 运行中 · {{ kpis.openTasks }} 待办
        </span>
        <span v-if="lastRefreshed" class="ws-updated">更新于 {{ timeAgo(lastRefreshed) }}</span>
        <button
          type="button" class="text-btn"
          :disabled="streaming || !projection.messages.length" @click="clearChat"
        >清空</button>
      </div>
    </div>

    <div class="ws-body">
      <!-- 中：工作区助手（与对话页共用时间线 / composer 视觉） -->
      <section class="chat-pane">
        <div ref="messageListRef" class="chat-scroll" @scroll.passive="onChatScroll">
          <div v-if="showWelcome" class="welcome">
            <span class="welcome-icon"><el-icon><ChatDotRound /></el-icon></span>
            <h2 class="welcome-title">要做什么？</h2>
            <p class="welcome-desc">
              {{ projectName }} · 可以直接问工作区状态，也可以让它创建会话、派发任务并跟踪到完成。
            </p>
            <div class="welcome-suggestions">
              <button
                v-for="a in actionCards" :key="a.title" type="button"
                class="suggestion" @click="usePrompt(a.prompt)"
              >
                <span class="suggestion-main">
                  <strong>{{ a.title }}</strong>
                  <small>{{ a.desc }}</small>
                </span>
                <el-icon><CaretRight /></el-icon>
              </button>
            </div>
            <p class="welcome-note">生产写操作需要你在确认卡上确认。</p>
          </div>

          <ConversationTimeline
            v-if="!showWelcome"
            :projection="projection"
            :streaming="streaming"
            @copy="copyText"
          />
          <div v-if="error" class="stream-error">
            <span class="stream-error-text">{{ error }}</span>
            <button type="button" class="text-btn" :disabled="streaming" @click="retry">重试</button>
          </div>
        </div>

        <div class="composer-wrap">
          <div class="composer">
            <textarea
              ref="composerRef" v-model="input" rows="1" class="composer-input"
              :placeholder="supervisor ? '直接问工作区状态，或描述要做的事' : '正在连接工作区助手…'"
              @keydown.enter="onEnter"
              @keydown.esc="onEscape"
              @compositionstart="composing = true"
              @compositionend="composing = false"
            />
            <div class="composer-bar">
              <div class="bar-left">
                <span class="bar-scope mono">
                  工作区 · {{ envLabel(appStore.env) }}<template v-if="supervisor"> · {{ shortId(supervisor.id) }}</template>
                </span>
              </div>
              <div class="bar-right">
                <span v-if="streaming || elapsedMs" class="metric mono">
                  {{ (elapsedMs / 1000).toFixed(1) }}s · {{ tokens }} ev
                </span>
                <button v-if="streaming" type="button" class="send-btn stop" @click="stop">
                  停止
                </button>
                <button
                  v-else type="button" class="send-btn" :class="{ ready: canSend }"
                  :disabled="!canSend" title="发送" @click="submit"
                >
                  <el-icon><Promotion /></el-icon>
                </button>
              </div>
            </div>
          </div>
          <p class="composer-hint">
            {{ streaming ? 'Esc 停止' : 'Enter 发送 · Shift + Enter 换行 · Ctrl/⌘ + K 命令面板' }}
          </p>
        </div>
      </section>

      <!-- 右：任务 / 运行 -->
      <aside class="side-col">
        <div class="side-head">
          <div class="seg inline">
            <button type="button" :class="{ on: sideTab === 'tasks' }" @click="sideTab = 'tasks'">
              任务<span v-if="openTasks.length" class="seg-count">{{ openTasks.length }}</span>
            </button>
            <button type="button" :class="{ on: sideTab === 'runs' }" @click="sideTab = 'runs'">
              运行<span v-if="activeRuns.length" class="seg-count">{{ activeRuns.length }}</span>
            </button>
          </div>
          <button
            v-if="sideTab === 'tasks'" type="button" class="icon-btn sm"
            title="新建任务" @click="openTaskCreate"
          >
            <el-icon><Plus /></el-icon>
          </button>
        </div>

        <div class="side-body">
          <template v-if="sideTab === 'tasks'">
            <div
              v-for="task in openTasks" :key="task.id"
              class="list-item" @click="openTaskEdit(task)"
            >
              <button type="button" class="check" title="标记完成" @click.stop="setTaskStatus(task, 'DONE')">
                <el-icon><Check /></el-icon>
              </button>
              <div class="item-main">
                <span class="item-title">{{ task.goal }}</span>
                <span class="item-meta">
                  <em v-if="task.priority" class="prio">P{{ task.priority }}</em>
                  <span v-if="task.conversationId">{{ conversationTitle(task.conversationId) }}</span>
                  <span v-if="task.expectedOutput">{{ task.expectedOutput }}</span>
                </span>
              </div>
              <span class="item-status" :data-s="task.status">{{ statusLabel(task.status) }}</span>
            </div>
            <p v-if="!openTasks.length" class="empty">暂无待办，点右上角 + 登记</p>

            <button
              v-if="doneTasks.length" type="button" class="done-toggle"
              @click="showDoneTasks = !showDoneTasks"
            >{{ showDoneTasks ? '收起已完成' : `已完成 ${doneTasks.length}` }}</button>
            <template v-if="showDoneTasks">
              <div
                v-for="task in doneTasks" :key="task.id"
                class="list-item done" @click="openTaskEdit(task)"
              >
                <span class="done-mark">✓</span>
                <div class="item-main">
                  <span class="item-title">{{ task.goal }}</span>
                  <span class="item-meta">{{ timeAgo(task.updatedAt) }}</span>
                </div>
              </div>
            </template>
          </template>

          <template v-else>
            <div class="seg inline run-seg">
              <button type="button" :class="{ on: runTab === 'active' }" @click="runTab = 'active'">
                进行中<span v-if="activeRuns.length" class="seg-count">{{ activeRuns.length }}</span>
              </button>
              <button type="button" :class="{ on: runTab === 'recent' }" @click="runTab = 'recent'">最近</button>
            </div>
            <div
              v-for="run in visibleRuns" :key="run.id"
              class="list-item" @click="openRunDetail(run)"
            >
              <i class="run-dot" :data-s="run.status"></i>
              <div class="item-main">
                <span class="item-title">{{ run.goal }}</span>
                <span class="item-meta">
                  {{ conversationTitle(run.conversationId) }} · {{ runDuration(run) }}
                  <template v-if="run.error"> · {{ run.error }}</template>
                </span>
              </div>
              <span class="item-status" :data-s="run.status">{{ statusLabel(run.status) }}</span>
              <button
                v-if="run.status === 'FAILED' || run.status === 'CANCELLED'"
                type="button" class="text-btn retry-btn" title="重新派发"
                @click.stop="retryRun(run)"
              >重试</button>
            </div>
            <p v-if="!visibleRuns.length" class="empty">
              {{ runTab === 'active' ? '当前没有运行中的任务' : '还没有已结束的运行' }}
            </p>

            <!-- 跨引擎运行：流水线/修复/发布也进入同一份账本 -->
            <template v-if="runTab === 'recent'">
              <p class="group-label">
                全部引擎
                <span v-if="ledgerFailures" class="fail-badge">{{ ledgerFailures }} 个失败</span>
              </p>
              <div
                v-for="run in recentLedgerRuns" :key="`ledger-${run.id}`"
                class="list-item" @click="openLedgerRun(run)"
              >
                <i class="run-dot" :data-s="run.state"></i>
                <div class="item-main">
                  <span class="item-title">{{ kindLabel(run.kind) }} · {{ run.summary || run.id }}</span>
                  <span class="item-meta">
                    {{ timeAgo(run.updatedAt) }}
                    <template v-if="run.error"> · {{ run.error }}</template>
                  </span>
                </div>
                <span class="item-status" :data-s="run.state">{{ statusLabel(run.state) }}</span>
              </div>
              <p v-if="!recentLedgerRuns.length" class="empty">还没有跨引擎运行记录</p>
            </template>
          </template>
        </div>
      </aside>
    </div>

    <!-- 新建会话 -->
    <el-dialog v-model="createOpen" title="新建会话" width="420px" align-center>
      <div class="form">
        <label>
          <span>标题</span>
          <el-input v-model="createForm.title" placeholder="例如：修复登录页报错" maxlength="60" />
        </label>
        <label>
          <span>环境</span>
          <el-select v-model="createForm.env">
            <el-option v-for="e in envOptions" :key="e" :label="envLabel(e)" :value="e" />
          </el-select>
        </label>
        <label v-if="createForm.advanced">
          <span>工作树</span>
          <el-input v-model="createForm.worktree" placeholder="留空使用主仓库" />
        </label>
        <button
          v-else type="button" class="advanced-toggle"
          @click="createForm.advanced = true"
        >高级选项：指定工作树</button>
      </div>
      <template #footer>
        <div class="dialog-actions">
          <button type="button" class="btn ghost" @click="createOpen = false">取消</button>
          <button
            type="button" class="btn primary"
            :disabled="!createForm.title.trim()" @click="createNew"
          >创建并打开</button>
        </div>
      </template>
    </el-dialog>

    <!-- 任务 -->
    <el-dialog
      v-model="taskDialog" :title="editingTask ? '编辑任务' : '新建任务'"
      width="460px" align-center
    >
      <div class="form">
        <label>
          <span>目标</span>
          <el-input v-model="taskForm.goal" placeholder="要完成什么" />
        </label>
        <label>
          <span>期望产出</span>
          <el-input v-model="taskForm.expectedOutput" placeholder="可选的验收标准" />
        </label>
        <label v-if="editingTask">
          <span>状态</span>
          <el-select v-model="taskForm.status">
            <el-option label="待办" value="TODO" />
            <el-option label="进行中" value="RUNNING" />
            <el-option label="阻塞" value="BLOCKED" />
            <el-option label="完成" value="DONE" />
          </el-select>
        </label>
      </div>
      <template #footer>
        <div class="dialog-actions">
          <button type="button" class="btn ghost" @click="taskDialog = false">取消</button>
          <button
            type="button" class="btn primary"
            :disabled="!taskForm.goal.trim()" @click="saveTask"
          >保存</button>
        </div>
      </template>
    </el-dialog>

    <!-- 运行详情 -->
    <el-dialog v-model="runDialog" title="运行详情" width="520px" align-center>
      <div v-if="runDetail" class="detail">
        <div class="detail-row"><span>目标</span><b>{{ runDetail.goal }}</b></div>
        <div class="detail-row"><span>状态</span><b>{{ statusLabel(runDetail.status) }}</b></div>
        <div class="detail-row"><span>会话</span><b>{{ conversationTitle(runDetail.conversationId) }}</b></div>
        <div class="detail-row"><span>耗时</span><b>{{ runDuration(runDetail) }}</b></div>
        <div class="detail-row"><span>创建</span><b>{{ formatTime(runDetail.createdAt) }}</b></div>
        <div class="detail-row"><span>结束</span><b>{{ formatTime(runDetail.endedAt) }}</b></div>
        <div v-if="runDetail.error" class="detail-row">
          <span>错误</span><b class="danger">{{ runDetail.error }}</b>
        </div>
        <div v-if="runDetail.result" class="detail-row">
          <span>结果</span><b>{{ runDetail.result }}</b>
        </div>
      </div>
      <template #footer>
        <div class="dialog-actions">
          <button
            v-if="runDetail && (runDetail.status === 'QUEUED' || runDetail.status === 'RUNNING')"
            type="button" class="btn ghost" @click="cancel(runDetail)"
          >取消运行</button>
          <button
            v-if="runDetail && (runDetail.status === 'FAILED' || runDetail.status === 'CANCELLED')"
            type="button" class="btn ghost" @click="retryRun(runDetail)"
          >重试</button>
          <button type="button" class="btn ghost" @click="runDialog = false">关闭</button>
          <button
            v-if="runDetail" type="button" class="btn primary"
            @click="openRunConversation(runDetail)"
          >打开会话</button>
        </div>
      </template>
    </el-dialog>

    <!-- 命令面板 -->
    <div v-if="paletteOpen" class="palette-mask" @click.self="paletteOpen = false">
      <div class="palette">
        <input
          ref="paletteInputRef" v-model="paletteQuery" class="palette-input"
          placeholder="搜索动作或会话，↑↓ 选择，Enter 执行，Esc 关闭"
          @keydown="onPaletteKeydown"
        />
        <div class="palette-list">
          <button
            v-for="(command, index) in paletteCommands" :key="command.id"
            type="button" class="palette-row" :class="{ on: index === paletteIndex }"
            @mouseenter="paletteIndex = index" @click="runPaletteCommand(command)"
          >
            <span class="palette-title">{{ command.title }}</span>
            <span class="palette-hint">{{ command.hint }}<template v-if="index === paletteIndex"> · ↵</template></span>
          </button>
          <p v-if="!paletteCommands.length" class="empty">没有匹配的动作</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workspace {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
}
.mono { font-variant-numeric: tabular-nums; }
.muted { color: var(--ink-faint); }

/* 助手信息行：标题 + 只读说明 + 实时状态，弱于 Header 但比正文更轻 */
.ws-bar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 34px;
  padding: 0 20px 6px;
}
.ws-bar-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.ws-bar-left h2 {
  margin: 0;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
}
.tag {
  padding: 1px 7px;
  font-size: 10px;
  color: var(--ink-muted);
  white-space: nowrap;
  background: var(--surface-sunken);
  border-radius: 999px;
}
.ws-bar-right {
  display: flex;
  flex: none;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--ink-faint);
  white-space: nowrap;
}
.ws-kpis { color: var(--ink-muted); }

/* 正文：左对话 + 右任务栏，与对话页共用白纸画布 */
.ws-body {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
}
.chat-pane {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

/* 通用控件 */
.btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  border: 1px solid transparent;
  border-radius: 8px;
}
.btn.primary { color: #fff; background: var(--accent); }
.btn.primary:hover:not(:disabled) { filter: brightness(1.05); }
.btn.ghost { color: var(--ink-soft); background: #fff; border-color: var(--line-soft); }
.btn.ghost:hover:not(:disabled) { background: var(--surface-hover); }
.btn:disabled { cursor: not-allowed; opacity: 0.45; }
.icon-btn {
  display: grid;
  width: 30px;
  height: 30px;
  color: var(--ink-muted);
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: 8px;
  place-items: center;
}
.icon-btn:hover:not(:disabled) { color: var(--ink); background: var(--surface-hover); }
.icon-btn:disabled { cursor: not-allowed; opacity: 0.45; }
.icon-btn.sm { width: 24px; height: 24px; border: 0; background: none; }
.text-btn {
  padding: 0;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
}
.text-btn:hover:not(:disabled) { color: var(--accent-ink); }
.text-btn:disabled { cursor: not-allowed; opacity: 0.45; }

/* 右栏：任务 / 运行，发丝线与对话区分开 */
.side-col {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 296px;
  min-height: 0;
  background: var(--surface-raised);
  border-left: 1px solid var(--line-soft);
}
.side-head {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px 8px;
  border-bottom: 1px solid var(--line-soft);
}

/* 分段筛选 */
.seg { display: flex; flex: none; gap: 2px; }
.seg button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.seg button:hover { color: var(--ink); }
.seg button.on { color: var(--ink); background: var(--surface-sunken); font-weight: 500; }
.seg-count { font-size: 10px; opacity: 0.7; }

/* 列表行 */
.list-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  cursor: pointer;
  border-radius: 8px;
}
.list-item:hover { background: var(--surface-hover); }
.item-main { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 2px; }
.item-title {
  display: flex;
  gap: 3px;
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.item-meta {
  display: flex;
  gap: 5px;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.item-meta .prio {
  padding: 0 4px;
  font-style: normal;
  background: var(--surface-sunken);
  border-radius: 3px;
}
.empty {
  padding: 18px 10px;
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
  text-align: center;
}

/* 对话区：与对话页同一套白纸画布 + 居中阅读列 */
.chat-scroll {
  flex: 1 1 0;
  min-height: 0;
  padding: 0 24px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.welcome {
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  max-width: 56rem;
  min-height: 100%;
  padding: 24px 0;
  margin: 0 auto;
}
.welcome-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  font-size: 17px;
  color: var(--ink-soft);
  background: var(--surface-sunken);
  border-radius: var(--radius-md);
}
.welcome-title {
  margin: 14px 0 6px;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.welcome-desc {
  max-width: 34rem;
  margin: 0;
  font-size: 13px;
  line-height: 1.75;
  color: var(--ink-muted);
}
.welcome-suggestions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 22px;
}
.suggestion {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 12px 14px;
  font-family: inherit;
  font-size: 13px;
  color: var(--ink-soft);
  text-align: left;
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  transition: border-color var(--dur-fast) ease, box-shadow var(--dur-fast) ease;
}
.suggestion:hover {
  color: var(--ink);
  border-color: var(--line);
  box-shadow: var(--shadow-1);
}
.suggestion-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.suggestion-main strong {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}
.suggestion-main small {
  font-size: 12px;
  color: var(--ink-muted);
}
.suggestion .el-icon {
  flex: none;
  font-size: 12px;
  color: var(--ink-faint);
}
.welcome-note {
  margin-top: 16px;
  font-size: 11px;
  color: var(--ink-faint);
}
.stream-error {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 56rem;
  padding: 8px 12px;
  margin: 10px auto;
  font-size: 12px;
  color: var(--danger);
  background: var(--danger-soft);
  border-left: 2px solid var(--danger);
  border-radius: var(--radius-sm);
}
.stream-error-text {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}
.stream-error .text-btn {
  flex: none;
  font-size: 12px;
  color: var(--danger);
}

/* 输入区：白底浮起的 composer，底部一行工具条（复用对话页视觉） */
.composer-wrap {
  flex: none;
  padding: 0 24px 14px;
}
.composer {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-width: 56rem;
  padding: 12px 12px 6px 14px;
  margin: 0 auto;
  background: var(--surface-raised);
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-lg);
  box-shadow: 0 6px 20px rgb(16 24 40 / 6%);
  transition: border-color var(--dur-fast) ease, box-shadow var(--dur-fast) ease;
}
.composer:focus-within {
  border-color: rgb(77 130 247 / 52%);
  box-shadow: 0 0 0 3px var(--accent-soft), 0 8px 24px rgb(16 24 40 / 9%);
}
.composer-input {
  width: 100%;
  min-height: 46px;
  padding: 2px;
  overflow-y: auto;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink);
  resize: none;
  background: transparent;
  border: 0;
  outline: none;
}
.composer-input::placeholder {
  color: var(--ink-faint);
}
.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.bar-left,
.bar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.bar-scope {
  overflow: hidden;
  max-width: 190px;
  padding: 0 4px;
  font-size: 11px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.metric {
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.send-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-muted);
  cursor: not-allowed;
  background: var(--surface-hover);
  border: 0;
  border-radius: var(--radius-md);
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease,
    transform var(--dur-fast) ease;
}
.send-btn.ready {
  color: #fff;
  cursor: pointer;
  background: var(--accent);
}
.send-btn.ready:hover {
  background: var(--accent-hover);
  transform: translateY(-1px);
}
.send-btn.ready:active {
  transform: translateY(0);
}
.send-btn.stop {
  width: auto;
  padding: 0 12px;
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--surface-active);
}
.send-btn.stop:hover {
  color: var(--ink);
}
.composer-hint {
  max-width: 54rem;
  margin: 8px auto 0;
  font-size: 11px;
  color: var(--ink-faint);
  text-align: center;
}

/* 右栏 */
.side-body { flex: 1; min-height: 0; padding: 6px 8px 10px; overflow-y: auto; }
.list-item { align-items: flex-start; }
.check {
  display: grid;
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 2px;
  padding: 0;
  font-size: 9px;
  color: transparent;
  cursor: pointer;
  background: none;
  border: 1.5px solid #cbd5e1;
  border-radius: 50%;
  place-items: center;
}
.check:hover { color: #10b981; border-color: #10b981; }
.item-status { flex: none; padding-top: 1px; font-size: 10px; color: var(--ink-faint); white-space: nowrap; }
.item-status[data-s='RUNNING'] { color: #b45309; }
.item-status[data-s='DONE'] { color: #047857; }
.item-status[data-s='FAILED'] { color: #b91c1c; }
.list-item.done { opacity: 0.66; }
.done-mark { flex: none; width: 16px; font-size: 11px; color: #10b981; text-align: center; }
.done-toggle {
  display: block;
  width: 100%;
  padding: 8px;
  margin-top: 4px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-faint);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-top: 1px dashed var(--line-soft);
}
.done-toggle:hover { color: var(--accent-ink); }
.run-seg { padding: 4px 8px 8px; }
.run-dot { flex: none; width: 6px; height: 6px; margin-top: 6px; background: #cbd5e1; border-radius: 50%; }
.run-dot[data-s='QUEUED'] { background: #94a3b8; }
.run-dot[data-s='RUNNING'] { background: #f59e0b; }
.run-dot[data-s='SUCCEEDED'] { background: #10b981; }
.run-dot[data-s='FAILED'] { background: #ef4444; }
.run-dot[data-s='CANCELLED'] { background: #cbd5e1; }

/* 实时状态 */
.live { display: inline-flex; align-items: center; gap: 4px; }
.live-dot { width: 6px; height: 6px; background: #cbd5e1; border-radius: 50%; }
.live.on .live-dot { background: #10b981; box-shadow: 0 0 0 3px rgb(16 185 129 / 12%); }
.live.on { color: #047857; }

.retry-btn { flex: none; font-size: 11px; }
.group-label {
  display: flex; gap: 6px; align-items: center; margin: 14px 2px 6px;
  font-size: 11px; color: var(--ink-faint);
}
.fail-badge {
  padding: 1px 6px; border-radius: 999px;
  background: rgb(185 28 28 / 10%); color: #b91c1c; font-size: 10px;
}

/* 命令面板 */
.palette-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 12vh;
  background: rgb(15 23 42 / 32%);
}
.palette {
  width: min(560px, calc(100vw - 32px));
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: 12px;
  box-shadow: 0 24px 60px rgb(15 23 42 / 22%);
}
.palette-input {
  width: 100%;
  padding: 14px 16px;
  font-family: inherit;
  font-size: 14px;
  color: var(--ink);
  background: none;
  border: 0;
  border-bottom: 1px solid var(--line-soft);
  outline: none;
}
.palette-list { max-height: 320px; padding: 6px; overflow-y: auto; }
.palette-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 9px 10px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 8px;
}
.palette-row.on { background: var(--accent-soft); }
.palette-hint { font-size: 11px; color: var(--ink-faint); }

/* 弹窗 */
.form { display: flex; flex-direction: column; gap: 12px; }
.form label { display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--ink-muted); }
.advanced-toggle {
  align-self: flex-start; padding: 0; font-family: inherit; font-size: 12px;
  color: var(--ink-muted); cursor: pointer; background: none; border: 0;
}
.advanced-toggle:hover { color: var(--accent-ink); }
.form :deep(.el-select) { width: 100%; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
.detail { display: flex; flex-direction: column; gap: 10px; font-size: 12.5px; }
.detail-row { display: flex; gap: 12px; line-height: 1.6; }
.detail-row > span { flex: none; width: 48px; color: var(--ink-faint); }
.detail-row > b { font-weight: 500; color: var(--ink); word-break: break-word; }
.detail-row .danger { color: var(--danger); }

@media (max-width: 1180px) {
  .side-col { display: none; }
}
@media (max-width: 720px) {
  .ws-bar { padding: 0 14px 6px; }
  .ws-bar-right { gap: 8px; }
  .ws-kpis,
  .ws-updated { display: none; }
  .chat-scroll,
  .composer-wrap {
    padding-right: 14px;
    padding-left: 14px;
  }
  .composer-wrap {
    padding-bottom: calc(10px + env(safe-area-inset-bottom));
  }
  .metric { display: none; }
}
@media (max-width: 480px) {
  .bar-scope { display: none; }
  .ws-bar-left .tag { display: none; }
}
</style>
