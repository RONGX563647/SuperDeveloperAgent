<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CaretRight,
  CopyDocument,
  Cpu,
  Loading,
  Paperclip,
  Promotion,
  Setting,
  Select
} from '@element-plus/icons-vue'
import { extractText, findChangeId, streamChat } from '../api/chat'
import { applyChange, confirmChange, getChange } from '../api/change'
import type { ChangeView, ChatMessage } from '../types'
import { appStore } from '../stores/app'
import PageHeader from '../layouts/components/PageHeader.vue'

/**
 * 对话页 —— 视觉形态对齐 DSH：
 *
 * - assistant 不做气泡，直接是页面上的文本块（14px/1.85），最大宽度 46rem 居中偏左；
 * - 工具调用折叠成一行：`>_` 前缀 + 深色命令胶囊 + 状态标（已完成 / 进行中 / 失败）；
 * - user 消息是右侧的浅色软底块，不描边、不加重阴影；
 * - 底部输入是悬浮胶囊：上边框 + 内阴影，聚焦时蓝色 3px 环，DSH 的"桌面级输入"质感。
 *
 * 交互逻辑（SSE、IME 组合、粘底滚动、变更确认）与改造前完全一致，只换了皮。
 */
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
    : '发消息或描述任务，Enter 发送，Shift+Enter 换行'
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

/** 输入框是否为空 —— 决定发送键是蓝色的实心还是灰化的暗色。 */
const canSend = computed(() => !!input.value.trim() && !streaming.value)

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

/**
 * 工具调用行是否仍在跑。
 *
 * <p>判定条件：整体在流式，且该行之后还没有任何正文增量 —— 一旦后面跟了新的
 * assistant 正文，就说明这条命令已经返回并开始输出结果了。
 */
function stepRunning(message: ChatMessage): boolean {
  if (!streaming.value) return false
  const idx = messages.value.findIndex((m) => m.id === message.id)
  if (idx < 0) return false
  return !messages.value.slice(idx + 1).some((m) => m.role === 'assistant' && m.text)
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

  /**
   * 正文游标：当前这一轮回复里「正在续写」的 assistant 块 id。
   *
   * <p>不能靠 `reverse().find(role==='assistant')` 反查 —— 上一条历史消息（乃至开场欢迎语）
   * 也是 assistant，反查会命中它，正文就写串到旧块里。这里显式持有游标：
   * 遇到工具调用事件就把游标清空，下一个文本增量重新开一块，
   * DOM 顺序天然是「命令 → 输出 → 命令 → 输出」，而不是所有命令堆在正文上方。
   */
  let cursorId: string | null = null

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
        const label = String(event.payload?.tool ?? '')
        const delta = extractText(event)
        if (label) {
          push('step', label, '工具调用')
          cursorId = null
        }
        if (delta) {
          let target = cursorId ? messages.value.find((m) => m.id === cursorId) : undefined
          if (!target) {
            cursorId = `${Date.now()}-a${messages.value.length}`
            target = { id: cursorId, role: 'assistant', text: '', at: Date.now() }
            messages.value.push(target)
          }
          target.text += delta
          tokens.value += 1
        }
      },
      controller.signal
    )
  } catch (error) {
    const target = cursorId ? messages.value.find((m) => m.id === cursorId) : undefined
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
  onMountedGreeting()
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
        <span class="run-state" :class="streaming ? 'busy' : 'ok'">
          <span class="dot" />
          {{ streaming ? '生成中' : '就绪' }}
        </span>
        <button type="button" class="hdr-btn" title="新会话" @click="reset">
          新会话
        </button>
        <button type="button" class="icon-btn" title="设置（待接入）" disabled>
          <el-icon><Setting /></el-icon>
        </button>
      </template>
    </PageHeader>

    <!-- 消息区：唯一滚动容器（min-height:0 防止被内容撑破） -->
    <div ref="listRef" class="chat-list" @scroll.passive="onListScroll">
      <div class="chat-column">
        <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
          <!-- 工具调用：DSH 的单行折叠形态 -->
          <div v-if="m.role === 'step'" class="tool-line">
            <span class="tool-caret" aria-hidden="true">&gt;_</span>
            <span class="tool-cmd">{{ m.text }}</span>
            <span class="tool-state" :class="stepRunning(m) ? 'running' : 'done'">
              <el-icon v-if="stepRunning(m)" class="is-loading"><Loading /></el-icon>
              <el-icon v-else><Select /></el-icon>
              {{ stepRunning(m) ? '执行中' : '已完成' }}
            </span>
          </div>

          <!-- 错误：整块警示，不做气泡 -->
          <div v-else-if="m.role === 'error'" class="error-line">
            <span class="error-tag">中断</span>
            <span class="error-text">{{ m.text }}</span>
          </div>

          <template v-else>
            <!-- 用户消息：右侧软底块 -->
            <div v-if="m.role === 'user'" class="user-block">
              <div class="user-bubble">
                {{ m.text }}
              </div>
              <div class="msg-meta">
                <time class="meta-time mono">{{ formatTime(m.at) }}</time>
                <button type="button" class="meta-btn" title="复制" @click="copyMessage(m.text)">
                  <el-icon><CopyDocument /></el-icon>
                </button>
              </div>
            </div>

            <!-- 助手消息：白纸上的正文，无气泡无边框 -->
            <div v-else class="assistant-block">
              <div class="assistant-text">
                <span class="text">{{ m.text }}</span><span v-if="streaming && m.id === lastAssistantId" class="caret" />
              </div>
              <div v-if="m.text" class="msg-meta">
                <time class="meta-time mono">{{ formatTime(m.at) }}</time>
                <button type="button" class="meta-btn" title="复制" @click="copyMessage(m.text)">
                  <el-icon><CopyDocument /></el-icon>
                </button>
              </div>
            </div>
          </template>
        </div>

        <div v-if="pendingChangeId" class="change-tip">
          <div class="tip-main">
            <span class="tip-title">有变更待确认</span>
            <span class="mono tip-id">{{ pendingChangeId }}</span>
          </div>
          <button type="button" class="tip-btn" @click="openChange">
            查看确认卡片
            <el-icon><CaretRight /></el-icon>
          </button>
        </div>
      </div>
    </div>

    <!-- 输入区：自然撑开在底部，不 fixed / 不 sticky -->
    <div class="composer-wrap">
      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 8 }"
          resize="none"
          class="composer-input"
          :placeholder="placeholder"
          @compositionstart="composing = true"
          @compositionend="composing = false"
          @keydown.enter.exact.prevent="send"
        />
        <div class="composer-bar">
          <div class="bar-left">
            <button type="button" class="chip-btn" disabled title="附件功能待接入">
              <el-icon><Paperclip /></el-icon>
            </button>
            <el-select v-model="permission" size="small" class="permission-select" title="执行策略">
              <el-option label="完全权限" value="auto" />
              <el-option label="每次确认" value="confirm" />
              <el-option label="只读" value="readonly" />
            </el-select>
          </div>
          <div class="bar-right">
            <span class="metric mono">
              <el-icon><Cpu /></el-icon>
              qwen-plus
              <template v-if="streaming || elapsedMs">
                · {{ (elapsedMs / 1000).toFixed(1) }}s · {{ tokens }} ev
              </template>
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
          <el-tag :type="change.highRisk ? 'danger' : 'success'" size="small" effect="dark" round>
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

/* ---- Header 右侧 ---- */
.run-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  font-size: 11.5px;
  color: var(--ink-muted);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.run-state .dot {
  width: 5px;
  height: 5px;
  background: var(--ok);
  border-radius: 50%;
}
.run-state.busy .dot {
  background: var(--accent);
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}
.hdr-btn {
  height: 26px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.hdr-btn:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
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

/* ---- 消息区：只有这里滚动 ---- */
.chat-list {
  flex: 1 1 0;
  min-height: 0;
  padding: 8px 24px 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}
/* 内容收窄居中：正文行长控制在 46rem 内，长文本不横跨整屏 */
.chat-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-width: 54rem;
  min-height: 100%;
  padding: 0 0 24px clamp(0px, 2vw, 24px);
}
.row {
  display: flex;
  min-width: 0;
}
.row.user {
  justify-content: flex-end;
  margin-top: 22px;
}

/* 工具调用行：`>_` + 命令胶囊 + 状态 */
.tool-line {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
  padding: 3px 0;
  margin: 2px 0;
  font-size: 12px;
  color: var(--ink-muted);
}
.tool-caret {
  flex: none;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent-ink);
  opacity: 0.85;
}
.tool-cmd {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  padding: 2px 8px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
  background: var(--surface-hover);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.tool-state {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 3px;
  font-size: 11px;
}
.tool-state.done {
  color: var(--ok);
}
.tool-state.running {
  color: var(--accent-ink);
}

/* 错误行 */
.error-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 12px;
  margin: 6px 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--danger);
  background: var(--danger-soft);
  border-left: 2px solid var(--danger);
  border-radius: var(--radius-sm);
}
.error-tag {
  flex: none;
  font-size: 11px;
  font-weight: 600;
  color: var(--danger);
  letter-spacing: 0.04em;
}
.error-text {
  min-width: 0;
  word-break: break-word;
}

/* 用户块：右侧软底，圆角 14px，右下角收一点 */
.user-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  max-width: 80%;
  min-width: 0;
}
.user-bubble {
  max-width: 100%;
  padding: 10px 15px;
  font-size: 14px;
  line-height: 1.75;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: 20px;
}

/* 助手块：正文即页面 */
.assistant-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 0;
}
.assistant-text {
  font-size: 14.5px;
  line-height: 1.9;
  color: var(--ink);
  word-break: break-word;
}
.assistant-text .text {
  white-space: pre-wrap;
}
/* 流式光标：跟随最后一条 assistant 消息 */
.caret {
  display: inline-block;
  width: 6px;
  height: 1em;
  margin-left: 2px;
  vertical-align: -0.14em;
  background: var(--accent);
  border-radius: 1px;
  animation: caret-blink 1s step-end infinite;
}
@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}

/* 元信息：hover 才出现，不喧宾夺主 */
.msg-meta {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 18px;
  padding: 0 2px;
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.user-block:hover .msg-meta,
.assistant-block:hover .msg-meta {
  opacity: 1;
}
.meta-time {
  font-size: 10px;
  color: var(--ink-faint);
}
.meta-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  font-size: 11px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.meta-btn:hover {
  color: var(--ink);
  background: var(--surface-hover);
}

/* 变更提示：警示色块，不用黄底白字那种廉价感 */
.change-tip {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  margin-top: 14px;
  background: var(--warn-soft);
  border: 1px solid rgb(217 164 65 / 26%);
  border-radius: var(--radius-md);
}
.tip-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.tip-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--warn);
}
.tip-id {
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}
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

/* ---- 输入区：悬浮胶囊 ---- */
.composer-wrap {
  flex: none;
  padding: 0 24px 14px;
}
.composer {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-width: 54rem;
  padding: 10px 10px 8px 14px;
  margin: 0 auto;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-2), inset 0 0 0 1px var(--line-soft);
  transition: border-color var(--dur-fast) ease, box-shadow var(--dur-fast) ease;
}
.composer:focus-within {
  border-color: rgb(77 130 247 / 55%);
  box-shadow: var(--shadow-2), 0 0 0 3px rgb(77 130 247 / 12%);
}
.composer-input :deep(.el-textarea__inner) {
  max-height: 30vh;
  padding: 4px 2px;
  overflow-y: auto;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink);
  background: transparent;
  border: none;
  box-shadow: none;
}
.composer-input :deep(.el-textarea__inner::placeholder) {
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

/* 确认卡片 */
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
  color: var(--ink-muted);
}

@media (max-width: 720px) {
  .chat-list,
  .composer-wrap {
    padding-right: 14px;
    padding-left: 14px;
  }
  .metric {
    display: none;
  }
  .user-block {
    max-width: 88%;
  }
}
</style>
