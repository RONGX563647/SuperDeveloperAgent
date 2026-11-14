<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, CaretRight, ChatDotRound, Coin, Paperclip, Promotion, Setting,
} from '@element-plus/icons-vue'
import { appStore, type PermissionMode } from '../stores/app'
import type { TraceTurn } from '../types'
import { useAgentChat } from '../composables/useAgentChat'
import { useFollowScroll } from '../composables/useFollowScroll'
import { getToken } from '../api/auth'
import { listProjects, type ProjectRow } from '../api/project'
import { envLabel } from '../utils/pipelineLabels'
import { formatCost, formatTokens, sumCost } from '../utils/deepseekPricing'
import { loadHistory, saveHistory } from '../state/sessionHistory'
import ConversationTimeline from '../components/chat/ConversationTimeline.vue'
import ChatTraceRail from '../components/chat/ChatTraceRail.vue'
import ChatCapabilitiesView from '../components/chat/ChatCapabilitiesView.vue'
import ChatIssuesView from '../components/chat/ChatIssuesView.vue'
import { useChatCapabilities } from '../composables/useChatCapabilities'
import ChatTraceView from '../components/chat/ChatTraceView.vue'
import ApprovalRequestCard from '../components/chat/ApprovalRequestCard.vue'
import ChatStatusStrip from '../components/chat/ChatStatusStrip.vue'
import EnvironmentStatusBar from '../components/chat/EnvironmentStatusBar.vue'
import PageHeader from '../layouts/components/PageHeader.vue'

/**
 * 对话页 —— 已按三层职责拆分（见 docs/项目规约 前端分层）：
 *
 * - 传输层：src/api/sse.ts（SSE 分帧 / 归一化 / 重连续传）
 * - 编排层：src/composables/useAgentChat.ts（事件投影 / 生命周期 / 停止）
 * - 渲染层：本视图 + components/chat/*（ConversationTimeline / MessageItem /
 *   ApprovalRequestCard / MarkdownView）
 *
 * 本视图只负责：布局、输入区、IME 组合、粘底滚动、组件编排；不再直接 fetch/读流。
 */
/** 项目切换由顶栏 ContextSwitcher 负责，这里只兜底选择合法项目。 */
const projectId = computed(() => appStore.projectId)
const env = computed(() => appStore.env)
const sessionId = computed(() => appStore.sessionId)

const router = useRouter()

/** 本会话可调用的 Skill / MCP：默认取设置页启用项，可在轨迹旁工具栏按会话调整。 */
const {
  skillOptions, mcpOptions, selectedSkills, selectedMcp,
  ready: capsReady, loading: capsLoading, refresh: refreshCapabilities,
} = useChatCapabilities(sessionId)

/** 视图模式：对话 / 轨迹 / 能力 / 任务，四个平级页面共用底部输入区。 */
const viewMode = ref<'chat' | 'trace' | 'capabilities' | 'issues'>('chat')

/** 任务 Tab 的待处理角标（由任务页加载后回传）。 */
const issueCount = ref(0)

/** 项目列表：只用于显示项目名与默认项目兜底（下拉已移除）。 */
const projects = ref<ProjectRow[]>([])

/**
 * 项目/环境切换已统一到顶栏 ContextSwitcher（appStore 是唯一真相），
 * 输入区只保留一句当前上下文提示，不再维护第二套下拉。
 */
const envText = computed(() => envLabel(env.value))

const input = ref('')
/** 执行策略提升到全局 store（本地持久化）；三种模式实际影响确认卡的展开行为。 */
const permission = computed<PermissionMode>({
  get: () => appStore.permission,
  set: (value) => appStore.setPermission(value),
})
/** 中文输入法组合标记：组合期间 Enter 不应触发发送。 */
const composing = ref(false)

const {
  projection, streaming, elapsedMs, tokens, error, pendingChangeId,
  send: sendChat, stop, reset, appendMessage, hydrate,
} = useAgentChat({
  buildRequest: () => ({
    projectId: projectId.value,
    env: env.value,
    sessionId: sessionId.value,
    // 当前工作树：随会话一起带给后端，让 Agent 知道在哪个检出里工作
    worktree: appStore.worktree,
    // 后端目前按变更单做门禁，这里带上策略为后续服务端约束留接口
    permission: permission.value,
    // 本会话启用的能力；未加载完时传 undefined，让后端按设置页默认值处理
    skills: capsReady.value ? selectedSkills.value : undefined,
    mcpServers: capsReady.value ? selectedMcp.value : undefined,
  }),
  getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
  guard: () => !composing.value,
})

/** 会话累计费用（仅 deepseek 系列会产生；为 0 时不展示 chip）。 */
const sessionCost = computed(() => projection.value.sessionCost)

const listRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLTextAreaElement | null>(null)
const approvalCard = ref<InstanceType<typeof ApprovalRequestCard> | null>(null)
/** 变更确认卡是否展开（由 change.created 事件自动展开；dismiss 收起但不丢 pendingChangeId）。 */
const approvalCardOpen = ref(false)

/** 上下文窗口估算：服务端暂不回精确 token，状态条里会明确标注为估算值。 */
const CONTEXT_LIMIT = 128_000
const contextUsage = computed(() => {
  const messages = projection.value.messages
  const turn = projection.value.streamingTurn
  const chars = [
    ...messages.map((m) => m.text),
    turn?.text ?? '',
    turn?.thinking ?? ''
  ].reduce((sum, text) => sum + text.length, 0)
  return {
    used: Math.round(1800 + chars / 2.6 + messages.length * 12 + tokens.value * 4),
    limit: CONTEXT_LIMIT,
    messages: messages.length,
    events: tokens.value
  }
})

function openApprovalCard(): void {
  if (permission.value === 'readonly') return
  approvalCardOpen.value = true
  void nextTick(() => {
    approvalCard.value?.load()
    void scrollToLatest({ force: true })
  })
}

const {
  updateScrollState, followNewContent, scrollToLatest,
} = useFollowScroll(listRef, { bottomThreshold: 40 })

/**
 * 轨迹：把扁平的消息列按 user 消息切成一「轮」，供右侧定位条与轨迹视图使用。
 * 纯派生数据、不落盘——历史恢复后由 messages 重新算出同样的轮次。
 * 每步耗时按"本条时间 → 下一条时间"估算：事件本身不带开始时间，这个间隔已足够看出
 * 慢在模型还是慢在工具，界面上会注明是估算值。
 */
const traceTurns = computed<TraceTurn[]>(() => {
  const turns: TraceTurn[] = []
  for (const m of projection.value.messages) {
    if (m.role === 'user') {
      turns.push({
        id: m.id,
        index: turns.length + 1,
        question: m.text.replace(/\s+/g, ' ').trim().slice(0, 42),
        userText: m.text,
        at: m.at,
        startedAt: m.at,
        endedAt: m.at,
        endAt: m.at,
        steps: 0,
        tools: 0,
        failed: false,
        segments: [],
        items: [],
      })
      continue
    }
    const turn = turns[turns.length - 1]
    if (!turn) continue
    turn.endAt = m.at
    turn.endedAt = m.at
    if (m.role === 'assistant') {
      turn.steps += 1
      turn.segments.push('text')
      turn.items.push({
        id: m.id, kind: 'text', title: '模型输出', text: m.text, at: m.at, durationMs: 0,
        cost: m.cost,
      })
      // 每轮费用 = 轮内所有模型调用之和（工具循环会产生多条 assistant 消息）
      if (m.cost) turn.cost = sumCost(turn.cost, m.cost)
    } else if (m.role === 'step') {
      turn.steps += 1
      turn.tools += 1
      turn.segments.push('tool')
      const tool = m.toolCallId ? projection.value.tools[m.toolCallId] : undefined
      turn.items.push({
        id: m.id,
        kind: 'tool',
        title: m.text,
        text: m.label ?? '',
        toolCallId: m.toolCallId,
        at: m.at,
        durationMs: 0,
        status: tool?.status,
      })
    } else if (m.role === 'error') {
      turn.failed = true
      turn.items.push({
        id: m.id, kind: 'error', title: '运行失败', text: m.text, at: m.at, durationMs: 0,
      })
    }
  }
  // 流式中的思考/正文尚未落盘：作为临时步骤挂到当前轮，轨迹视图也能边输出边刷新
  const streaming = projection.value.streamingTurn
  const liveTurn = turns[turns.length - 1]
  if (streaming && liveTurn) {
    const now = Date.now()
    if (streaming.thinking) {
      liveTurn.steps += 1
      liveTurn.segments.push('text')
      liveTurn.items.push({
        id: 'live-thinking', kind: 'text', title: '模型思考',
        text: streaming.thinking, at: liveTurn.startedAt, durationMs: 0,
      })
    }
    if (streaming.text) {
      liveTurn.steps += 1
      liveTurn.segments.push('text')
      liveTurn.items.push({
        id: 'live-text', kind: 'text', title: '模型输出',
        text: streaming.text, at: now, durationMs: 0, cost: streaming.cost,
      })
    }
    // 流式中的费用尚未落盘，实时并入当前轮
    if (streaming.cost) liveTurn.cost = sumCost(liveTurn.cost, streaming.cost)
    liveTurn.endAt = now
    liveTurn.endedAt = now
  }
  for (const turn of turns) {
    for (let i = 0; i < turn.items.length; i++) {
      const nextAt = turn.items[i + 1]?.at ?? turn.endedAt
      turn.items[i].durationMs = Math.max(0, nextAt - turn.items[i].at)
    }
  }
  return turns
})

/** 当前视口所在轮次：最后一个"顶部已越过容器上沿"的 user 锚点。 */
const activeTurnId = ref('')

function syncActiveTurn(): void {
  const list = listRef.value
  const turns = traceTurns.value
  if (!list || !turns.length) return
  const listTop = list.getBoundingClientRect().top
  let current = turns[0].id
  for (const turn of turns) {
    const el = document.getElementById(`msg-${turn.id}`)
    if (!el) continue
    if (el.getBoundingClientRect().top - listTop <= 96) current = turn.id
    else break
  }
  activeTurnId.value = current
}

/** 点击刻度/轨迹条目：把该轮 user 消息滚到容器顶部附近。 */
function scrollToTurn(id: string): void {
  const list = listRef.value
  const el = document.getElementById(`msg-${id}`)
  if (!list || !el) return
  const top = el.getBoundingClientRect().top - list.getBoundingClientRect().top + list.scrollTop
  list.scrollTo({ top: Math.max(0, top - 12), behavior: 'smooth' })
  activeTurnId.value = id
}

/** 从轨迹页定位：先切回对话（轨迹视图下 chat-list 不参与布局），再滚动。 */
function locateTurn(id: string): void {
  viewMode.value = 'chat'
  void nextTick(() => scrollToTurn(id))
}

const placeholder = computed(() => {
  if (permission.value === 'readonly') {
    return '只读模式：可以提问与诊断，但不会展开任何变更确认卡'
  }
  return env.value === 'prod'
    ? '生产环境：只能只读诊断；任何变更都需要在确认卡片上确认'
    : '发消息或描述任务'
})

const canSend = computed(() => !!input.value.trim() && !streaming.value)

/** 空会话：只显示引导区，不堆系统问候语（首条消息落地后自动消失）。 */
const showWelcome = computed(() =>
  !streaming.value && projection.value.messages.length === 0)

const suggestions = [
  { title: '排查一次失败', desc: '读日志、定位原因、给出下一步', prompt: '最近一次失败的运行是什么原因？读日志给出结论和修复建议' },
  { title: '部署到测试环境', desc: '跑通流水线并在测试环境验证', prompt: '把当前项目部署到测试环境，失败就诊断并修复' },
  { title: '了解项目现状', desc: '流水线、环境与最近运行一屏概览', prompt: '介绍一下当前项目的流水线、环境状态和最近运行情况' },
]

function startWith(text: string): void {
  if (streaming.value) return
  input.value = text
  void send()
}

/** 次级视图统一收进「更多」，顶栏只保留对话一个主入口。 */
const secondaryLabels: Record<string, string> = {
  trace: '轨迹',
  capabilities: '能力',
  issues: '任务',
}
const secondaryLabel = computed(() =>
  viewMode.value === 'chat' ? '更多' : (secondaryLabels[viewMode.value] ?? '更多'))

/** 输入框自增高：1 行起，最高约 30vh，超出后内部滚动。 */
function resizeInput(): void {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, Math.round(window.innerHeight * 0.3))}px`
}

watch(input, () => void nextTick(resizeInput))

/** 项目名（空状态文案用，比裸 projectId 可读）。 */
const projectDisplayName = computed(() =>
  projects.value.find((p) => p.id === projectId.value)?.name ?? projectId.value)

function goPath(path: string): void {
  void router.push(path)
}

/** 上下文条里的「配置项目环境」入口。 */
function goSettings(): void {
  goPath('/settings?section=envs')
}
/** 项目下拉：与「设置 → 项目环境」共用同一份项目列表。 */
async function loadProjects(): Promise<void> {
  try {
    projects.value = await listProjects()
    if (projects.value.length && !projects.value.some((p) => p.id === projectId.value)) {
      appStore.projectId = projects.value[0].id
    }
  } catch {
    projects.value = []
  }
}

/** 读取「设置 → 项目环境」的有效配置：由悬浮卡自行加载，本视图不再关心。 */

// 内容变化时粘底滚动（流式增量/新消息都会触发）
watch(
  () => projection.value.messages.length + (projection.value.streamingTurn?.text.length ?? 0),
  () => {
    followNewContent()
    void nextTick(syncActiveTurn)
  },
)

// 出现待确认变更单时按执行策略处理：自动展开 / 仅提示 / 只读不展开
watch(pendingChangeId, (id) => {
  if (id && permission.value === 'auto') openApprovalCard()
})

// 错误不弹 toast：reducer 已把 error 事件写进时间线（内联色块），双提示会重复。
// 这里仅保留 error 状态用于调试日志。
watch(error, (msg) => {
  if (msg) console.warn('[chat] stream error:', msg)
})

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value || composing.value) return
  input.value = ''
  // 从轨迹页发起的新问题：切回对话，才能看到流式回复
  viewMode.value = 'chat'
  appStore.touchSession(text)
  // user 消息进同一条消息列表：多轮问答的时间线顺序才对（之前 user 全排在 assistant 前面）
  appendMessage({ id: `u-${Date.now()}`, role: 'user', text, at: Date.now() })
  await sendChat(text)
  persistSession()
  void scrollToLatest({ force: true })
}

function onListScroll() {
  updateScrollState()
  syncActiveTurn()
}

async function copyMessage(text: string) {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板）')
  }
}

/** 把当前会话时间线（消息 + 工具结果）写进 localStorage，切回时恢复。 */
function persistSession(): void {
  saveHistory(sessionId.value, projection.value.messages, projection.value.tools)
}

/** 切换/首次进入会话：优先恢复本地历史，没有就是空会话。 */
function restoreSession(): void {
  const snapshot = loadHistory(sessionId.value)
  if (snapshot) hydrate(snapshot.messages, snapshot.tools)
  void nextTick(() => {
    scrollToLatest({ force: true })
    syncActiveTurn()
  })
}

/**
 * 增量落盘：不再只在 send 结束的那一刻写一次 ——
 * 流式过程中每 400ms 存一次，刷新/关页/切走都不会把整轮对话丢掉。
 */
let persistTimer: number | null = null
watch(
  [() => projection.value.messages, () => projection.value.tools],
  () => {
    if (persistTimer) window.clearTimeout(persistTimer)
    persistTimer = window.setTimeout(() => {
      persistTimer = null
      persistSession()
    }, 400)
  },
)

onUnmounted(() => {
  if (persistTimer) window.clearTimeout(persistTimer)
})

function resetSession() {
  reset()
  approvalCardOpen.value = false
  appStore.newSession()
}

watch(sessionId, () => {
  reset()
  approvalCardOpen.value = false
  restoreSession()
})

onMounted(async () => {
  await loadProjects()
  restoreSession()
  void nextTick(() => {
    resizeInput()
    scrollToLatest({ force: true })
  })
})
</script>

<template>
  <div class="chat-page">
    <PageHeader
      title="助手"
      context
      context-env
    >
      <template #actions>
        <!-- 会话累计费用：本地按 usage 累加，悬浮看命中/未命中/输出明细 -->
        <el-tooltip
          v-if="sessionCost.calls > 0"
          placement="bottom-end"
          :show-after="100"
          popper-class="session-cost-tip"
        >
          <template #content>
            <div class="tip-row">
              <span>命中输入</span>
              <span class="mono">{{ formatCost(sessionCost.inputHit) }}</span>
            </div>
            <div class="tip-row">
              <span>未命中输入</span>
              <span class="mono">{{ formatCost(sessionCost.inputMiss) }}</span>
            </div>
            <div class="tip-row">
              <span>输出</span>
              <span class="mono">{{ formatCost(sessionCost.output) }}</span>
            </div>
            <div class="tip-row tip-total">
              <span>{{ sessionCost.calls }} 次调用</span>
              <span class="mono">
                {{ formatTokens(sessionCost.hitTokens + sessionCost.missTokens) }} in
                / {{ formatTokens(sessionCost.completionTokens) }} out
              </span>
            </div>
          </template>
          <span class="session-cost mono">
            <el-icon class="cost-icon"><Coin /></el-icon>
            累计 {{ formatCost(sessionCost.total) }}
          </span>
        </el-tooltip>
        <button
          type="button" class="icon-btn" title="设置（模型 / 环境）"
          @click="goPath('/settings?section=models')"
        >
          <el-icon><Setting /></el-icon>
        </button>
      </template>
    </PageHeader>

    <!-- 视图切换 + 环境状态条：对话/轨迹共用一行工具区，状态条可展开看中间件明细 -->
    <div class="topbar">
      <div class="view-switch">
        <button
          type="button" class="switch-tab" :class="{ on: viewMode === 'chat' }"
          @click="viewMode = 'chat'"
        >
          对话
        </button>
        <el-dropdown trigger="click" @command="(cmd: string) => (viewMode = cmd as 'chat' | 'trace' | 'capabilities' | 'issues')">
          <button type="button" class="switch-tab more-tab" :class="{ on: viewMode !== 'chat' }">
            {{ secondaryLabel }}
            <span v-if="viewMode === 'capabilities' && selectedSkills.length + selectedMcp.length" class="tab-badge mono">
              {{ selectedSkills.length + selectedMcp.length }}
            </span>
            <span v-else-if="viewMode === 'issues' && issueCount" class="tab-badge mono">{{ issueCount }}</span>
            <el-icon><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="trace">轨迹</el-dropdown-item>
              <el-dropdown-item command="capabilities">能力</el-dropdown-item>
              <el-dropdown-item command="issues">任务</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <EnvironmentStatusBar
        :project-id="projectId"
        :env="env"
        :project-name="projectDisplayName"
        :streaming="streaming"
        @navigate="goSettings"
      />
    </div>

    <!-- 轨迹视图：时间轴 + 轮次列表 + 详情/系统提示词 -->
    <ChatTraceView
      v-if="viewMode === 'trace'"
      :turns="traceTurns"
      :active-id="activeTurnId"
      :tools="projection.tools"
      :project-id="projectId"
      :env="env"
      :streaming="streaming"
      @locate="locateTurn"
    />

    <!-- 能力视图：按会话选择 Skill / MCP，与轨迹同级 -->
    <ChatCapabilitiesView
      v-if="viewMode === 'capabilities'"
      :skill-options="skillOptions"
      :mcp-options="mcpOptions"
      :skills="selectedSkills"
      :mcp="selectedMcp"
      :ready="capsReady"
      :loading="capsLoading"
      @update:skills="selectedSkills = $event"
      @update:mcp="selectedMcp = $event"
      @refresh="refreshCapabilities"
    />

    <!-- 任务视图：平台与 Git 双向同步的 Issue 工单（Agent 工具同源） -->
    <ChatIssuesView
      v-if="viewMode === 'issues'"
      @count="issueCount = $event"
    />

    <!-- 消息区 + 右侧轨迹：chat-body 提供定位参照，chat-list 仍是唯一滚动容器 -->
    <div v-show="viewMode === 'chat'" class="chat-body">
    <div ref="listRef" class="chat-list" @scroll.passive="onListScroll">
      <!-- 空会话引导：一句定位 + 三个可点任务，替代原来孤零零的一行问候 -->
      <div v-if="showWelcome" class="welcome">
        <span class="welcome-icon" aria-hidden="true">
          <el-icon><ChatDotRound /></el-icon>
        </span>
        <h2 class="welcome-title">今天要做什么？</h2>
        <p class="welcome-desc">
          {{ `${projectDisplayName} · ${envLabel(env)}。选一个常用场景，或直接在下方描述你的问题。` }}
        </p>
        <div class="welcome-suggestions">
          <button
            v-for="s in suggestions" :key="s.title" type="button" class="suggestion"
            @click="startWith(s.prompt)"
          >
            <span class="suggestion-main">
              <strong>{{ s.title }}</strong>
              <small>{{ s.desc }}</small>
            </span>
            <el-icon><CaretRight /></el-icon>
          </button>
        </div>
      </div>

      <ConversationTimeline
        v-if="!showWelcome"
        v-model:card-open="approvalCardOpen"
        :projection="projection"
        :streaming="streaming"
        @copy="copyMessage"
      >
        <template #change-action>
          <button
            type="button" class="tip-btn"
            :disabled="permission === 'readonly'"
            @click="openApprovalCard"
          >
            {{ permission === 'readonly' ? '只读模式：不展开确认卡' : '展开确认卡片' }}
            <el-icon><CaretRight /></el-icon>
          </button>
        </template>
      </ConversationTimeline>

      <!-- 变更确认卡（change.created 事件驱动，替代正则抠单号） -->
      <ApprovalRequestCard
        v-if="pendingChangeId && approvalCardOpen"
        ref="approvalCard"
        :change-id="pendingChangeId"
        class="chat-approval"
        @applied="() => followNewContent()"
        @dismiss="approvalCardOpen = false"
      />
    </div>

      <!-- 右侧轨迹：细刻度定位回答，面板看耗时/步数/工具调用 -->
      <ChatTraceRail
        :turns="traceTurns"
        :active-id="activeTurnId"
        :streaming="streaming"
        @locate="scrollToTurn"
        @open-trace="viewMode = 'trace'"
      />
    </div>

    <!-- 输入区：自然撑开在底部，不 fixed / 不 sticky；任务页不涉及对话，隐藏以留出列表高度 -->
    <div v-if="viewMode !== 'issues'" class="composer-wrap">
      <div class="composer">
        <textarea
          ref="inputRef"
          v-model="input"
          class="composer-input"
          rows="1"
          :placeholder="placeholder"
          @compositionstart="composing = true"
          @compositionend="composing = false"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <div class="composer-bar">
          <div class="bar-left">
            <button type="button" class="chip-btn" disabled title="附件功能待接入">
              <el-icon><Paperclip /></el-icon>
            </button>
            <span class="bar-scope mono" :title="projectDisplayName">
              {{ envText }}
            </span>
          </div>
          <div class="bar-right">
            <span v-if="streaming || elapsedMs" class="metric mono">
              {{ (elapsedMs / 1000).toFixed(1) }}s · {{ tokens }} ev
            </span>
            <button
              v-if="streaming"
              type="button"
              class="send-btn stop"
              title="停止生成"
              @click="stop"
            >
              停止
            </button>
            <button
              v-else
              type="button"
              class="send-btn"
              :class="{ ready: canSend }"
              :disabled="!canSend"
              title="发送"
              @click="send"
            >
              <el-icon><Promotion /></el-icon>
            </button>
          </div>
        </div>
        <!-- 状态条内置在输入框底部：上下文 / 权限 / 工作树 / 模型，浮层向上展开 -->
        <div class="composer-status">
          <ChatStatusStrip
            :project-id="projectId"
            :worktree="appStore.worktree"
            :context="contextUsage"
            :permission="permission"
            @update:permission="appStore.setPermission($event)"
            @update:worktree="appStore.setWorktree($event)"
            @new-session="resetSession"
            @navigate="goPath"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  position: relative;
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  /* 会话区用白纸画布（DSH 的做法）：侧栏浅灰、正文纯白，层级靠底色差而不是边框 */
  background: var(--surface-raised);
}

/* ---- Header 右侧 ---- */
.icon-btn {
  display: flex;
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
}
.icon-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

/* 会话累计费用 chip：等宽小字，贴着设置键 */
.session-cost {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  height: 24px;
  padding: 0 8px;
  font-size: 11px;
  color: var(--ink-muted);
  white-space: nowrap;
  cursor: default;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: 999px;
  transition: color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.session-cost:hover {
  color: var(--ink-soft);
  border-color: var(--line);
}
.cost-icon {
  font-size: 12px;
  color: var(--ink-faint);
}

/* ---- 工具区：左侧视图切换，右侧环境状态条 ---- */
.topbar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px 0 24px;
  border-bottom: 1px solid var(--line-soft);
}
.view-switch {
  display: flex;
  flex: none;
  gap: 16px;
}
.switch-tab {
  padding: 9px 1px 10px;
  font-family: inherit;
  font-size: var(--text-sm);
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 1.5px solid transparent;
  transition: color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.switch-tab:hover {
  color: var(--ink-muted);
}
.switch-tab.on {
  font-weight: 600;
  color: var(--ink);
  border-bottom-color: var(--accent);
}
.tab-badge {
  display: inline-block;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  margin-left: 4px;
  font-size: 9.5px;
  line-height: 15px;
  color: var(--ink-muted);
  text-align: center;
  background: var(--surface-sunken);
  border-radius: 8px;
}

/* ---- 消息区：只有这里滚动；正文明暗靠白纸画布，不加分隔线 ---- */
.chat-body {
  position: relative;
  display: flex;
  flex: 1 1 0;
  min-height: 0;
}
.chat-list {
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  padding: 0 24px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.chat-approval {
  max-width: 45rem;
  margin: 0 auto 24px;
}

/* ---- 空会话引导：在可视区里垂直居中，内容超出时自然从顶部滚动 ---- */
.welcome {
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  max-width: 45rem;
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
/* 一张卡 = 一个常用场景：标题 + 一句说明，点一下直接开跑 */
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
.more-tab {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 展开确认卡片按钮（在 ConversationTimeline 的 change-action 插槽里） */
.tip-btn {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 2px;
  height: 26px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 12px;
  color: var(--warn);
  cursor: pointer;
  background: rgb(217 164 65 / 10%);
  border: 1px solid rgb(217 164 65 / 30%);
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease;
}
.tip-btn:hover {
  background: rgb(217 164 65 / 18%);
}
.tip-btn:disabled {
  cursor: not-allowed;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-color: var(--line-soft);
}

/* ---- 输入区：白底浮起的 composer，底部一行工具条 ---- */
.composer-wrap {
  flex: none;
  padding: 0 24px 14px;
}
.composer {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-width: 45rem;
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
/* 输入框：原生 textarea，高度由 resizeInput 按内容算，最多 30vh 后内部滚动 */
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
.chip-btn {
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
}
.chip-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
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
/* 环境选择：并入输入区左下的范围声明，不再占用正文上方的独立信息行 */
.scope-env {
  width: 88px;
}
.scope-env :deep(.el-select__wrapper) {
  min-height: 24px;
  padding: 0 8px;
  font-size: 11.5px;
  color: var(--ink-muted);
  background: transparent;
  box-shadow: none;
}
.scope-env :deep(.el-select__wrapper:hover) {
  background: var(--surface-hover);
}
/* 状态条内置：发丝线与输入区分隔，浮层向上展开 */
.composer-status {
  margin: 0 -12px -6px;
  padding: 2px 8px 2px 10px;
  border-top: 1px solid var(--line-soft);
}
.composer :deep(.status-strip) {
  max-width: none;
  margin: 0;
}
.composer :deep(.strip-row) {
  padding: 0;
}
.permission-select {
  width: 104px;
}
.permission-select :deep(.el-select__wrapper) {
  min-height: 26px;
  padding: 0 8px;
  font-size: 12px;
  background: transparent;
  box-shadow: none;
}
.permission-select :deep(.el-select__wrapper:hover) {
  background: var(--surface-hover);
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
/* 发送键：有内容才亮成蓝，空态是安静的暗块 */
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

@media (max-width: 720px) {
  .chat-list,
  .composer-wrap {
    padding-right: 14px;
    padding-left: 14px;
  }
  .composer-wrap {
    padding-bottom: calc(10px + env(safe-area-inset-bottom));
  }
  .metric {
    display: none;
  }
  .session-cost {
    display: none;
  }
}

/* 手机：底栏工具条空间有限，范围说明让位给环境选择与发送按钮 */
@media (max-width: 480px) {
  .bar-scope {
    display: none;
  }
  .composer {
    padding: 10px 10px 6px 12px;
  }
  .welcome {
    padding-right: 4px;
    padding-left: 4px;
  }
}
</style>

<!-- Tooltip 内容经 teleport 挂到 body，scoped 样式到不了，这里单独定义 -->
<style>
.session-cost-tip.el-popper {
  max-width: 240px;
}
.session-cost-tip .tip-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  font-size: 11.5px;
  line-height: 1.9;
}
.session-cost-tip .tip-total {
  padding-top: 4px;
  margin-top: 3px;
  color: rgb(255 255 255 / 62%);
  border-top: 1px solid rgb(255 255 255 / 14%);
}
</style>
