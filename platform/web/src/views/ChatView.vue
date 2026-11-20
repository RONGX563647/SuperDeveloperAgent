<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown, CaretRight, ChatDotRound, Coin, Loading, Paperclip, Promotion, Setting,
} from '@element-plus/icons-vue'
import { appStore, type PermissionMode } from '../stores/app'
import type { ChatMessage, TraceTurn } from '../types'
import { emptyAgentProjection } from '../state/agentEventReducer'
import { useFollowScroll } from '../composables/useFollowScroll'
import { getSessionChat, type SessionChatEntry } from '../state/sessionChat'
import { listProjects, type ProjectRow } from '../api/project'
import {
  listConversations, updateConversation,
} from '../api/workspace'
import { getSessionUsage } from '../api/settings'
import { envLabel } from '../utils/pipelineLabels'
import { formatCost, formatTokens, sumCost } from '../utils/deepseekPricing'
import ConversationTimeline from '../components/chat/ConversationTimeline.vue'
import ChatTraceRail from '../components/chat/ChatTraceRail.vue'
import ChatCapabilitiesView from '../components/chat/ChatCapabilitiesView.vue'
import { useChatCapabilities } from '../composables/useChatCapabilities'
import ChatTraceView from '../components/chat/ChatTraceView.vue'
import ApprovalRequestCard from '../components/chat/ApprovalRequestCard.vue'
import EnvBindingCard from '../components/chat/EnvBindingCard.vue'
import ChatStatusStrip from '../components/chat/ChatStatusStrip.vue'
import EnvironmentStatusBar from '../components/chat/EnvironmentStatusBar.vue'
import PageHeader from '../layouts/components/PageHeader.vue'
import { uploadIssueImage } from '../api/issues'
import { imageMarkdown, insertAt, isImageFile } from '../utils/chatImage'

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

/** 视图模式：对话 / 轨迹 / 能力，三个平级页面共用底部输入区；任务已移入工作区。 */
const viewMode = ref<'chat' | 'trace' | 'capabilities'>('chat')

/** 项目列表：只用于显示项目名与默认项目兜底（下拉已移除）。 */
const projects = ref<ProjectRow[]>([])

/**
 * 项目/环境切换已统一到顶栏 ContextSwitcher（appStore 是唯一真相），
 * 输入区只保留一句当前上下文提示，不再维护第二套下拉。
 */
const envText = computed(() => envLabel(env.value))

const input = ref('')

/**
 * 输入草稿按会话保存到 localStorage：切会话、跳设置再回来都不会丢字。
 * 发送成功后清空；空草稿直接删除键，不留下无意义记录。
 */
const DRAFT_PREFIX = 'agent.chat.draft.'
const DRAFT_LIMIT = 20_000
let draftTimer: number | null = null

function persistDraftFor(id: string, text: string): void {
  if (!id) return
  try {
    if (text.trim()) localStorage.setItem(DRAFT_PREFIX + id, text.slice(0, DRAFT_LIMIT))
    else localStorage.removeItem(DRAFT_PREFIX + id)
  } catch {
    // 隐私模式下 localStorage 可能不可写：草稿仅保留在内存
  }
}

function persistDraft(): void {
  persistDraftFor(sessionId.value, input.value)
}

function restoreDraft(): void {
  const id = sessionId.value
  if (!id) {
    input.value = ''
    return
  }
  try {
    input.value = localStorage.getItem(DRAFT_PREFIX + id) ?? ''
  } catch {
    input.value = ''
  }
}

function clearDraft(): void {
  persistDraftFor(sessionId.value, '')
}

/** 执行策略提升到全局 store（本地持久化）；三种模式实际影响确认卡的展开行为。 */
const permission = computed<PermissionMode>({
  get: () => appStore.permission,
  set: (value) => appStore.setPermission(value),
})
/** 中文输入法组合标记：组合期间 Enter 不应触发发送。 */
const composing = ref(false)

/**
 * 会话状态按 sessionId 常驻（state/sessionChat）：切换会话只是换绑实例，
 * 后台运行中的读流不会被 abort，切回来能接着看到输出。
 */
const chatEntry = shallowRef<SessionChatEntry | null>(null)
const EMPTY_PROJECTION = emptyAgentProjection()

/** 本会话选择的模型（null = 设置页默认）；随会话保存，不修改全局默认。 */
const modelSelection = shallowRef<{ connectionId: string | null, modelName: string | null }>({
  connectionId: null,
  modelName: null,
})

function syncChatEntry(): void {
  const id = sessionId.value
  if (!id) return
  const entry = getSessionChat(id, {
    projectId: projectId.value,
    env: env.value,
    worktree: appStore.worktree,
    skills: capsReady.value ? selectedSkills.value : undefined,
    mcpServers: capsReady.value ? selectedMcp.value : undefined,
    modelConnectionId: modelSelection.value.connectionId,
    modelName: modelSelection.value.modelName,
  })
  entry.context.projectId = projectId.value
  entry.context.env = env.value
  entry.context.worktree = appStore.worktree
  entry.context.modelConnectionId = modelSelection.value.connectionId
  entry.context.modelName = modelSelection.value.modelName
  if (capsReady.value) {
    entry.context.skills = [...selectedSkills.value]
    entry.context.mcpServers = [...selectedMcp.value]
  }
  chatEntry.value = entry
}

watch(
  [
    sessionId, () => appStore.projectId, () => appStore.env, () => appStore.worktree,
    capsReady, selectedSkills, selectedMcp, modelSelection,
  ],
  syncChatEntry,
  { immediate: true },
)

/** 切换会话时恢复服务端保存的模型选择与 token 累计。 */
async function loadSessionMeta(): Promise<void> {
  const id = sessionId.value
  if (!id || !projectId.value) return
  try {
    const rows = await listConversations(projectId.value, true)
    const row = rows.find((item) => item.id === id)
    modelSelection.value = {
      connectionId: row?.modelConnectionId ?? null,
      modelName: row?.modelName ?? null,
    }
  } catch {
    // 新会话还没在服务端登记：保持当前本地选择
  }
  if (chatEntry.value && chatEntry.value.chat.usage.value.calls === 0) {
    try {
      const usage = await getSessionUsage(id)
      chatEntry.value.chat.applyUsage(usage.totals)
    } catch {
      // 没有用量记录（首轮尚未发生）时忽略
    }
  }
}

watch([sessionId, () => appStore.projectId], () => void loadSessionMeta())

/** 选择模型：只写本会话；服务端有新会话时忽略 400。 */
function chooseModel(value: { connectionId: string | null, modelName: string | null }): void {
  modelSelection.value = value
  const id = sessionId.value
  if (!id) return
  void updateConversation(id, {
    modelConnectionId: value.connectionId,
    modelName: value.modelName,
  }).catch(() => {
    // 新会话尚未登记：首条消息会由服务端一起保存
  })
}

const projection = computed(() => chatEntry.value?.chat.projection.value ?? EMPTY_PROJECTION)
const streaming = computed(() => chatEntry.value?.chat.streaming.value ?? false)
const elapsedMs = computed(() => chatEntry.value?.chat.elapsedMs.value ?? 0)
const tokens = computed(() => chatEntry.value?.chat.tokens.value ?? 0)
/** 本会话真实 token 累计（所有模型）。 */
const sessionUsage = computed(() => chatEntry.value?.chat.usage.value ?? {
  calls: 0, inputTokens: 0, cachedTokens: 0, outputTokens: 0, totalTokens: 0,
})
const error = computed(() => chatEntry.value?.chat.error.value ?? '')
const pendingChangeId = computed(() => chatEntry.value?.chat.pendingChangeId.value ?? null)
const pendingBindingId = computed(() => chatEntry.value?.chat.pendingBindingId.value ?? null)

function sendChat(text: string): Promise<void> {
  return chatEntry.value?.chat.send(text) ?? Promise.resolve()
}

function stop(): void {
  chatEntry.value?.chat.stop()
}

/** Enter 发送 / Shift+Enter 换行；输入法组合中不拦截，避免破坏候选词上屏。 */
function onEnter(event: KeyboardEvent): void {
  if (event.isComposing || composing.value) return
  event.preventDefault()
  void send()
}

/** Esc 停止当前生成（输入法组合中交给输入法自己处理）。 */
function onEscape(): void {
  if (composing.value || !streaming.value) return
  stop()
}

/** 会话切换后把焦点还给输入框（触屏设备不主动弹键盘）。 */
function focusComposer(): void {
  if (!window.matchMedia?.('(pointer: fine)').matches) return
  inputRef.value?.focus()
}

function appendMessage(message: ChatMessage): void {
  chatEntry.value?.chat.appendMessage(message)
}

/** 会话累计费用（仅 deepseek 系列会产生；为 0 时不展示 chip）。 */
const sessionCost = computed(() => projection.value.sessionCost)

const listRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const approvalCard = ref<InstanceType<typeof ApprovalRequestCard> | null>(null)
/** 变更确认卡是否展开（由 change.created 事件自动展开；dismiss 收起但不丢 pendingChangeId）。 */
const approvalCardOpen = ref(false)

/* ---------------------------------------------------------------- 图片上传 */

/**
 * 会话里贴图：上传后以 Markdown 图片语法插到光标处。
 *
 * <p>为什么走 Markdown 而不是单独的消息类型：正文本来就是 Markdown，
 * 模型能直接「看到」这个地址并据此回答（例如让它看图排查问题），
 * 也不需要为图片再发明一套消息协议。
 */
const uploading = ref(false)

function pickImage(): void {
  fileInputRef.value?.click()
}

async function onFilePick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许连续选同一张图
  if (file) await insertImage(file)
}

async function insertImage(file: File): Promise<void> {
  if (!isImageFile(file.type)) {
    ElMessage.warning('只能上传图片')
    return
  }
  uploading.value = true
  try {
    const url = await uploadIssueImage(file)
    const el = inputRef.value
    // 插到光标处，而不是无条件追加到末尾：用户常常写了一半再补图
    const result = insertAt(input.value, imageMarkdown(url, file.name), el?.selectionStart)
    input.value = result.text
    await nextTick()
    el?.focus()
    el?.setSelectionRange(result.caret, result.caret)
    ElMessage.success('图片已插入')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '图片上传失败')
  } finally {
    uploading.value = false
  }
}

/** 粘贴图片：直接从剪贴板上传（截图后 Ctrl+V 是最高频的用法）。 */
function onPasteImage(event: ClipboardEvent): void {
  const items = event.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (!isImageFile(item.type)) continue
    const file = item.getAsFile()
    if (!file) continue
    event.preventDefault()
    void insertImage(file)
    return
  }
}

/** 拖入图片。 */
function onDropImage(event: DragEvent): void {
  const file = event.dataTransfer?.files?.[0]
  if (!file || !isImageFile(file.type)) return
  event.preventDefault()
  void insertImage(file)
}

/** 上下文窗口估算：服务端暂不回精确 token，状态条里会明确标注为估算值。 */const CONTEXT_LIMIT = 128_000
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

/**
 * 确认卡点完就不再需要用户手打「继续」：把结果直接回灌给 Agent，让它接着跑下一步。
 *
 * <p>仍在流式输出时无法并发再发一条消息，先把回执排队，等本轮结束自动回灌；
 * 失败回执同样走这里（原来只弹 toast，Agent 永远等不到结果）。
 */
const queuedFeedback = ref('')

function continueAfterUserAction(
  prompt: string,
  done = '已确认，Agent 继续执行',
  level: 'success' | 'warning' = 'success',
): void {
  if (streaming.value) {
    queuedFeedback.value = prompt
    ElMessage.info('当前回复还在生成，结束后会自动把结果回传给 Agent')
    return
  }
  approvalCardOpen.value = false
  ElMessage[level](done)
  void sendChat(prompt)
}

// 流式输出结束后补发排队中的确认结果（成功/失败都适用）
watch(streaming, (busy) => {
  if (busy || !queuedFeedback.value) return
  const prompt = queuedFeedback.value
  queuedFeedback.value = ''
  approvalCardOpen.value = false
  ElMessage.success('已将确认结果回传给 Agent')
  void sendChat(prompt)
})

/** 变更单应用成功（补丁已提交到分支）：让 Agent 接着按新提交重新部署。 */
function onApprovalApplied(result: {
  changeId: string, action: string, status: string, durationMs: number
}): void {
  followNewContent()
  chatEntry.value?.chat.clearPendingChange(result.changeId)
  continueAfterUserAction(
    `用户已在对话里确认并应用变更 ${result.changeId}（${result.action} → ${result.status}）。`
    + '请继续后续步骤：需要时用 git_status / git_commits 查看最新提交 sha，并用该 sha 重新运行部署。',
  )
}

/** 变更单执行失败：把原因回灌给 Agent，避免"卡片点了没反应"的静默失败。 */
function onApprovalFailed(result: { changeId: string; reason: string }): void {
  continueAfterUserAction(
    `用户在该变更卡（${result.changeId}）上的操作失败了：${result.reason}。`
    + '请不要假设变更已生效或已确认；判断是重新登记（核对 kind / 参数）还是停止，并向用户说明。',
    '变更卡操作失败，已回传给 Agent',
    'warning',
  )
}

/** 环境绑定确认成功：复用该 binding_id 继续，不重新生成草案。 */
function onBindingConfirmed(bindingId: string): void {
  continueAfterUserAction(
    `用户已在对话里确认环境绑定 ${bindingId}（DRAFT → CONFIRMED）。`
    + '请直接复用该 binding_id 继续执行 deploy_run，不要重新生成草案。',
  )
}

const {
  isNearBottom, hasNewContent, updateScrollState, followNewContent, scrollToLatest,
} = useFollowScroll(listRef, { bottomThreshold: 40 })

/** 上翻阅读时出现「有新内容 / 回到底部」浮标，流式输出也不打断阅读。 */
function jumpToBottom(): void {
  scrollToLatest({ force: true, behavior: 'smooth' })
}

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
        // 入参/结果在派生时固化，避免渲染阶段反查 tools 表失败导致详情栏空白
        arguments: tool?.arguments,
        resultText: tool?.resultText,
        toolName: tool?.toolName,
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
let activeFrame: number | null = null

/** 滚动事件按帧合并：长会话下逐个 getBoundingClientRect 会拖慢滚动。 */
function syncActiveTurn(): void {
  if (activeFrame !== null) return
  activeFrame = requestAnimationFrame(() => {
    activeFrame = null
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
  })
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
  if (streaming.value) return '正在生成…可以先输入下一条，结束后发送'
  return env.value === 'prod'
    ? '生产环境：只能只读诊断；任何变更都需要在确认卡片上确认'
    : '发消息或描述任务（Enter 发送，Shift+Enter 换行）'
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
  void nextTick(focusComposer)
}

/** 三个平级视图直接铺开；任务看板已上移到工作区，会话页保持纯对话。 */
const viewTabs: { key: 'chat' | 'trace' | 'capabilities'; label: string }[] = [
  { key: 'chat', label: '对话' },
  { key: 'trace', label: '轨迹' },
  { key: 'capabilities', label: '能力' },
]

/** 输入框自增高：1 行起，最高约 30vh，超出后内部滚动。 */
function resizeInput(): void {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, Math.round(window.innerHeight * 0.3))}px`
}

watch(input, () => {
  void nextTick(resizeInput)
  if (draftTimer !== null) window.clearTimeout(draftTimer)
  draftTimer = window.setTimeout(() => {
    draftTimer = null
    persistDraft()
  }, 250)
})

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

// 内容变化时粘底滚动（正文 / 思考增量、新消息都会触发）
watch(
  () => [
    projection.value.messages.length,
    projection.value.streamingTurn?.text.length ?? 0,
    projection.value.streamingTurn?.thinking.length ?? 0,
  ].join(':'),
  () => {
    followNewContent()
    void nextTick(syncActiveTurn)
  },
)

// 出现待确认变更单时按执行策略处理：confirm/auto 直接展开卡片；只读模式只提示
watch(pendingChangeId, (id) => {
  if (id && permission.value !== 'readonly') openApprovalCard()
})

// 错误不弹 toast：reducer 已把 error 事件写进时间线（内联色块），双提示会重复。
// 这里仅保留 error 状态用于调试日志。
watch(error, (msg) => {
  if (msg) console.warn('[chat] stream error:', msg)
})

/** 真正提交一轮对话：追加 user 气泡并读流；草稿与输入框由调用方处理。 */
async function submit(text: string): Promise<void> {
  if (!text || streaming.value) return
  // 从轨迹页发起的新问题：切回对话，才能看到流式回复
  viewMode.value = 'chat'
  appStore.touchSession(text)
  // user 消息进同一条消息列表：多轮问答的时间线顺序才对（之前 user 全排在 assistant 前面）
  appendMessage({ id: `u-${Date.now()}`, role: 'user', text, at: Date.now() })
  // 落盘由 sessionChat 按会话负责：这里切走也不会把内容写进别的会话
  await sendChat(text)
  void scrollToLatest({ force: true })
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value || composing.value) return
  input.value = ''
  clearDraft()
  await submit(text)
}

/** 失败行重试：重发上一条问题（保留输入框里正在写的草稿）。 */
function retryLast(): void {
  if (streaming.value) return
  const lastUser = [...projection.value.messages]
    .reverse()
    .find((message) => message.role === 'user')
  if (!lastUser) return
  void submit(lastUser.text)
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

/** 新建会话时跳过「把空输入写回旧会话」的一步，保证旧草稿不被抹掉。 */
let skipPersistSession = ''

function resetSession() {
  approvalCardOpen.value = false
  if (draftTimer !== null) {
    window.clearTimeout(draftTimer)
    draftTimer = null
  }
  // 先把当前草稿存回旧会话：新建会话不应丢掉上一段没发出去的内容
  persistDraftFor(sessionId.value, input.value)
  skipPersistSession = sessionId.value
  input.value = ''
  // 只切到新会话：旧会话若仍在后台运行，让它继续跑完（与侧栏「新会话」一致）
  appStore.newSession()
  void nextTick(() => {
    resizeInput()
    scrollToLatest({ force: true })
    syncActiveTurn()
    focusComposer()
  })
}

/** 切换会话：先保存旧会话草稿，再恢复新会话草稿；后台读流不中止。 */
watch(sessionId, (next, prev) => {
  if (draftTimer !== null) {
    window.clearTimeout(draftTimer)
    draftTimer = null
  }
  if (prev && prev !== skipPersistSession) persistDraftFor(prev, input.value)
  skipPersistSession = ''
  approvalCardOpen.value = false
  restoreDraft()
  void nextTick(() => {
    resizeInput()
    scrollToLatest({ force: true })
    syncActiveTurn()
    focusComposer()
  })
})

onMounted(async () => {
  restoreDraft()
  await loadProjects()
  void loadSessionMeta()
  void nextTick(() => {
    resizeInput()
    scrollToLatest({ force: true })
    syncActiveTurn()
  })
})

onBeforeUnmount(() => {
  if (draftTimer !== null) {
    window.clearTimeout(draftTimer)
    draftTimer = null
  }
  persistDraft()
  if (activeFrame !== null) {
    cancelAnimationFrame(activeFrame)
    activeFrame = null
  }
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
        <!-- 会话累计 token：每次模型调用的 usage 累加，所有模型都统计 -->
        <el-tooltip
          v-if="sessionUsage.calls > 0"
          placement="bottom-end"
          :show-after="100"
          popper-class="session-cost-tip"
        >
          <template #content>
            <div class="tip-row">
              <span>输入</span>
              <span class="mono">{{ formatTokens(sessionUsage.inputTokens) }}</span>
            </div>
            <div class="tip-row">
              <span>其中缓存命中</span>
              <span class="mono">{{ formatTokens(sessionUsage.cachedTokens) }}</span>
            </div>
            <div class="tip-row">
              <span>输出</span>
              <span class="mono">{{ formatTokens(sessionUsage.outputTokens) }}</span>
            </div>
            <div class="tip-row tip-total">
              <span>{{ sessionUsage.calls }} 次调用</span>
              <span class="mono">合计 {{ formatTokens(sessionUsage.totalTokens) }}</span>
            </div>
          </template>
          <span class="session-cost mono">
            <el-icon class="cost-icon"><Coin /></el-icon>
            {{ formatTokens(sessionUsage.totalTokens) }} tokens
          </span>
        </el-tooltip>
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
      <div class="seg" role="tablist" aria-label="对话视图">
        <button
          v-for="tab in viewTabs" :key="tab.key" type="button"
          role="tab" :aria-selected="viewMode === tab.key"
          :class="{ on: viewMode === tab.key }"
          @click="viewMode = tab.key"
        >
          {{ tab.label }}
          <span
            v-if="tab.key === 'capabilities' && selectedSkills.length + selectedMcp.length"
            class="seg-count"
          >
            {{ selectedSkills.length + selectedMcp.length }}
          </span>
        </button>
      </div>
      <EnvironmentStatusBar
        :project-id="projectId"
        :env="env"
        :project-name="projectDisplayName"
        :streaming="streaming"
        :default-open="false"
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

    <!-- 任务视图已上移为工作区主视图，会话页不再承载任务看板 -->

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
        @retry="retryLast"
        @ask-select="submit"
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
        @applied="onApprovalApplied"
        @failed="onApprovalFailed"
        @dismiss="approvalCardOpen = false"
      />

      <!-- 环境绑定确认卡（env_mapping_draft 后由后端事件驱动；确认动作只走已登录用户接口） -->
      <EnvBindingCard
        v-if="pendingBindingId"
        :project-id="projectId"
        :binding-id="pendingBindingId"
        class="chat-approval"
        @confirmed="onBindingConfirmed"
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

      <!-- 上翻阅读时的新内容提示：流式输出不抢滚动，用户主动回到底部 -->
      <Transition name="scroll-pop">
        <button
          v-if="!isNearBottom || hasNewContent"
          type="button" class="scroll-bottom"
          :class="{ fresh: hasNewContent }"
          :title="hasNewContent ? '有新的输出，回到底部' : '回到底部'"
          @click="jumpToBottom"
        >
          <el-icon><ArrowDown /></el-icon>
          <span>{{ hasNewContent ? '有新内容' : '回到底部' }}</span>
        </button>
      </Transition>
    </div>

    <!-- 输入区：自然撑开在底部，不 fixed / 不 sticky -->
    <div class="composer-wrap">
      <div class="composer">
        <textarea
          ref="inputRef"
          v-model="input"
          class="composer-input"
          rows="1"
          :placeholder="placeholder"
          aria-label="对话输入框"
          @compositionstart="composing = true"
          @compositionend="composing = false"
          @keydown.enter.exact="onEnter"
          @keydown.esc="onEscape"
          @paste="onPasteImage"
          @dragover.prevent
          @drop.prevent="onDropImage"
        ></textarea>
        <div class="composer-bar">
          <div class="bar-left">
            <button
              type="button" class="icon-btn"
              :disabled="streaming || uploading"
              :title="uploading ? '图片上传中…' : '上传图片（也可直接粘贴或拖入）'"
              @click="pickImage"
            >
              <el-icon v-if="uploading" class="is-loading"><Loading /></el-icon>
              <el-icon v-else><Paperclip /></el-icon>
            </button>
            <input
              ref="fileInputRef" type="file" class="hidden-file" accept="image/*"
              @change="onFilePick"
            />
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
            :model-connection-id="modelSelection.connectionId"
            :model-name="modelSelection.modelName"
            :skill-options="skillOptions"
            :mcp-options="mcpOptions"
            :skills="selectedSkills"
            :mcp="selectedMcp"
            :caps-ready="capsReady"
            :caps-loading="capsLoading"
            @update:permission="appStore.setPermission($event)"
            @update:worktree="appStore.setWorktree($event)"
            @update:model="chooseModel($event)"
            @update:skills="selectedSkills = $event"
            @update:mcp="selectedMcp = $event"
            @refresh-capabilities="refreshCapabilities"
            @open-capabilities="viewMode = 'capabilities'"
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
/* 图标按钮 / 输入区 / 欢迎页使用全局原语（styles.css） */

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
  gap: var(--sp-6);
  padding: var(--sp-2) var(--page-gutter) var(--sp-3);
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
  padding: var(--sp-4) var(--page-gutter) 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.chat-approval {
  max-width: var(--reading-max);
  margin: 0 auto var(--sp-9);
}

/* 回到底部：贴消息区底部居中，不遮挡右侧轨迹刻度 */
.scroll-bottom {
  position: absolute;
  bottom: 12px;
  left: 50%;
  z-index: 8;
  display: inline-flex;
  gap: 5px;
  align-items: center;
  height: 28px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-soft);
  white-space: nowrap;
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  box-shadow: var(--shadow-2);
  transform: translateX(-50%);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease,
    border-color var(--dur-fast) ease;
}
.scroll-bottom:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.scroll-bottom.fresh {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: var(--accent);
}
.scroll-bottom.fresh::before {
  width: 5px;
  height: 5px;
  content: '';
  background: var(--accent);
  border-radius: 50%;
}
.scroll-pop-enter-active,
.scroll-pop-leave-active {
  transition: opacity var(--dur-fast) ease, transform var(--dur-fast) ease;
}
.scroll-pop-enter-from,
.scroll-pop-leave-to {
  opacity: 0;
  transform: translate(-50%, 6px);
}

/* ---- 空会话引导：布局与建议卡使用全局 .welcome / .suggestion ---- */

/* 展开确认卡片按钮（在 ConversationTimeline 的 change-action 插槽里） */
.tip-btn {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: var(--sp-1);
  height: var(--control-sm);
  padding: 0 var(--sp-5);
  font-family: inherit;
  font-size: var(--text-xs);
  color: var(--warn-ink);
  cursor: pointer;
  background: var(--warn-soft);
  border: 1px solid var(--warn-border);
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease;
}
.tip-btn:hover {
  background: var(--warn-soft-strong);
}
.tip-btn:disabled {
  cursor: not-allowed;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-color: var(--line-soft);
}

/* ---- 输入区：容器 / 输入框 / 工具条 / 发送键使用全局 .composer 原语 ---- */
/* 环境选择：并入输入区左下的范围声明 */
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
  margin: 0 calc(var(--sp-6) * -1) calc(var(--sp-3) * -1);
  padding: var(--sp-1) var(--sp-4) var(--sp-1) var(--sp-5);
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
  min-height: var(--control-sm);
  padding: 0 var(--sp-4);
  font-size: var(--text-xs);
  background: transparent;
  box-shadow: none;
}
.permission-select :deep(.el-select__wrapper:hover) {
  background: var(--surface-hover);
}

@media (max-width: 720px) {
  .chat-list {
    padding-right: var(--page-gutter-tight);
    padding-left: var(--page-gutter-tight);
  }
  .composer-wrap {
    padding-right: var(--page-gutter-tight);
    padding-left: var(--page-gutter-tight);
  }
  .session-cost {
    display: none;
  }
}

/* 隐藏的原生文件选择框：用图标按钮代理点击，避免默认按钮样式 */
.hidden-file {
  display: none;
}

/* 手机：底栏工具条空间有限，范围说明让位给环境选择与发送按钮 */
@media (max-width: 480px) {
  .welcome {
    padding-right: var(--sp-1);
    padding-left: var(--sp-1);
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
