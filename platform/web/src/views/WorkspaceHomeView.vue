<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, Refresh, VideoPause } from '@element-plus/icons-vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import { listProjects, type ProjectRow } from '../api/project'
import { useAgentChat } from '../composables/useAgentChat'
import { clearHistory, loadHistory, saveHistory } from '../state/sessionHistory'
import type { ChatMessage } from '../types'
import {
  cancelRun, createConversation, createTask, getSupervisor, listConversations, listRuns, listTasks,
  syncConversations, updateConversation, updateTask,
  type ConversationRow, type RunRow, type TaskRow,
} from '../api/workspace'
import { envLabel } from '../utils/pipelineLabels'

/**
 * 工作区首页：左侧会话列表、中间调度 Agent、右侧任务板 / 运行队列。
 *
 * 工作区 = 项目；点击侧栏工作区标题会进入这里，由调度 Agent 统一创建、派发、
 * 跟踪与汇总该工作区内的会话。调度 Agent 没有任何写工具，真正执行仍落到子会话。
 */
const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId ?? appStore.projectId))
const projectName = ref('')
const projects = ref<ProjectRow[]>([])

const conversations = ref<ConversationRow[]>([])
const supervisor = ref<ConversationRow | null>(null)
const tasks = ref<TaskRow[]>([])
const runs = ref<RunRow[]>([])
const input = ref('')
const loading = ref(false)
const lastRefreshed = ref(0)
const messageListRef = ref<HTMLElement | null>(null)
const composerRef = ref<HTMLTextAreaElement | null>(null)

/** 左侧筛选：全部 / 进行中 / 失败 / 待确认。 */
type ConvFilter = 'ALL' | 'ACTIVE' | 'FAILED' | 'CONFIRM'
const convSearch = ref('')
const convFilter = ref<ConvFilter>('ALL')

/** 右侧运行队列：进行中 / 最近结束。 */
const runTab = ref<'active' | 'recent'>('active')
const showDoneTasks = ref(false)
const taskDraft = ref({ goal: '', expectedOutput: '' })
const creatingTask = ref(false)

const envOptions = ['dev', 'test', 'prod']

const workerConversations = computed(() => conversations.value.filter((c) => c.kind !== 'SUPERVISOR'))
const activeRuns = computed(() => runs.value.filter(
  (run) => run.status === 'QUEUED' || run.status === 'RUNNING'))
const recentRuns = computed(() => runs.value
  .filter((run) => run.status !== 'QUEUED' && run.status !== 'RUNNING')
  .slice(0, 8))
const visibleRuns = computed(() => (runTab.value === 'active' ? activeRuns.value : recentRuns.value))

const filterCounts = computed(() => ({
  ALL: workerConversations.value.length,
  ACTIVE: workerConversations.value.filter((c) => c.status === 'RUNNING').length,
  FAILED: workerConversations.value.filter((c) => c.status === 'FAILED').length,
  CONFIRM: workerConversations.value.filter((c) => c.status === 'NEEDS_CONFIRMATION').length,
}))

const filteredConversations = computed(() => {
  const query = convSearch.value.trim().toLowerCase()
  let list = workerConversations.value
  if (query) {
    list = list.filter((c) =>
      c.title.toLowerCase().includes(query)
      || (c.summary ?? '').toLowerCase().includes(query)
      || c.id.toLowerCase().includes(query))
  }
  if (convFilter.value === 'ACTIVE') list = list.filter((c) => c.status === 'RUNNING')
  if (convFilter.value === 'FAILED') list = list.filter((c) => c.status === 'FAILED')
  if (convFilter.value === 'CONFIRM') list = list.filter((c) => c.status === 'NEEDS_CONFIRMATION')
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

const quickActions = [
  '工作区现在什么状态？',
  '列出运行中的会话和最近失败',
  '新建一个会话修复构建失败，并跟踪到完成',
  '汇总今天的运行，把待办登记成任务',
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

/** 最近几次调度动作：让用户看到 Agent 到底调用了哪些管理工具。 */
const recentTools = computed(() => Object.values(projection.value.tools ?? {}).slice(-5).reverse())
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

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    const [list, taskList, runList] = await Promise.all([
      listConversations(projectId.value, true),
      listTasks(projectId.value),
      listRuns(projectId.value, 30),
    ])
    conversations.value = list
    tasks.value = taskList
    runs.value = runList
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
  try {
    projects.value = await listProjects()
    projectName.value = projects.value.find((p) => p.id === projectId.value)?.name ?? projectId.value
  } catch {
    projectName.value = projectId.value
  }
  appStore.projectId = projectId.value
  // 把本地历史会话导入服务端（同 id 跳过），让工作区首页能看到旧会话
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
    ElMessage.error(e instanceof Error ? e.message : '调度会话初始化失败')
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

async function newConversation(): Promise<void> {
  try {
    const created = await createConversation(projectId.value, {
      title: '新会话',
      env: appStore.env,
    })
    await loadAll()
    openConversation(created)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新建会话失败')
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

async function cancel(run: RunRow): Promise<void> {
  try {
    await cancelRun(run.id)
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  }
}

/** 运行所属会话可能已归档或被删除，找不到时给出提示而不是跳到错误会话。 */
function openRun(run: RunRow): void {
  const conversation = conversations.value.find((c) => c.id === run.conversationId)
  if (!conversation) {
    ElMessage.info('该运行所属的会话已不在当前工作区')
    return
  }
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

async function addTask(): Promise<void> {
  const goal = taskDraft.value.goal.trim()
  if (!goal || creatingTask.value) return
  creatingTask.value = true
  try {
    await createTask(projectId.value, {
      goal,
      expectedOutput: taskDraft.value.expectedOutput.trim() || undefined,
    })
    taskDraft.value = { goal: '', expectedOutput: '' }
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '新建任务失败')
  } finally {
    creatingTask.value = false
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

function useQuick(text: string): void {
  input.value = text
  void nextTick(() => composerRef.value?.focus())
}

async function clearChat(): Promise<void> {
  if (!supervisor.value) return
  try {
    await ElMessageBox.confirm(
      '清空后只删除本机保存的调度对话记录，服务端会话与运行记录仍保留。',
      '清空调度对话',
      { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  clearHistory(supervisor.value.id)
  reset()
  ElMessage.success('已清空调度对话')
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

/** 运行耗时：进行中按当前时间算，结束后用结束时间。 */
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
  return id.length > 12 ? `${id.slice(0, 12)}…` : id
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

// 流式正文是逐字追加的，长度 watcher 不会触发，单独跟随一次
watch(() => projection.value.streamingTurn?.text, async () => {
  await Promise.resolve()
  scrollToBottom()
})

watch(projectId, () => {
  supervisor.value = null
  conversations.value = []
  tasks.value = []
  runs.value = []
  void init()
})

onMounted(async () => {
  await init()
  // 页面不可见时不轮询，避免后台标签页持续打接口
  timer = setInterval(() => {
    if (!document.hidden) void loadAll()
  }, 3000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="workspace">
    <header class="ws-header">
      <div class="ws-title">
        <span class="eyebrow">工作区</span>
        <h1>{{ projectName }}</h1>
        <div class="ws-meta">
          <span class="ws-id mono">{{ projectId }}</span>
          <span class="kpi"><b>{{ kpis.conversations }}</b> 会话</span>
          <span class="kpi" :class="{ hot: kpis.activeRuns > 0 }"><b>{{ kpis.activeRuns }}</b> 运行中</span>
          <span class="kpi"><b>{{ kpis.openTasks }}</b> 待办</span>
          <span v-if="lastRefreshed" class="refreshed">更新于 {{ timeAgo(lastRefreshed) }}</span>
        </div>
      </div>
      <div class="ws-actions">
        <el-select v-model="appStore.env" size="small" class="env-select" aria-label="环境">
          <el-option v-for="e in envOptions" :key="e" :label="envLabel(e)" :value="e" />
        </el-select>
        <button type="button" class="ghost-btn" :disabled="loading" @click="loadAll">
          <el-icon><Refresh /></el-icon> 刷新
        </button>
        <button type="button" class="primary-btn" @click="newConversation">新建会话</button>
      </div>
    </header>

    <div class="ws-body">
      <!-- 左：会话列表 -->
      <aside class="pane conversations">
        <div class="pane-head">
          <span>会话</span>
          <span class="pill">{{ filterCounts.ALL }}</span>
        </div>
        <div class="conv-tools">
          <input
            v-model="convSearch" class="conv-search" type="search"
            placeholder="搜索标题 / 摘要 / ID" aria-label="搜索会话"
          />
          <div class="conv-filters">
            <button
              v-for="f in ([
                { key: 'ALL', label: '全部' },
                { key: 'ACTIVE', label: '进行中' },
                { key: 'FAILED', label: '失败' },
                { key: 'CONFIRM', label: '待确认' },
              ] as const)" :key="f.key"
              type="button" class="filter-chip" :class="{ on: convFilter === f.key }"
              @click="convFilter = f.key"
            >
              {{ f.label }}<span v-if="filterCounts[f.key]" class="chip-count">{{ filterCounts[f.key] }}</span>
            </button>
          </div>
        </div>
        <div class="conv-list">
          <div
            v-for="row in filteredConversations" :key="row.id"
            class="conv-row" @click="openConversation(row)"
          >
            <div class="conv-main">
              <span class="conv-title" :title="row.title">
                <span v-if="row.pinned" class="pin-mark">★</span>{{ row.title }}
              </span>
              <span class="conv-meta">
                {{ envLabel(row.env) }} · {{ row.worktree || '主仓库' }} · {{ timeAgo(row.lastActivityAt) }}
              </span>
              <span v-if="row.summary" class="conv-summary" :title="row.summary">{{ row.summary }}</span>
            </div>
            <span class="status-chip" :data-s="row.status">{{ statusLabel(row.status) }}</span>
            <button
              type="button" class="pin-btn" :class="{ on: row.pinned }"
              :title="row.pinned ? '取消置顶' : '置顶'" @click.stop="togglePin(row)"
            >★</button>
          </div>
          <p v-if="!filteredConversations.length" class="empty">
            {{ convSearch || convFilter !== 'ALL'
              ? '没有匹配的会话。'
              : '还没有会话。可以在中间让调度 Agent 新建并派发，或点右上角「新建会话」。' }}
          </p>
        </div>
      </aside>

      <!-- 中：调度 Agent -->
      <section class="pane supervisor">
        <div class="pane-head">
          <span>工作区调度 Agent</span>
          <span class="hint">只做编排：创建 / 派发 / 跟踪 / 汇总，不直接改代码</span>
          <button
            type="button" class="mini-btn clear-btn" :disabled="streaming || !timeline.length"
            title="清空本机保存的调度对话" @click="clearChat"
          >清空</button>
        </div>
        <div ref="messageListRef" class="messages">
          <div v-if="!timeline.length" class="welcome">
            <p class="welcome-title">把整个工作区交给它调度</p>
            <p>例如：「看看有哪些会话在跑，把失败的那个重启」，「新建一个会话修复构建，完成后告诉我」，
              或「把登录相关问题登记成任务并派给合适的会话」。</p>
            <p class="muted">子会话仍在各自环境中执行；生产写操作需要你在确认卡上确认。</p>
            <div class="quick-list">
              <button
                v-for="q in quickActions" :key="q" type="button" class="quick-chip"
                @click="useQuick(q)"
              >{{ q }}</button>
            </div>
          </div>
          <div
            v-for="m in timeline" :key="m.id" class="msg"
            :class="m.role === 'user' ? 'user' : 'assistant'"
          >
            <div class="bubble">{{ m.text }}</div>
          </div>
          <div v-if="streaming && !projection.streamingTurn" class="typing">
            <span></span><span></span><span></span>
          </div>
          <p v-if="error" class="stream-error">{{ error }}</p>
        </div>
        <div v-if="recentTools.length" class="tool-strip">
          <span class="tool-strip-label">最近动作</span>
          <span
            v-for="t in recentTools" :key="t.toolCallId" class="tool-chip"
            :data-s="t.status" :title="t.arguments || t.toolName"
          >
            <i class="dot"></i>{{ toolLabel(t.toolName) }}
          </span>
        </div>
        <div class="composer">
          <textarea
            ref="composerRef" v-model="input" rows="3"
            placeholder="告诉调度 Agent 要做什么 / 问工作区状态（Enter 发送，Shift+Enter 换行）"
            @keydown.enter="onEnter"
          />
          <div class="composer-actions">
            <span class="composer-hint">
              {{ streaming
                ? `调度中 ${(elapsedMs / 1000).toFixed(1)}s，可随时停止`
                : (supervisor ? `调度会话 ${shortId(supervisor.id)}` : '调度会话初始化中…') }}
            </span>
            <button v-if="streaming" type="button" class="ghost-btn" @click="stop">
              <el-icon><VideoPause /></el-icon> 停止
            </button>
            <button
              type="button" class="primary-btn" :disabled="streaming || !supervisor"
              @click="submit"
            >
              <el-icon><Promotion /></el-icon> 发送
            </button>
          </div>
        </div>
      </section>

      <!-- 右：任务板 + 运行队列 -->
      <aside class="pane side">
        <div class="pane-head">
          <span>任务板</span><span class="pill">{{ kpis.openTasks }}</span>
          <button
            type="button" class="mini-btn head-btn"
            :disabled="!doneTasks.length" @click="showDoneTasks = !showDoneTasks"
          >{{ showDoneTasks ? '收起完成' : `已完成 ${doneTasks.length}` }}</button>
        </div>
        <div class="task-add">
          <input v-model="taskDraft.goal" placeholder="登记一个任务…" @keydown.enter="addTask" />
          <input v-model="taskDraft.expectedOutput" placeholder="期望产出（可选）" @keydown.enter="addTask" />
          <button
            type="button" class="primary-btn sm" :disabled="!taskDraft.goal.trim() || creatingTask"
            @click="addTask"
          >{{ creatingTask ? '添加中…' : '添加' }}</button>
        </div>
        <div class="task-list">
          <div v-for="task in openTasks" :key="task.id" class="task-row">
            <span class="task-status" :data-s="task.status">{{ statusLabel(task.status) }}</span>
            <div class="task-main">
              <span class="task-goal">{{ task.goal }}</span>
              <span v-if="task.expectedOutput" class="task-out">产出：{{ task.expectedOutput }}</span>
              <span class="task-meta">
                <span v-if="task.priority" class="prio">P{{ task.priority }}</span>
                <span v-if="task.conversationId">{{ conversationTitle(task.conversationId) }}</span>
              </span>
            </div>
            <span class="task-actions">
              <button
                v-if="task.status === 'TODO' || task.status === 'BLOCKED'"
                type="button" class="mini-btn" @click="setTaskStatus(task, 'RUNNING')"
              >{{ task.status === 'BLOCKED' ? '重试' : '开始' }}</button>
              <button type="button" class="mini-btn" @click="setTaskStatus(task, 'DONE')">完成</button>
            </span>
          </div>
          <p v-if="!openTasks.length" class="empty">暂无待办任务，可在上方登记，或让调度 Agent 创建。</p>
          <div v-if="showDoneTasks && doneTasks.length" class="done-list">
            <div v-for="task in doneTasks" :key="task.id" class="task-row done">
              <span class="task-status" data-s="DONE">完成</span>
              <div class="task-main">
                <span class="task-goal">{{ task.goal }}</span>
                <span class="task-out">{{ timeAgo(task.updatedAt) }}</span>
              </div>
              <button type="button" class="mini-btn" @click="setTaskStatus(task, 'TODO')">重开</button>
            </div>
          </div>
        </div>

        <div class="pane-head runs-head">
          <span>运行队列</span><span class="pill">{{ activeRuns.length }}</span>
          <span class="run-tabs">
            <button
              type="button" class="filter-chip" :class="{ on: runTab === 'active' }"
              @click="runTab = 'active'"
            >进行中</button>
            <button
              type="button" class="filter-chip" :class="{ on: runTab === 'recent' }"
              @click="runTab = 'recent'"
            >最近</button>
          </span>
        </div>
        <div class="run-list">
          <div
            v-for="run in visibleRuns" :key="run.id" class="run-row"
            :title="run.error || run.result || run.goal" @click="openRun(run)"
          >
            <div class="run-main">
              <span class="run-goal">{{ run.goal }}</span>
              <span class="run-meta">
                {{ statusLabel(run.status) }} · {{ conversationTitle(run.conversationId) }} · {{ runDuration(run) }}
              </span>
              <span v-if="run.error" class="run-error">{{ run.error }}</span>
            </div>
            <button
              v-if="run.status === 'QUEUED' || run.status === 'RUNNING'"
              type="button" class="mini-btn" @click.stop="cancel(run)"
            >取消</button>
          </div>
          <p v-if="!visibleRuns.length" class="empty">
            {{ runTab === 'active' ? '当前没有运行中的任务' : '还没有已结束的运行' }}
          </p>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--surface-sunken);
}
.ws-header {
  display: flex;
  flex: none;
  align-items: flex-end;
  gap: 16px;
  padding: 18px 22px 14px;
  background: var(--surface-raised);
  border-bottom: 1px solid var(--line-soft);
}
.ws-title { flex: 1; min-width: 0; }
.eyebrow {
  display: block;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  letter-spacing: 0.08em;
}
.ws-title h1 {
  margin: 2px 0 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--ink);
}
.ws-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 4px; }
.ws-id { font-size: 11px; color: var(--ink-faint); }
.kpi { font-size: 11.5px; color: var(--ink-muted); }
.kpi b { font-weight: 600; color: var(--ink); }
.kpi.hot b { color: #b45309; }
.refreshed { font-size: 10.5px; color: var(--ink-faint); }
.ws-actions { display: flex; align-items: center; gap: 8px; }
.env-select { width: 104px; }
.ghost-btn, .primary-btn, .mini-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.ghost-btn {
  color: var(--ink-soft);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
}
.ghost-btn:hover:not(:disabled) { background: var(--surface-hover); }
.primary-btn {
  color: #fff;
  background: var(--accent);
  border: 1px solid transparent;
}
.primary-btn:hover:not(:disabled) { filter: brightness(1.05); }
.primary-btn:disabled, .ghost-btn:disabled { cursor: not-allowed; opacity: 0.5; }
.primary-btn.sm { height: 26px; padding: 0 10px; font-size: 11.5px; }
.mini-btn {
  height: 24px;
  padding: 0 8px;
  font-size: 11.5px;
  color: var(--ink-muted);
  background: none;
  border: 1px solid var(--line-soft);
}
.mini-btn:hover:not(:disabled) { color: var(--accent-ink); border-color: var(--accent); }
.mini-btn:disabled { cursor: not-allowed; opacity: 0.5; }

.ws-body {
  display: grid;
  flex: 1;
  grid-template-columns: 288px minmax(0, 1fr) 340px;
  gap: 12px;
  min-height: 0;
  padding: 12px;
}
.pane {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-1);
}
.pane-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.pane-head .hint {
  flex: 1;
  overflow: hidden;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.clear-btn { flex: none; }
.head-btn { margin-left: auto; font-weight: 400; }
.pill {
  padding: 0 6px;
  font-size: 10.5px;
  font-weight: 500;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.empty {
  padding: 10px 14px;
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-faint);
}

/* 会话列表 */
.conv-tools { flex: none; padding: 0 8px 6px; }
.conv-search {
  width: 100%;
  height: 28px;
  padding: 0 9px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
}
.conv-search:focus { border-color: var(--accent); }
.conv-filters { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.filter-chip:hover { color: var(--ink); background: var(--surface-hover); }
.filter-chip.on { color: var(--accent-ink); background: var(--accent-soft); border-color: transparent; }
.chip-count { font-size: 10px; opacity: 0.75; }
.conv-list { flex: 1; overflow-y: auto; padding: 0 6px 8px; }
.conv-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px;
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.conv-row:hover { background: var(--surface-hover); }
.conv-main { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 1px; }
.conv-title {
  display: flex;
  gap: 3px;
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.pin-mark { color: #f59e0b; }
.conv-meta { font-size: 10.5px; color: var(--ink-faint); }
.conv-summary {
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.status-chip, .task-status {
  flex: none;
  padding: 1px 6px;
  font-size: 10px;
  color: var(--ink-muted);
  white-space: nowrap;
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.status-chip[data-s='RUNNING'], .task-status[data-s='RUNNING'] { color: #b45309; background: #fef3c7; }
.status-chip[data-s='NEEDS_CONFIRMATION'] { color: #b45309; background: #fef3c7; }
.status-chip[data-s='SUCCEEDED'], .task-status[data-s='DONE'] { color: #047857; background: #d1fae5; }
.status-chip[data-s='FAILED'], .task-status[data-s='BLOCKED'] { color: #b91c1c; background: #fee2e2; }
.status-chip[data-s='CANCELLED'] { color: var(--ink-muted); background: var(--surface-active); }
.pin-btn {
  flex: none;
  width: 20px;
  padding: 0;
  font-size: 12px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
}
.pin-btn.on { color: #f59e0b; }

/* 中间调度区 */
.messages {
  flex: 1;
  min-height: 0;
  padding: 0 16px 12px;
  overflow-y: auto;
}
.welcome {
  padding: 28px 6px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--ink-soft);
}
.welcome-title { margin: 0 0 6px; font-size: 15px; font-weight: 600; color: var(--ink); }
.welcome .muted { color: var(--ink-faint); font-size: 12px; }
.quick-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 14px; }
.quick-chip {
  padding: 5px 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.quick-chip:hover { color: var(--accent-ink); background: var(--accent-soft); border-color: transparent; }
.msg { display: flex; margin-top: 12px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 78%;
  padding: 9px 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--surface-sunken);
  border-radius: 10px;
}
.msg.user .bubble { color: #fff; background: var(--accent); }
.typing { display: flex; gap: 4px; padding: 8px 0 0; }
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
.stream-error { margin: 10px 0 0; font-size: 12px; color: var(--danger); }
.tool-strip {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  padding: 6px 14px;
  border-top: 1px solid var(--line-soft);
}
.tool-strip-label { font-size: 10.5px; color: var(--ink-faint); }
.tool-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.tool-chip .dot {
  width: 5px;
  height: 5px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.tool-chip[data-s='RUNNING'] .dot { background: #f59e0b; }
.tool-chip[data-s='COMPLETED'] .dot { background: #10b981; }
.tool-chip[data-s='FAILED'] .dot { background: #ef4444; }
.composer {
  flex: none;
  padding: 10px 12px 12px;
  border-top: 1px solid var(--line-soft);
}
.composer textarea {
  width: 100%;
  padding: 9px 10px;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink);
  resize: none;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
}
.composer textarea:focus { border-color: var(--accent); }
.composer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
.composer-hint {
  flex: 1;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 右侧任务 / 运行 */
.side { overflow-y: auto; }
.task-add { flex: none; display: flex; flex-direction: column; gap: 5px; padding: 0 10px 8px; }
.task-add input {
  height: 27px;
  padding: 0 9px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
}
.task-add input:focus { border-color: var(--accent); }
.task-add .primary-btn { align-self: flex-end; }
.task-list, .run-list { padding: 0 8px 8px; }
.done-list { margin-top: 4px; padding-top: 4px; border-top: 1px dashed var(--line-soft); }
.runs-head { margin-top: 4px; border-top: 1px solid var(--line-soft); }
.run-tabs { display: flex; gap: 4px; margin-left: auto; }
.task-row, .run-row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 8px;
  border-radius: var(--radius-sm);
}
.task-row:hover, .run-row:hover { background: var(--surface-hover); }
.run-row { cursor: pointer; }
.task-row.done { opacity: 0.72; }
.task-main, .run-main { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 2px; }
.task-goal, .run-goal {
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink);
  word-break: break-word;
}
.task-out, .run-meta { font-size: 10.5px; color: var(--ink-faint); }
.task-meta { display: flex; gap: 6px; font-size: 10.5px; color: var(--ink-faint); }
.prio {
  padding: 0 4px;
  font-size: 9.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 3px;
}
.task-actions { display: flex; flex: none; flex-direction: column; gap: 4px; }
.run-error {
  overflow: hidden;
  font-size: 10.5px;
  color: var(--danger);
  white-space: nowrap;
  text-overflow: ellipsis;
}

@media (max-width: 1100px) {
  .ws-body { grid-template-columns: 250px minmax(0, 1fr); }
  .pane.side { display: none; }
}
@media (max-width: 760px) {
  .ws-body { grid-template-columns: 1fr; }
  .pane.conversations { display: none; }
  .ws-header { flex-wrap: wrap; align-items: center; }
}
</style>
