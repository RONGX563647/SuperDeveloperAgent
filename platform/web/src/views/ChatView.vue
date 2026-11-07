<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { extractText, findChangeId, streamChat } from '../api/chat'
import { confirmChange, getChange } from '../api/change'
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

  try {
    await streamChat(
      { projectId: projectId.value, env: env.value, sessionId: sessionId.value, message: text },
      (event) => {
        if (event.type.includes('ToolCallStart')) {
          const name = String((event.payload as Record<string, unknown>).name ?? 'tool')
          push('step', `调用工具 ${name}`, name)
          return
        }
        if (event.type.includes('ToolResult')) {
          push('step', '工具返回', 'result')
          return
        }
        const delta = extractText(event)
        if (!delta) return
        const target = messages.value.find((m) => m.id === assistantId)
        if (target) target.text += delta
        const found = findChangeId(delta)
        if (found) pendingChangeId.value = found
        void scrollToBottom()
      },
      controller.signal
    )
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error)
    if (!msg.includes('aborted')) push('error', msg)
  } finally {
    streaming.value = false
    controller = null
  }
}

function stop() {
  controller?.abort()
  streaming.value = false
}

function reset() {
  sessionId.value = newSessionId()
  messages.value = []
  pendingChangeId.value = null
  confirmToken.value = null
}

async function openChange() {
  if (!pendingChangeId.value) return
  try {
    change.value = await getChange(pendingChangeId.value)
    confirmToken.value = null
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

onMounted(() => {
  push('assistant', '你好，我是本项目的研发运维助手。可以问我：流水线有哪些、环境状态如何、某段代码在哪。')
})
</script>

<template>
  <div class="chat">
    <aside class="side">
      <div class="field">
        <label>项目</label>
        <el-select v-model="projectId" size="small" :disabled="streaming">
          <el-option label="RY · RuoYi-Vue-Plus" value="RY" />
        </el-select>
      </div>
      <div class="field">
        <label>环境</label>
        <el-select v-model="env" size="small" :disabled="streaming">
          <el-option label="dev" value="dev" />
          <el-option label="test" value="test" />
          <el-option label="prod" value="prod" />
        </el-select>
      </div>
      <div class="field">
        <label>会话</label>
        <div class="mono">{{ sessionId }}</div>
      </div>
      <el-button size="small" @click="reset">新会话</el-button>

      <div v-if="pendingChangeId" class="change-tip">
        <div class="tip-title">有变更待确认</div>
        <div class="mono">{{ pendingChangeId }}</div>
        <el-button type="warning" size="small" @click="openChange">查看确认卡片</el-button>
      </div>
    </aside>

    <main class="main">
      <div ref="listRef" class="list">
        <div v-for="m in messages" :key="m.id" class="row" :class="m.role">
          <div v-if="m.role === 'step'" class="step">
            <el-tag size="small" type="info">{{ m.label }}</el-tag>
            <span>{{ m.text }}</span>
          </div>
          <div v-else-if="m.role === 'error'" class="bubble error">{{ m.text }}</div>
          <div v-else class="bubble">{{ m.text || '…' }}</div>
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
        <div class="actions">
          <span class="hint">Enter 发送 · Shift+Enter 换行</span>
          <div>
            <el-button v-if="streaming" @click="stop">停止</el-button>
            <el-button type="primary" :loading="streaming" :disabled="!input.trim()" @click="send">
              发送
            </el-button>
          </div>
        </div>
      </div>
    </main>

    <el-dialog v-model="changeDialog" title="变更确认" width="520px">
      <div v-if="change" class="confirm-card">
        <div class="line"><span>变更单</span><b class="mono">{{ change.changeId }}</b></div>
        <div class="line"><span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b></div>
        <div class="line"><span>类型</span><b>{{ change.kind }}</b></div>
        <div class="line"><span>内容</span><b>{{ change.summary }}</b></div>
        <div class="line">
          <span>镜像 digest</span>
          <b class="mono">{{ change.imageDigest ?? '—' }}</b>
        </div>
        <div class="line"><span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 24) }}…</b></div>
        <div class="line">
          <span>风险</span>
          <el-tag :type="change.highRisk ? 'danger' : 'success'" size="small">
            {{ change.highRisk ? '高危（需二次密码）' : '常规' }}
          </el-tag>
        </div>

        <el-input
          v-if="change.highRisk"
          v-model="password"
          type="password"
          placeholder="请输入密码（账号体系接入前会失败关闭）"
          show-password
        />

        <el-alert
          v-if="confirmToken"
          type="success"
          :closable="false"
          title="确认令牌已签发"
          :description="confirmToken"
        />
      </div>

      <template #footer>
        <el-button @click="changeDialog = false">关闭</el-button>
        <el-button type="primary" :disabled="!!confirmToken" @click="doConfirm">确认变更</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat {
  display: grid;
  grid-template-columns: 220px 1fr;
  height: 100%;
}
.side {
  border-right: 1px solid var(--line);
  padding: 16px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.field label {
  display: block;
  font-size: 12px;
  color: #6b7a88;
  margin-bottom: 4px;
}
.change-tip {
  margin-top: auto;
  padding: 10px;
  border: 1px solid #f5d3b8;
  background: #fdeee4;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.tip-title {
  font-weight: 600;
  color: #9a4514;
  font-size: 13px;
}
.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.list {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.row.user {
  justify-content: flex-end;
  display: flex;
}
.row.assistant {
  display: flex;
}
.bubble {
  max-width: 76%;
  padding: 10px 14px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid var(--line);
  white-space: pre-wrap;
  line-height: 1.6;
  font-size: 14px;
}
.row.user .bubble {
  background: var(--ink);
  color: #fff;
  border-color: var(--ink);
}
.bubble.error {
  background: #fdecec;
  border-color: #f3c2c2;
  color: #b33333;
}
.step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #6b7a88;
}
.composer {
  border-top: 1px solid var(--line);
  padding: 12px 24px 16px;
  background: #fff;
}
.actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.hint {
  font-size: 12px;
  color: #8a98a6;
}
.confirm-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.line {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 13px;
}
.line span {
  color: #6b7a88;
}
</style>
