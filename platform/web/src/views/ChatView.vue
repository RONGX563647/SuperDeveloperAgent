<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Paperclip, Promotion, Setting, Cpu } from '@element-plus/icons-vue'
import { extractText, findChangeId, streamChat } from '../api/chat'
import { applyChange, confirmChange, getChange } from '../api/change'
import type { ChangeView, ChatMessage } from '../types'
import { appStore } from '../stores/app'
import PageHeader from '../layouts/components/PageHeader.vue'

/** 项目/环境/会话走全局 store，与侧栏共用同一份状态。 */
const projectId = computed(() => appStore.projectId)
const env = computed(() => appStore.env)
const sessionId = computed(() => appStore.sessionId)

const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const elapsedMs = ref(0)
const listRef = ref<HTMLElement | null>(null)
const permission = ref<'readonly' | 'confirm' | 'auto'>('confirm')
let controller: AbortController | null = null
let timer: ReturnType<typeof setInterval> | null = null

const pendingChangeId = ref<string | null>(null)
const changeDialog = ref(false)
const change = ref<ChangeView | null>(null)
const password = ref('')
const confirmToken = ref<string | null>(null)
const applyBusy = ref(false)
const applyResult = ref<string | null>(null)

const placeholder = computed(() =>
  env.value === 'prod'
    ? '生产环境：只能只读诊断；任何变更都需要在确认卡片上确认'
    : '输入问题，例如：这个项目的流水线有哪些？/ 帮我看看 test 环境为什么起不来'
)

const tokens = ref(0)
/** 中文输入法组合标记：组合期间 Enter 不应触发发送。 */
const composing = ref(false)
/** 用户是否粘在底部：上翻看历史时暂停自动滚底，回到底部附近恢复。 */
const stickToBottom = ref(true)
/** 最后一条 assistant 消息（流式光标挂在它上面）。 */
const lastAssistantId = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'assistant') return messages.value[i].id
  }
  return ''
})

function push(role: ChatMessage['role'], text: string, label?: string) {
  messages.value.push({
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    role,
    text,
    label,
    at: Date.now()
  })
  void scrollToBottom()
}

async function scrollToBottom(force = false) {
  if (!force && !stickToBottom.value) return
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 滚动时判断是否仍在底部附近（±40px 内视为粘底）。 */
function onListScroll() {
  const el = listRef.value
  if (!el) return
  stickToBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 40
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

function formatTime(at: number): string {
  const d = new Date(at)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  // 中文输入法组合中不发送（compositionend 前 keydown 也会带 enter）
  if (composing.value) return
  input.value = ''
  push('user', text)
  appStore.touchSession(text)
  stickToBottom.value = true

  streaming.value = true
  elapsedMs.value = 0
  timer = setInterval(() => (elapsedMs.value += 100), 100)
  controller = new AbortController()

  const assistantId = `${Date.now()}-a`
  messages.value.push({ id: assistantId, role: 'assistant', text: '', at: Date.now() })

  try {
    await streamChat(
      { projectId: projectId.value, env: env.value, sessionId: sessionId.value, message: text },
      (event) => {
        // 后端用 event:error + data:{"message":…} 表示流式失败（如模型 401）
        if (event.type === 'error') {
          const msg = typeof event.payload?.message === 'string'
            ? event.payload.message
            : '对话流异常'
          throw new Error(msg)
        }
        const target = messages.value.find((m) => m.id === assistantId)
        if (!target) return
        const label = String(event.payload?.tool ?? '')
        const delta = extractText(event)
        if (label) push('step', label, '工具调用')
        if (delta) {
          target.text += delta
          tokens.value += 1
        }
      },
      controller.signal
    )
  } catch (error) {
    const target = messages.value.find((m) => m.id === assistantId)
    if (target && !target.text) target.text = '（连接中断）'
    push('error', error instanceof Error ? error.message : String(error))
  } finally {
    streaming.value = false
    controller = null
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    const found = findChangeId(messages.value.map((m) => m.text).join('\n'))
    if (found) pendingChangeId.value = found
    void scrollToBottom()
  }
}

function stop() {
  controller?.abort()
  streaming.value = false
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function reset() {
  if (streaming.value) stop()
  appStore.newSession()
  messages.value = []
  pendingChangeId.value = null
  confirmToken.value = null
  applyResult.value = null
  tokens.value = 0
}

async function openChange() {
  if (!pendingChangeId.value) return
  try {
    change.value = await getChange(pendingChangeId.value)
    confirmToken.value = null
    applyResult.value = null
    password.value = ''
    changeDialog.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载变更失败')
  }
}

async function doConfirm() {
  if (!change.value) return
  try {
    const result = await confirmChange(
      change.value.changeId,
      change.value.highRisk ? password.value : undefined
    )
    confirmToken.value = result.confirmToken
    ElMessage.success('已确认，令牌 5 分钟内有效')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '确认失败')
  }
}

async function doApply() {
  if (!change.value || !confirmToken.value) return
  applyBusy.value = true
  try {
    const result = await applyChange(change.value.changeId, confirmToken.value)
    applyResult.value = `${result.action} → ${result.status}（${result.durationMs} ms）`
    ElMessage.success(`变更执行完毕：${result.status}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '执行失败')
  } finally {
    applyBusy.value = false
  }
}

/** 切换会话时清空视图（会话内容当前仍为内存态）。 */
watch(sessionId, () => {
  if (streaming.value) stop()
  messages.value = []
  pendingChangeId.value = null
  void onMountedGreeting()
})

function onMountedGreeting() {
  push('assistant', '你好，我是本项目的研发运维助手。可以问我：流水线有哪些、环境状态如何、某段代码在哪。')
}

onMounted(() => {
  appStore.touchSession('')
  onMountedGreeting()
})
</script>

<template>
  <div class="chat-page">
    <PageHeader title="通用对话" :subtitle="`${projectId} · ${env}`">
      <template #actions>
        <span class="status-pill">
          <span class="dot" :class="streaming ? 'busy' : 'ok'" />
          {{ streaming ? '生成中' : '就绪' }}
        </span>
        <el-button size="small" text :icon="Setting">设置</el-button>
      </template>
    </PageHeader>

    <!-- 消息区：唯一滚动容器（min-height:0 防止被内容撑破） -->
    <div ref="listRef" class="chat-list" @scroll.passive="onListScroll">
      <div class="chat-column">
        <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
          <div v-if="m.role === 'step'" class="step-row">
            <el-tag size="small" type="info" effect="light" round>{{ m.label }}</el-tag>
            <span class="step-text">{{ m.text }}</span>
          </div>
          <div v-else-if="m.role === 'error'" class="bubble error">
            {{ m.text }}
          </div>
          <div v-else class="bubble-group" :class="{ user: m.role === 'user' }">
            <div class="bubble" :class="{ user: m.role === 'user', streaming: m.role === 'assistant' && streaming && m.id === messages[messages.length - 1]?.id && !m.text }">
              <span class="bubble-text">{{ m.text }}</span><span v-if="streaming && m.role === 'assistant' && m.id === lastAssistantId" class="caret" />
            </div>
            <div class="bubble-meta">
              <span class="mono meta-time">{{ formatTime(m.at) }}</span>
              <button
                v-if="m.text"
                type="button"
                class="meta-copy"
                title="复制"
                @click="copyMessage(m.text)"
              >
                <el-icon><CopyDocument /></el-icon>
              </button>
            </div>
          </div>
        </div>

        <div v-if="pendingChangeId" class="change-tip">
          <div class="tip-title">
            有变更待确认
          </div>
          <div class="mono tip-id">
            {{ pendingChangeId }}
          </div>
          <el-button type="warning" size="small" @click="openChange">
            查看确认卡片
          </el-button>
        </div>
      </div>
    </div>

    <!-- 输入区：自然撑开在底部，不 fixed / 不 sticky -->
    <div class="composer-wrap">
      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 8 }"
          resize="none"
          class="composer-input"
          :placeholder="placeholder"
          @compositionstart="composing = true"
          @compositionend="composing = false"
          @keydown.enter.exact.prevent="send"
        />
        <div class="composer-bar">
          <div class="bar-left">
            <el-tooltip content="附件功能待接入（占位）" placement="top">
              <button type="button" class="icon-btn" disabled title="附件功能待接入">
                <el-icon><Paperclip /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="执行策略：会话内工具调用的门禁偏好" placement="top">
              <el-select v-model="permission" size="small" class="permission-select">
                <el-option label="每次确认" value="confirm" />
                <el-option label="只读" value="readonly" />
                <el-option label="自动执行" value="auto" />
              </el-select>
            </el-tooltip>
          </div>
          <div class="bar-right">
            <span class="metric mono">
              <el-icon><Cpu /></el-icon>
              qwen-plus · {{ (elapsedMs / 1000).toFixed(1) }}s · {{ tokens }} ev
            </span>
            <el-button v-if="streaming" size="small" @click="stop">
              停止
            </el-button>
            <el-button
              type="primary"
              size="small"
              :icon="Promotion"
              :loading="streaming"
              :disabled="!input.trim()"
              @click="send"
            >
              发送
            </el-button>
          </div>
        </div>
      </div>
      <p class="composer-hint">
        Enter 发送 · Shift+Enter 换行 · 所有写操作需人工确认
      </p>
    </div>

    <el-dialog v-model="changeDialog" title="变更确认" width="560px">
      <div v-if="change" class="confirm-card">
        <div class="line"><span>变更单</span><b class="mono">{{ change.changeId }}</b></div>
        <div class="line"><span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b></div>
        <div class="line"><span>类型</span><b>{{ change.kind }}</b></div>
        <div class="line"><span>内容</span><b>{{ change.summary }}</b></div>
        <div class="line"><span>镜像 digest</span><b class="mono">{{ change.imageDigest ?? '—' }}</b></div>
        <div class="line"><span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 24) }}…</b></div>
        <div class="line">
          <span>风险</span>
          <el-tag :type="change.highRisk ? 'danger' : 'success'" size="small" effect="light" round>
            {{ change.highRisk ? '高危（需二次密码）' : '常规' }}
          </el-tag>
        </div>

        <el-input
          v-if="change.highRisk && !confirmToken"
          v-model="password"
          type="password"
          placeholder="请输入密码（账号体系接入前会失败关闭）"
          show-password
        />

        <el-alert
          v-if="confirmToken"
          type="success"
          :closable="false"
          show-icon
          title="确认令牌已签发（5 分钟内有效，一次性）"
          :description="confirmToken"
        />
        <el-alert
          v-if="applyResult"
          type="success"
          :closable="false"
          show-icon
          title="已带令牌执行"
          :description="applyResult"
        />
      </div>

      <template #footer>
        <el-button @click="changeDialog = false">关闭</el-button>
        <el-button :disabled="!!confirmToken" @click="doConfirm">确认变更</el-button>
        <el-button
          type="primary"
          :disabled="!confirmToken || applyBusy"
          :loading="applyBusy"
          @click="doApply"
        >
          带令牌执行
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

/* 消息区：只有这里滚动 */
.chat-list {
  flex: 1 1 0;
  min-height: 0;
  padding: 24px 20px 8px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
/* 内容居中收窄，避免超长行 */
.chat-column {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
  max-width: 56rem;
  margin: 0 auto;
}
.row {
  display: flex;
  min-width: 0;
}
.row.user {
  justify-content: flex-end;
}
.bubble-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  max-width: 78%;
}
.row.user .bubble-group {
  align-items: flex-end;
}
.bubble {
  padding: 11px 15px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--ink);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 14px;
}
.row.user .bubble {
  color: var(--ink);
  background: #f2f3f5;
  border-color: #eceef1;
  border-bottom-right-radius: 4px;
}
.row.assistant .bubble {
  border-bottom-left-radius: 4px;
}
.bubble.error {
  color: #c45656;
  background: #fef0f0;
  border-color: #f3c2c2;
}
/* 流式光标：跟随最后一条 assistant 消息 */
.caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -0.15em;
  background: var(--accent);
  animation: caret-blink 0.9s step-end infinite;
}
@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}
/* 气泡元信息：时间 + 复制，hover 才出现，不喧宾夺主 */
.bubble-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 18px;
  padding: 0 4px;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.bubble-group:hover .bubble-meta {
  opacity: 1;
}
.meta-time {
  font-size: 10px;
  color: #b0b6bd;
}
.meta-copy {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  color: #b0b6bd;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 5px;
}
.meta-copy:hover {
  color: var(--accent);
  background: var(--accent-soft);
}
.step-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}
.step-text {
  color: var(--ink-soft);
}
.change-tip {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  background: #fdf6ec;
  border: 1px solid #f5d3b8;
  border-radius: 12px;
}
.tip-title {
  font-size: 13px;
  font-weight: 600;
  color: #9a4514;
}
.tip-id {
  color: #9a4514;
  word-break: break-all;
}

/* 输入区：flex 自然撑开，绝不 fixed */
.composer-wrap {
  flex: none;
  padding: 8px 20px 14px;
  background: var(--panel);
}
.composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  max-width: 56rem;
  padding: 10px 12px 8px;
  margin: 0 auto;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: 0 1px 2px rgb(0 0 0 / 4%);
}
.composer:focus-within {
  border-color: #b9d3f7;
  box-shadow: 0 0 0 3px rgb(64 158 255 / 10%);
}
.composer-input :deep(.el-textarea__inner) {
  padding: 2px 4px;
  font-size: 14px;
  line-height: 1.7;
  background: transparent;
  border: none;
  box-shadow: none;
  /* 自适应高度，但最多不超过屏幕的 30% */
  max-height: 30vh;
  overflow-y: auto;
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
  gap: 8px;
  min-width: 0;
}
.icon-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 8px;
}
.icon-btn:hover {
  color: var(--accent);
  background: var(--accent-soft);
}
.permission-select {
  width: 108px;
}
.metric {
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  font-size: 11px;
  color: #a0a6ad;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.composer-hint {
  max-width: 56rem;
  margin: 6px auto 0;
  font-size: 11px;
  color: #b0b6bd;
  text-align: center;
}
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--ink-soft);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 999px;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #67c23a;
}
.dot.busy {
  background: var(--accent);
}
.confirm-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.confirm-card .line {
  display: flex;
  gap: 14px;
  font-size: 13px;
}
.confirm-card .line span {
  flex: none;
  width: 90px;
  color: var(--ink-soft);
}

@container workspace (max-width: 720px) {
  .bubble {
    max-width: 88%;
  }
  .metric {
    display: none;
  }
}
</style>
