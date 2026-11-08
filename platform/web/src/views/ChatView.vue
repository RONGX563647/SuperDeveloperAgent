<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, User } from '@element-plus/icons-vue'
import { extractText, findChangeId, streamChat } from '../api/chat'
import { applyChange, confirmChange, getChange } from '../api/change'
import type { ChangeView, ChatMessage } from '../types'

const projectId = ref('RY')
const env = ref('test')
const sessionId = ref<string>(newSessionId())
const messages = ref<ChatMessage[]>([])
const input = ref('')
const streaming = ref(false)
const listRef = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

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
    : '例如：这个项目的流水线有哪些？/ 帮我看看 test 环境为什么起不来'
)

function newSessionId(): string {
  return 's-' + Math.random().toString(36).slice(2, 10)
}

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
  streaming.value = true
  controller = new AbortController()

  const assistantId = `${Date.now()}-a`
  messages.value.push({ id: assistantId, role: 'assistant', text: '', at: Date.now() })
  // …与原实现一致：SSE 逐事件追加，详见 api/chat.ts
  try {
    await streamChat(
      { projectId: projectId.value, env: env.value, sessionId: sessionId.value, message: text },
      (event) => {
        const target = messages.value.find(m => m.id === assistantId)
        if (!target) return
        const label = String(event.payload?.tool ?? '')
        const text0 = extractText(event)
        if (label) {
          push('step', label, '工具调用')
        }
        if (text0) target.text += text0
      },
      controller.signal
    )
  } catch (error) {
    const target = messages.value.find(m => m.id === assistantId)
    if (target && !target.text) target.text = '（连接中断）'
    push('error', error instanceof Error ? error.message : String(error))
  } finally {
    streaming.value = false
    controller = null
    const found = findChangeId(messages.value.map(m => m.text).join('\n'))
    if (found) pendingChangeId.value = found
    void scrollToBottom()
  }
}

function stop() {
  controller?.abort()
  streaming.value = false
}

function reset() {
  if (streaming.value) stop()
  sessionId.value = newSessionId()
  messages.value = []
  pendingChangeId.value = null
  confirmToken.value = null
  applyResult.value = null
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

/** 对话里也能直接带令牌执行，不必跳到流水线面板。 */
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

onMounted(() => {
  push('assistant', '你好，我是本项目的研发运维助手。可以问我：流水线有哪些、环境状态如何、某段代码在哪。')
})
</script>

<template>
  <div class="chat-page">
    <div class="chat-grid">
      <aside class="chat-side">
        <div class="side-block">
          <div class="section-heading">
            <span class="step">01</span>
            <h2 class="section-title">
              会话
            </h2>
          </div>
          <div class="field">
            <label>项目</label>
            <el-select v-model="projectId" size="default" :disabled="streaming" style="width: 100%">
              <el-option label="RY · RuoYi-Vue-Plus" value="RY" />
            </el-select>
          </div>
          <div class="field">
            <label>环境</label>
            <el-select v-model="env" size="default" :disabled="streaming" style="width: 100%">
              <el-option label="dev" value="dev" />
              <el-option label="test" value="test" />
              <el-option label="prod" value="prod" />
            </el-select>
          </div>
          <div class="field">
            <label>会话 ID</label>
            <div class="mono sid">
              {{ sessionId }}
            </div>
          </div>
          <el-button size="small" plain @click="reset">
            新会话
          </el-button>
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
      </aside>

      <main class="chat-main">
        <div ref="listRef" class="list">
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
        </div>

        <div class="composer">
          <el-input
            v-model="input"
            type="textarea"
            :rows="3"
            resize="none"
            :placeholder="placeholder"
            @keydown.enter.exact.prevent="send"
          />
          <div class="composer-actions">
            <span class="hint">Enter 发送 · Shift+Enter 换行</span>
            <div class="composer-buttons">
              <el-button v-if="streaming" @click="stop">
                停止
              </el-button>
              <el-button
                type="primary"
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
      </main>
    </div>

    <el-dialog v-model="changeDialog" title="变更确认" width="560px">
      <div v-if="change" class="confirm-card">
        <div class="line">
          <span>变更单</span><b class="mono">{{ change.changeId }}</b>
        </div>
        <div class="line">
          <span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b>
        </div>
        <div class="line">
          <span>类型</span><b>{{ change.kind }}</b>
        </div>
        <div class="line">
          <span>内容</span><b>{{ change.summary }}</b>
        </div>
        <div class="line">
          <span>镜像 digest</span>
          <b class="mono">{{ change.imageDigest ?? '—' }}</b>
        </div>
        <div class="line">
          <span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 24) }}…</b>
        </div>
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
        <el-button @click="changeDialog = false">
          关闭
        </el-button>
        <el-button :disabled="!!confirmToken" @click="doConfirm">
          确认变更
        </el-button>
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
  height: 100%;
  padding: 20px clamp(16px, 2.4vw, 40px) 24px;
}
.chat-grid {
  display: grid;
  grid-template-columns: var(--sidebar-w) minmax(0, 1fr);
  gap: 20px;
  height: 100%;
  min-height: 0;
}
.chat-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px;
  overflow-y: auto;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
}
.side-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.section-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 2px;
}
.step {
  font: 600 11px/24px monospace;
  color: var(--accent);
}
.section-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field label {
  font-size: 12px;
  color: var(--ink-soft);
}
.sid {
  padding: 6px 10px;
  color: var(--ink-soft);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 8px;
  word-break: break-all;
}
.change-tip {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: auto;
  padding: 12px;
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
.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
}
.list {
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: 14px;
  padding: 20px 24px;
  overflow-y: auto;
}
.row.user {
  display: flex;
  justify-content: flex-end;
}
.row.assistant {
  display: flex;
}
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  color: var(--ink);
  background: var(--bg);
  border: 1px solid var(--line);
  border-radius: 12px;
}
.row.user .bubble {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
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
  padding-left: 2px;
  font-size: 12px;
}
.step-text {
  color: var(--ink-soft);
}
.composer {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--line-soft);
}
.composer-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.composer-buttons {
  display: flex;
  gap: 8px;
}
.hint {
  font-size: 12px;
  color: #a0a6ad;
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
@media (max-width: 860px) {
  .chat-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .chat-side {
    flex-direction: row;
    flex-wrap: wrap;
    align-items: flex-end;
  }
  .side-block {
    flex: 1 1 200px;
  }
  .change-tip {
    margin-top: 0;
  }
}
</style>
