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

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  push('user', text)
  appStore.touchSession(text)

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
    <div ref="listRef" class="chat-list">
      <div class="chat-column">
        <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
          <div v-if="m.role === 'step'" class="step-row">
            <el-tag size="small" type="info" effect="light" round>{{ m.label }}</el-tag>
            <span class="step-text">{{ m.text }}</span>
          </div>
          <div v-else-if="m.role === 'error'" class="bubble error">
            {{ m.text }}
          </div>
          <div v-else class="bubble" :class="{ user: m.role === 'user' }">
            {{ m.text || '…' }}
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
          @keydown.enter.exact.prevent="send"
        />
        <div class="composer-bar">
          <div class="bar-left">
            <button type="button" class="icon-btn" title="添加附件">
              <el-icon><Paperclip /></el-icon>
            </button>
            <el-select v-model="permission" size="small" class="permission-select">
              <el-option label="每次确认" value="confirm" />
              <el-option label="只读" value="readonly" />
              <el-option label="自动执行" value="auto" />
            </el-select>
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
.bubble {
  max-width: 78%;
  padding: 11px 15px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  color: var(--ink);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 14px;
}
.row.user .bubble {
  color: var(--ink);
  background: #f2f3f5;
  border-color: #eceef1;
}
.bubble.error {
  color: #c45656;
  background: #fef0f0;
  border-color: #f3c2c2;
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
