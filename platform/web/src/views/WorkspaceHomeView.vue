<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, Refresh, VideoPause, Search, Plus, Check, MoreFilled, Aim } from '@element-plus/icons-vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import { listProjects, type ProjectRow } from '../api/project'
import { useAgentChat } from '../composables/useAgentChat'
import { useWorkspaceEvents, type WorkspaceEvent } from '../composables/useWorkspaceEvents'
import { clearHistory, loadHistory, saveHistory } from '../state/sessionHistory'
import type { ChatMessage } from '../types'
import {
  cancelRun, createConversation, createTask, getSupervisor, listConversations, listRunLedger,
  listRuns, listTasks, sendToConversation, syncConversations, updateConversation, updateTask,
  type ConversationRow, type RunLedgerRow, type RunRow, type TaskRow,
} from '../api/workspace'
import { envLabel } from '../utils/pipelineLabels'

/**
 * 工作区首页：左会话、中调度 Agent、右任务/运行。
 *
 * 工作区 = 项目；调度 Agent 只做编排（创建/派发/跟踪/汇总），没有任何写工具，
 * 真正执行落到子会话，生产写操作仍由人工确认。
 */
const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId ?? appStore.projectId))
const projectName = ref('')
const projects = ref<ProjectRow[]>([])
const initial = computed(() => (projectName.value || projectId.value).slice(0, 1).toUpperCase())

const conversations = ref<ConversationRow[]>([])
const supervisor = ref<ConversationRow | null>(null)
const tasks = ref<TaskRow[]>([])
const runs = ref<RunRow[]>([])
const input = ref('')
const loading = ref(false)
const lastRefreshed = ref(0)
const messageListRef = ref<HTMLElement | null>(null)
const composerRef = ref<HTMLTextAreaElement | null>(null)

type ConvFilter = 'ALL' | 'ACTIVE' | 'FAILED' | 'CONFIRM'
const convSearch = ref('')
const convFilter = ref<ConvFilter>('ALL')
const showArchived = ref(false)

const sideTab = ref<'tasks' | 'runs'>('tasks')
const runTab = ref<'active' | 'recent'>('active')
const showDoneTasks = ref(false)
const toolsOpen = ref(false)

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
const filters = [
  { key: 'ALL', label: '全部' },
  { key: 'ACTIVE', label: '活跃' },
  { key: 'FAILED', label: '失败' },
  { key: 'CONFIRM', label: '待确认' },
] as const

const allConversations = computed(() =>
  conversations.value.filter((c) => c.kind !== 'SUPERVISOR'))
const workerConversations = computed(() => allConversations.value.filter((c) => !c.archived))
const archivedConversations = computed(() => allConversations.value.filter((c) => c.archived))

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

const filterCounts = computed(() => ({
  ALL: workerConversations.value.length,
  ACTIVE: workerConversations.value.filter((c) => c.status === 'RUNNING').length,
  FAILED: workerConversations.value.filter((c) => c.status === 'FAILED').length,
  CONFIRM: workerConversations.value.filter((c) => c.status === 'NEEDS_CONFIRMATION').length,
}))

const filteredConversations = computed(() => {
  const query = convSearch.value.trim().toLowerCase()
  let list = showArchived.value ? archivedConversations.value : workerConversations.value
  if (query) {
    list = list.filter((c) =>
      c.title.toLowerCase().includes(query)
      || (c.summary ?? '').toLowerCase().includes(query)
      || c.id.toLowerCase().includes(query))
  }
  if (!showArchived.value && convFilter.value !== 'ALL') {
    const wanted = convFilter.value === 'ACTIVE'
      ? 'RUNNING'
      : convFilter.value === 'FAILED' ? 'FAILED' : 'NEEDS_CONFIRMATION'
    list = list.filter((c) => c.status === wanted)
  }
  return [...list].sort((a, b) =>
    Number(b.pinned) - Number(a.pinned) || b.lastActivityAt - a.lastActivityAt)
})

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

const chat = useAgentChat({
  url: () => `/api/v1/workspaces/${projectId.value}/super/stream`,
  buildRequest: () => ({ sessionId: supervisor.value?.id, env: appStore.env }),
  getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
})
const { projection, streaming, elapsedMs, error, send: sendChat, stop, hydrate, reset } = chat

const timeline = computed<ChatMessage[]>(() => {
  const list = [...projection.value.messages]
  if (projection.value.streamingTurn) {
    list.push({
      id: 'streaming',
      role: 'assistant',
      text: projection.value.streamingTurn.text || '…',
      at: Date.now(),
    })
  }
  return list
})

const recentTools = computed(() => Object.values(projection.value.tools ?? {}).slice(-6).reverse())
const toolLabels: Record<string, string> = {
  workspace_snapshot: '工作区快照',
  conversation_list: '会话列表',
  conversation_search: '搜索会话',
  conversation_create: '新建会话',
  conversation_rename: '重命名会话',
  conversation_archive: '归档会话',
  conversation_pin: '置顶会话',
  conversation_send: '派发消息',
  conversation_status: '查询运行',
  conversation_wait: '等待运行',
  conversation_cancel: '取消运行',
  conversation_summarize: '汇总会话',
  task_create: '新建任务',
  task_update: '更新任务',
  task_complete: '完成任务',
  task_list: '任务列表',
}
function toolLabel(name: string): string {
  return toolLabels[name] ?? name
}

let timer: ReturnType<typeof setInterval> | null = null
let reconcileTimer: ReturnType<typeof setTimeout> | null = null

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
  loading.value = true
  try {
    const [list, taskList, runList, ledgerList] = await Promise.all([
      listConversations(projectId.value, true),
      listTasks(projectId.value),
      listRuns(projectId.value, 30),
      // 账本不可用时不影响主视图（例如后端版本较旧）
      listRunLedger(projectId.value, 50).catch(() => [] as RunLedgerRow[]),
    ])
    conversations.value = list
    tasks.value = taskList
    runs.value = runList
    ledgerRuns.value = ledgerList
    lastRefreshed.value = Date.now()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '工作区加载失败')
  } finally {
    loading.value = false
  }
}

async function init(): Promise<void> {
  reset()
  input.value = ''
  convSearch.value = ''
  convFilter.value = 'ALL'
  showArchived.value = false
  try {
    projects.value = await listProjects()
    projectName.value = projects.value.find((p) => p.id === projectId.value)?.name ?? projectId.value
  } catch {
    projectName.value = projectId.value
  }
  appStore.projectId = projectId.value
  const local = appStore.sessions
    .filter((s) => s.projectId === projectId.value)
    .map((s) => ({ id: s.id, title: s.title, at: s.at }))
  if (local.length) {
    try {
      await syncConversations(projectId.value, local)
    } catch {
      // 同步失败不影响使用
    }
  }
  try {
    const sup = await getSupervisor(projectId.value)
    supervisor.value = sup
    const history = loadHistory(sup.id)
    if (history) hydrate(history.messages, history.tools)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '工作区助手初始化失败')
  }
  await loadAll()
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

async function renameConversation(row: ConversationRow): Promise<void> {
  try {
    const result = await ElMessageBox.prompt('新的会话标题', '重命名会话', {
      inputValue: row.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (value) => (String(value ?? '').trim() ? true : '标题不能为空'),
    })
    const title = String(result.value ?? '').trim()
    if (!title) return
    await updateConversation(row.id, { title })
    await loadAll()
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message)
  }
}

async function togglePin(row: ConversationRow): Promise<void> {
  try {
    await updateConversation(row.id, { pinned: !row.pinned })
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function archiveConversation(row: ConversationRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '归档后会话从列表隐藏，历史记录保留，可在「已归档」中恢复。',
      '归档会话',
      { type: 'warning', confirmButtonText: '归档', cancelButtonText: '取消' },
    )
    await updateConversation(row.id, { archived: true })
    await loadAll()
  } catch (e) {
    if (e instanceof Error) ElMessage.error(e.message)
  }
}

async function restoreConversation(row: ConversationRow): Promise<void> {
  try {
    await updateConversation(row.id, { archived: false })
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '恢复失败')
  }
}

function onConvCommand(command: string, row: ConversationRow): void {
  if (command === 'rename') void renameConversation(row)
  if (command === 'pin') void togglePin(row)
  if (command === 'archive') void archiveConversation(row)
}

function toggleArchived(): void {
  showArchived.value = !showArchived.value
  convSearch.value = ''
  convFilter.value = 'ALL'
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
  input.value = ''
  await sendChat(text)
  await loadAll()
}

function onEnter(event: KeyboardEvent): void {
  if (event.isComposing) return
  if (!event.shiftKey) {
    event.preventDefault()
    void submit()
  }
}

function usePrompt(text: string): void {
  input.value = text
  void nextTick(() => composerRef.value?.focus())
}

function onEscape(): void {
  if (streaming.value) stop()
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

watch(() => [projection.value.messages, projection.value.tools], () => {
  if (supervisor.value) saveHistory(supervisor.value.id, projection.value.messages, projection.value.tools)
}, { deep: true })

function scrollToBottom(): void {
  const el = messageListRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(() => projection.value.messages.length, async () => {
  await Promise.resolve()
  scrollToBottom()
})

watch(() => projection.value.streamingTurn?.text, async () => {
  await Promise.resolve()
  scrollToBottom()
})

watch(projectId, () => {
  supervisor.value = null
  conversations.value = []
  tasks.value = []
  runs.value = []
  workspaceEvents.start()
  void init()
})

onMounted(async () => {
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
  if (timer) clearInterval(timer)
  if (reconcileTimer) clearTimeout(reconcileTimer)
  document.removeEventListener('keydown', onGlobalKeydown)
  document.removeEventListener('visibilitychange', onVisibility)
  workspaceEvents.stop()
})
</script>

<template>
  <div class="workspace">
    <header class="ws-top">
      <div class="ws-ident">
        <span class="ws-mark">{{ initial }}</span>
        <div class="ws-name">
          <h1>{{ projectName }}</h1>
          <p>
            <span class="mono">{{ projectId }}</span>
            <i>·</i><span>{{ kpis.conversations }} 会话</span>
            <i>·</i><span :class="{ hot: kpis.activeRuns > 0 }">{{ kpis.activeRuns }} 运行中</span>
            <i>·</i><span>{{ kpis.openTasks }} 待办</span>
            <i>·</i>
            <span class="live" :class="{ on: liveConnected }">
              <i class="live-dot"></i>{{ liveConnected ? '实时' : '轮询兜底' }}
            </span>
            <template v-if="lastRefreshed">
              <i>·</i><span class="muted">更新于 {{ timeAgo(lastRefreshed) }}</span>
            </template>
          </p>
        </div>
      </div>
      <div class="ws-actions">
        <button type="button" class="btn ghost" title="一键诊断工作区" @click="diagnose">
          <el-icon><Aim /></el-icon>诊断
        </button>
        <button type="button" class="icon-btn" title="命令面板（Ctrl/⌘ + K）" @click="openPalette">
          <el-icon><Search /></el-icon>
        </button>
        <el-select v-model="appStore.env" size="small" class="env-select" aria-label="环境">
          <el-option v-for="e in envOptions" :key="e" :label="envLabel(e)" :value="e" />
        </el-select>
        <button type="button" class="icon-btn" title="刷新" :disabled="loading" @click="loadAll">
          <el-icon><Refresh /></el-icon>
        </button>
        <button type="button" class="btn primary" @click="openCreate">
          <el-icon><Plus /></el-icon>新建会话
        </button>
      </div>
    </header>

    <div class="ws-grid">
      <!-- 左：会话 -->
      <aside class="col conv-col">
        <div class="col-head">
          <span class="col-title">{{ showArchived ? '已归档' : '会话' }}</span>
          <span class="col-count">{{ showArchived ? archivedConversations.length : filterCounts.ALL }}</span>
          <button
            v-if="!showArchived" type="button" class="icon-btn sm"
            title="新建会话" @click="openCreate"
          >
            <el-icon><Plus /></el-icon>
          </button>
        </div>

        <label class="search-box">
          <el-icon><Search /></el-icon>
          <input v-model="convSearch" type="search" placeholder="搜索会话" />
        </label>

        <div v-if="!showArchived" class="seg">
          <button
            v-for="f in filters" :key="f.key" type="button"
            :class="{ on: convFilter === f.key }" @click="convFilter = f.key"
          >
            {{ f.label }}<span v-if="filterCounts[f.key]" class="seg-count">{{ filterCounts[f.key] }}</span>
          </button>
        </div>

        <div class="conv-list">
          <div
            v-for="row in filteredConversations" :key="row.id"
            class="conv-item" @click="openConversation(row)"
          >
            <span class="conv-state" :data-s="row.status" :title="statusLabel(row.status)"></span>
            <div class="item-main">
              <span class="item-title">
                <em v-if="row.pinned" class="pin">★</em>{{ row.title }}
              </span>
              <span class="item-meta">
                {{ envLabel(row.env) }} · {{ timeAgo(row.lastActivityAt) }}
                <template v-if="row.summary"> · {{ row.summary }}</template>
              </span>
            </div>
            <button
              v-if="showArchived" type="button" class="text-btn"
              @click.stop="restoreConversation(row)"
            >恢复</button>
            <span v-else class="row-actions" @click.stop>
              <el-dropdown trigger="click" @command="onConvCommand($event, row)">
                <button type="button" class="more-btn" title="更多操作">
                  <el-icon><MoreFilled /></el-icon>
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="pin">{{ row.pinned ? '取消置顶' : '置顶' }}</el-dropdown-item>
                    <el-dropdown-item command="archive" divided>归档</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </span>
          </div>
          <p v-if="!filteredConversations.length" class="empty">
            {{ convSearch || (!showArchived && convFilter !== 'ALL')
              ? '没有匹配的会话'
              : showArchived ? '没有已归档的会话' : '还没有会话，点右上角新建' }}
          </p>
        </div>

        <button
          v-if="archivedConversations.length || showArchived"
          type="button" class="archived-toggle" @click="toggleArchived"
        >
          {{ showArchived ? '← 返回会话' : `已归档 ${archivedConversations.length}` }}
        </button>
      </aside>

      <!-- 中：工作区助手 -->
      <section class="col chat-col">
        <div class="chat-head">
          <div class="chat-title">
            <span>工作区助手</span>
            <span class="tag">只读，不会直接改代码</span>
          </div>
          <div class="chat-head-actions">
            <span v-if="supervisor" class="mono muted">{{ shortId(supervisor.id) }}</span>
            <button
              type="button" class="text-btn"
              :disabled="streaming || !timeline.length" @click="clearChat"
            >清空</button>
          </div>
        </div>

        <div ref="messageListRef" class="chat-scroll">
          <div v-if="!timeline.length" class="hero">
            <h2>要做什么？</h2>
            <p>可以直接问工作区状态，也可以让它创建会话、派发任务并跟踪到完成。</p>
            <div class="action-grid">
              <button
                v-for="a in actionCards" :key="a.title" type="button"
                class="action-card" @click="usePrompt(a.prompt)"
              >
                <span class="action-title">{{ a.title }}</span>
                <span class="action-desc">{{ a.desc }}</span>
              </button>
            </div>
            <p class="hero-note">生产写操作需要你在确认卡上确认。</p>
          </div>

          <div
            v-for="m in timeline" :key="m.id" class="turn"
            :class="m.role === 'user' ? 'user' : 'assistant'"
          >
            <template v-if="m.role === 'assistant'">
              <span class="assistant-avatar">调</span>
              <div class="turn-body">
                <div class="turn-text">{{ m.text }}</div>
                <button type="button" class="copy-btn" @click="copyText(m.text)">复制</button>
              </div>
            </template>
            <div v-else class="user-bubble">{{ m.text }}</div>
          </div>

          <div v-if="streaming && !projection.streamingTurn" class="typing">
            <span></span><span></span><span></span>
          </div>
          <p v-if="error" class="stream-error">{{ error }}</p>
        </div>

        <div v-if="recentTools.length" class="activity">
          <button type="button" class="activity-head" @click="toolsOpen = !toolsOpen">
            <i class="activity-dot"></i>最近动作 · {{ recentTools.length }}
            <span class="chev">{{ toolsOpen ? '收起' : '展开' }}</span>
          </button>
          <div v-if="toolsOpen" class="activity-list">
            <div v-for="t in recentTools" :key="t.toolCallId" class="activity-row">
              <i class="activity-dot" :data-s="t.status"></i>
              <span class="activity-name">{{ toolLabel(t.toolName) }}</span>
              <span class="activity-args">{{ t.arguments || t.resultText }}</span>
            </div>
          </div>
        </div>

        <div class="composer">
          <div class="composer-box">
            <textarea
              ref="composerRef" v-model="input" rows="2"
              placeholder="直接问工作区状态，或描述要做的事（Ctrl/⌘ + K 打开命令面板）"
              @keydown.enter="onEnter"
              @keydown.esc="onEscape"
            />
            <div class="composer-bar">
              <span class="composer-hint">
                {{ streaming
                  ? `调度中 ${(elapsedMs / 1000).toFixed(1)}s · Esc 停止`
                  : 'Enter 发送 · Shift + Enter 换行' }}
              </span>
              <button v-if="streaming" type="button" class="text-btn" @click="stop">
                <el-icon><VideoPause /></el-icon> 停止
              </button>
              <button
                type="button" class="send-btn" :disabled="streaming || !supervisor || !input.trim()"
                title="发送" @click="submit"
              >
                <el-icon><Promotion /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </section>

      <!-- 右：任务 / 运行 -->
      <aside class="col side-col">
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
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #f6f7f9;
}
.mono { font-variant-numeric: tabular-nums; }
.muted { color: var(--ink-faint); }
.hot { color: #b45309; }

/* 顶栏 */
.ws-top {
  display: flex;
  flex: none;
  align-items: center;
  gap: 16px;
  padding: 14px 20px;
}
.ws-ident { display: flex; flex: 1; align-items: center; gap: 10px; min-width: 0; }
.ws-mark {
  display: grid;
  flex: none;
  width: 34px;
  height: 34px;
  font-size: 15px;
  font-weight: 600;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: 10px;
  place-items: center;
}
.ws-name { min-width: 0; }
.ws-name h1 {
  margin: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ws-name p {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 2px 0 0;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--ink-faint);
  white-space: nowrap;
}
.ws-name p i { font-style: normal; opacity: 0.5; }
.ws-actions { display: flex; align-items: center; gap: 8px; }
.env-select { width: 100px; }

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

/* 三栏 */
.ws-grid {
  display: grid;
  flex: 1;
  grid-template-columns: 264px minmax(0, 1fr) 320px;
  gap: 14px;
  min-height: 0;
  padding: 0 20px 18px;
}
.col {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: 12px;
}
.col-head,
.side-head,
.chat-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 8px;
}
.side-head { justify-content: space-between; padding: 10px 12px 8px; border-bottom: 1px solid var(--line-soft); }
.chat-head { justify-content: space-between; border-bottom: 1px solid var(--line-soft); }
.col-title { font-size: 12px; font-weight: 600; color: var(--ink); }
.col-count {
  padding: 0 5px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 999px;
}
.col-head .icon-btn.sm { margin-left: auto; }
.chat-title { display: flex; align-items: center; gap: 8px; font-size: 13px; font-weight: 600; color: var(--ink); }
.chat-title .tag {
  padding: 1px 7px;
  font-size: 10px;
  font-weight: 400;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 999px;
}
.chat-head-actions { display: flex; align-items: center; gap: 10px; font-size: 10.5px; }

/* 左栏 */
.search-box {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  height: 30px;
  margin: 0 12px 8px;
  padding: 0 9px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 8px;
}
.search-box input {
  flex: 1;
  min-width: 0;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: none;
  border: 0;
  outline: none;
}
.seg { display: flex; flex: none; gap: 2px; padding: 0 12px 8px; }
.seg.inline { padding: 0; }
.seg button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.seg button:hover { color: var(--ink); }
.seg button.on { color: var(--ink); background: var(--surface-sunken); font-weight: 500; }
.seg-count { font-size: 10px; opacity: 0.7; }
.conv-list { flex: 1; min-height: 0; overflow-y: auto; padding: 2px 8px 8px; }
.conv-item,
.list-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  cursor: pointer;
  border-radius: 8px;
}
.conv-item { align-items: center; }
.conv-item:hover,
.list-item:hover { background: var(--surface-hover); }
.conv-state { flex: none; width: 6px; height: 6px; background: #cbd5e1; border-radius: 50%; }
.conv-state[data-s='RUNNING'] { background: #f59e0b; }
.conv-state[data-s='NEEDS_CONFIRMATION'] { background: #8b5cf6; }
.conv-state[data-s='SUCCEEDED'] { background: #10b981; }
.conv-state[data-s='FAILED'] { background: #ef4444; }
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
.pin { font-style: normal; color: #f59e0b; }
.row-actions { flex: none; opacity: 0; transition: opacity 0.12s ease; }
.conv-item:hover .row-actions { opacity: 1; }
.more-btn {
  display: grid;
  width: 22px;
  height: 22px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
  place-items: center;
}
.more-btn:hover { color: var(--ink); background: var(--surface-sunken); }
.archived-toggle {
  flex: none;
  padding: 8px 14px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-faint);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-top: 1px solid var(--line-soft);
}
.archived-toggle:hover { color: var(--accent-ink); }
.empty {
  padding: 18px 10px;
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
  text-align: center;
}

/* 中间 */
.chat-scroll { flex: 1; min-height: 0; padding: 24px 28px; overflow-y: auto; }
.hero { max-width: 600px; margin: 5vh auto 0; text-align: center; }
.hero h2 {
  margin: 0 0 6px;
  font-size: 21px;
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.hero p { margin: 0; font-size: 12.5px; line-height: 1.7; color: var(--ink-muted); }
.action-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-top: 22px;
  text-align: left;
}
.action-card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--line-soft);
  border-radius: 10px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.action-card:hover { border-color: var(--accent); box-shadow: var(--shadow-1); }
.action-title { font-size: 12.5px; font-weight: 500; color: var(--ink); }
.action-desc { font-size: 11px; color: var(--ink-faint); }
.hero-note { margin-top: 16px; font-size: 11px; color: var(--ink-faint); }

.turn { display: flex; gap: 10px; margin-bottom: 18px; }
.turn.user { justify-content: flex-end; }
.assistant-avatar {
  display: grid;
  flex: none;
  width: 24px;
  height: 24px;
  margin-top: 2px;
  font-size: 11px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: 8px;
  place-items: center;
}
.turn-body { max-width: min(680px, 82%); }
.turn-text {
  font-size: 13px;
  line-height: 1.85;
  color: var(--ink);
  word-break: break-word;
  white-space: pre-wrap;
}
.copy-btn {
  margin-top: 3px;
  padding: 0;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  opacity: 0;
}
.turn:hover .copy-btn { opacity: 1; }
.user-bubble {
  max-width: 78%;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #fff;
  word-break: break-word;
  white-space: pre-wrap;
  background: var(--accent);
  border-radius: 12px 12px 4px 12px;
}
.typing { display: flex; gap: 4px; padding: 4px 0 0 34px; }
.typing span {
  width: 5px;
  height: 5px;
  background: var(--ink-faint);
  border-radius: 50%;
  animation: blink 1.2s infinite ease-in-out;
}
.typing span:nth-child(2) { animation-delay: 0.15s; }
.typing span:nth-child(3) { animation-delay: 0.3s; }
@keyframes blink {
  0%, 80%, 100% { opacity: 0.25; }
  40% { opacity: 1; }
}
.stream-error { margin: 10px 0 0 34px; font-size: 12px; color: var(--danger); }

.activity { flex: none; padding: 4px 16px 2px; border-top: 1px solid var(--line-soft); }
.activity-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 4px 0;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
}
.activity-head .chev { margin-left: auto; color: var(--ink-faint); }
.activity-dot { width: 5px; height: 5px; background: var(--ink-faint); border-radius: 50%; }
.activity-dot[data-s='RUNNING'] { background: #f59e0b; }
.activity-dot[data-s='COMPLETED'] { background: #10b981; }
.activity-dot[data-s='FAILED'] { background: #ef4444; }
.activity-list { max-height: 118px; padding-bottom: 6px; overflow-y: auto; }
.activity-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
  font-size: 11px;
  color: var(--ink-muted);
}
.activity-name { flex: none; }
.activity-args {
  flex: 1;
  overflow: hidden;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.composer { flex: none; padding: 10px 16px 14px; }
.composer-box {
  padding: 8px 10px;
  background: var(--surface-sunken);
  border: 1px solid transparent;
  border-radius: 12px;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.composer-box:focus-within { background: #fff; border-color: var(--accent); }
.composer textarea {
  width: 100%;
  max-height: 160px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.65;
  color: var(--ink);
  resize: none;
  background: none;
  border: 0;
  outline: none;
}
.composer-bar { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.composer-hint {
  flex: 1;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.send-btn {
  display: grid;
  width: 30px;
  height: 30px;
  color: #fff;
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: 8px;
  place-items: center;
}
.send-btn:disabled { cursor: not-allowed; opacity: 0.4; }

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
  .ws-grid { grid-template-columns: 240px minmax(0, 1fr); }
  .side-col { display: none; }
}
@media (max-width: 820px) {
  .ws-top { flex-wrap: wrap; padding: 12px; }
  .ws-grid { grid-template-columns: 1fr; padding: 0 12px 12px; }
  .conv-col { display: none; }
  .chat-scroll { padding: 16px; }
  .action-grid { grid-template-columns: 1fr; }
}
</style>
