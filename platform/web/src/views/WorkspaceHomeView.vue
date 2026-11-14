<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion, Refresh, VideoPause } from '@element-plus/icons-vue'
import { appStore } from '../stores/app'
import { getToken } from '../api/auth'
import { listProjects, type ProjectRow } from '../api/project'
import { useAgentChat } from '../composables/useAgentChat'
import { loadHistory, saveHistory } from '../state/sessionHistory'
import type { ChatMessage } from '../types'
import {
  cancelRun, createConversation, getSupervisor, listConversations, listRuns, listTasks,
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
const messageListRef = ref<HTMLElement | null>(null)

const envOptions = ['dev', 'test', 'prod']
const activeRuns = computed(() => runs.value.filter(
  (run) => run.status === 'QUEUED' || run.status === 'RUNNING'))
const workerConversations = computed(() => conversations.value.filter((c) => c.kind !== 'SUPERVISOR'))
const tasksByStatus = computed(() => ({
  todo: tasks.value.filter((t) => t.status === 'TODO'),
  running: tasks.value.filter((t) => t.status === 'RUNNING'),
  blocked: tasks.value.filter((t) => t.status === 'BLOCKED'),
  done: tasks.value.filter((t) => t.status === 'DONE'),
}))

const chat = useAgentChat({
  url: () => `/api/v1/workspaces/${projectId.value}/super/stream`,
  buildRequest: () => ({ sessionId: supervisor.value?.id, env: appStore.env }),
  getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
})
const { projection, streaming, error, send: sendChat, stop, hydrate } = chat

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
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '工作区加载失败')
  } finally {
    loading.value = false
  }
}

async function init(): Promise<void> {
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

async function completeTask(task: TaskRow): Promise<void> {
  try {
    await updateTask(projectId.value, task.id, { status: 'DONE' })
    await loadAll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新任务失败')
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

function statusLabel(status: string): string {
  return ({
    IDLE: '空闲', RUNNING: '运行中', NEEDS_CONFIRMATION: '待确认',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
    QUEUED: '排队中', TODO: '待办', BLOCKED: '阻塞', DONE: '完成',
  } as Record<string, string>)[status] ?? status
}

watch(() => [projection.value.messages, projection.value.tools], () => {
  if (supervisor.value) saveHistory(supervisor.value.id, projection.value.messages, projection.value.tools)
}, { deep: true })

watch(() => projection.value.messages.length, async () => {
  await Promise.resolve()
  const el = messageListRef.value
  if (el) el.scrollTop = el.scrollHeight
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
  timer = setInterval(() => { void loadAll() }, 3000)
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
        <span class="ws-id mono">{{ projectId }}</span>
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
          <span class="pill">{{ workerConversations.length }}</span>
        </div>
        <div class="conv-list">
          <div
            v-for="row in workerConversations" :key="row.id"
            class="conv-row" @click="openConversation(row)"
          >
            <div class="conv-main">
              <span class="conv-title" :title="row.title">{{ row.title }}</span>
              <span class="conv-meta">{{ envLabel(row.env) }} · {{ row.worktree || '主仓库' }}</span>
            </div>
            <span class="status-chip" :data-s="row.status">{{ statusLabel(row.status) }}</span>
            <button
              type="button" class="pin-btn" :class="{ on: row.pinned }"
              :title="row.pinned ? '取消置顶' : '置顶'" @click.stop="togglePin(row)"
            >★</button>
          </div>
          <p v-if="!workerConversations.length" class="empty">
            还没有会话。可以在中间让调度 Agent 新建并派发，或点右上角「新建会话」。
          </p>
        </div>
      </aside>

      <!-- 中：调度 Agent -->
      <section class="pane supervisor">
        <div class="pane-head">
          <span>工作区调度 Agent</span>
          <span class="hint">只做编排：创建 / 派发 / 跟踪 / 汇总，不直接改代码</span>
        </div>
        <div ref="messageListRef" class="messages">
          <div v-if="!timeline.length" class="welcome">
            <p class="welcome-title">把整个工作区交给它调度</p>
            <p>例如：「看看有哪些会话在跑，把失败的那个重启」，「新建一个会话修复构建，完成后告诉我」，
              或「把登录相关问题登记成任务并派给合适的会话」。</p>
            <p class="muted">子会话仍在各自环境中执行；生产写操作需要你在确认卡上确认。</p>
          </div>
          <div
            v-for="m in timeline" :key="m.id" class="msg"
            :class="m.role === 'user' ? 'user' : 'assistant'"
          >
            <div class="bubble">{{ m.text }}</div>
          </div>
          <p v-if="error" class="stream-error">{{ error }}</p>
        </div>
        <div class="composer">
          <textarea
            v-model="input" rows="3" placeholder="告诉调度 Agent 要做什么 / 问工作区状态（Enter 发送，Shift+Enter 换行）"
            @keydown.enter="onEnter"
          />
          <div class="composer-actions">
            <span class="composer-hint">{{ supervisor ? `调度会话 ${supervisor.id}` : '调度会话初始化中…' }}</span>
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
        <div class="pane-head"><span>任务板</span><span class="pill">{{ tasks.length }}</span></div>
        <div class="task-list">
          <div v-for="task in tasksByStatus.todo.concat(tasksByStatus.running, tasksByStatus.blocked)"
               :key="task.id" class="task-row">
            <span class="task-status" :data-s="task.status">{{ statusLabel(task.status) }}</span>
            <div class="task-main">
              <span class="task-goal">{{ task.goal }}</span>
              <span v-if="task.expectedOutput" class="task-out">产出：{{ task.expectedOutput }}</span>
            </div>
            <button type="button" class="mini-btn" @click="completeTask(task)">完成</button>
          </div>
          <p v-if="!tasksByStatus.todo.length && !tasksByStatus.running.length && !tasksByStatus.blocked.length"
             class="empty">暂无待办任务</p>
          <p v-if="tasksByStatus.done.length" class="done-note">
            已完成 {{ tasksByStatus.done.length }} 项
          </p>
        </div>

        <div class="pane-head runs-head">
          <span>运行队列</span><span class="pill">{{ activeRuns.length }}</span>
        </div>
        <div class="run-list">
          <div v-for="run in activeRuns" :key="run.id" class="run-row">
            <div class="run-main">
              <span class="run-goal">{{ run.goal }}</span>
              <span class="run-meta">{{ statusLabel(run.status) }} · {{ run.conversationId }}</span>
            </div>
            <button type="button" class="mini-btn" @click="cancel(run)">取消</button>
          </div>
          <p v-if="!activeRuns.length" class="empty">当前没有运行中的任务</p>
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
.ws-id { font-size: 11px; color: var(--ink-faint); }
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
.mini-btn {
  height: 24px;
  padding: 0 8px;
  font-size: 11.5px;
  color: var(--ink-muted);
  background: none;
  border: 1px solid var(--line-soft);
}
.mini-btn:hover { color: var(--accent-ink); border-color: var(--accent); }

.ws-body {
  display: grid;
  flex: 1;
  grid-template-columns: 280px minmax(0, 1fr) 330px;
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
  overflow: hidden;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
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
.conv-main { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 2px; }
.conv-title {
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.conv-meta { font-size: 10.5px; color: var(--ink-faint); }
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
.stream-error { margin: 10px 0 0; font-size: 12px; color: var(--danger); }
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
.task-list, .run-list { padding: 0 8px 8px; }
.runs-head { margin-top: 4px; border-top: 1px solid var(--line-soft); }
.task-row, .run-row {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 8px;
  border-radius: var(--radius-sm);
}
.task-row:hover, .run-row:hover { background: var(--surface-hover); }
.task-main, .run-main { display: flex; flex: 1; flex-direction: column; min-width: 0; gap: 2px; }
.task-goal, .run-goal {
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink);
  word-break: break-word;
}
.task-out, .run-meta { font-size: 10.5px; color: var(--ink-faint); }
.done-note { padding: 0 14px 10px; margin: 0; font-size: 11px; color: var(--ink-faint); }

@media (max-width: 1100px) {
  .ws-body { grid-template-columns: 240px minmax(0, 1fr); }
  .pane.side { display: none; }
}
@media (max-width: 760px) {
  .ws-body { grid-template-columns: 1fr; }
  .pane.conversations { display: none; }
  .ws-header { flex-wrap: wrap; align-items: center; }
}
</style>
