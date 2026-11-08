<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CaretRight, Cpu, Paperclip, Promotion, Setting,
} from '@element-plus/icons-vue'
import { appStore } from '../stores/app'
import { useAgentChat } from '../composables/useAgentChat'
import { useFollowScroll } from '../composables/useFollowScroll'
import { getToken } from '../api/auth'
import { modelOptions } from '../api/settings'
import ConversationTimeline from '../components/chat/ConversationTimeline.vue'
import ApprovalRequestCard from '../components/chat/ApprovalRequestCard.vue'
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
const projectId = computed(() => appStore.projectId)
const env = computed(() => appStore.env)
const sessionId = computed(() => appStore.sessionId)

const input = ref('')
const permission = ref<'readonly' | 'confirm' | 'auto'>('confirm')
/** 中文输入法组合标记：组合期间 Enter 不应触发发送。 */
const composing = ref(false)

const {
  projection, streaming, elapsedMs, tokens, error, pendingChangeId,
  send: sendChat, stop, reset,
} = useAgentChat({
  buildRequest: () => ({
    projectId: projectId.value,
    env: env.value,
    sessionId: sessionId.value,
  }),
  getHeaders: () => ({ Authorization: `Bearer ${getToken()}` }),
  guard: () => !composing.value,
})

const listRef = ref<HTMLElement | null>(null)
const approvalCard = ref<InstanceType<typeof ApprovalRequestCard> | null>(null)
/** 本地消息（greeting / user 侧）：不经过 reducer，规避 shallowRef 原地 push 不触发更新的问题。 */
const localMessages = ref<{ id: string; role: 'user' | 'assistant'; text: string; at: number }[]>([])
/** 变更确认卡是否展开（由 change.created 事件自动展开；dismiss 收起但不丢 pendingChangeId）。 */
const approvalCardOpen = ref(false)

function openApprovalCard(): void {
  approvalCardOpen.value = true
  void nextTick(() => {
    approvalCard.value?.load()
    void scrollToLatest({ force: true })
  })
}

const {
  updateScrollState, followNewContent, scrollToLatest,
} = useFollowScroll(listRef, { bottomThreshold: 40 })

const placeholder = computed(() =>
  env.value === 'prod'
    ? '生产环境：只能只读诊断；任何变更都需要在确认卡片上确认'
    : '发消息或描述任务，Enter 发送，Shift+Enter 换行'
)

const canSend = computed(() => !!input.value.trim() && !streaming.value)

/** 当前模型名（设置页的默认模型；未配置时显示环境变量模型）。 */
const activeModelName = ref('qwen-plus')
const activeModelSource = ref('')

async function loadActiveModel(): Promise<void> {
  try {
    const options = await modelOptions()
    if (options.modelName) {
      activeModelName.value = options.modelName
      activeModelSource.value = options.activeName ?? ''
    }
  } catch {
    // 未配置/无权限时保留占位
  }
}

// 内容变化时粘底滚动（流式增量/新消息都会触发）
watch(
  () => projection.value.messages.length + (projection.value.streamingTurn?.text.length ?? 0),
  () => followNewContent(),
)

// 流式结束时若出现待确认变更单，自动展开确认卡
watch(pendingChangeId, (id) => {
  if (id) openApprovalCard()
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
  appStore.touchSession(text)
  localMessages.value.push({ id: `u-${Date.now()}`, role: 'user', text, at: Date.now() })
  await sendChat(text)
  void scrollToLatest({ force: true })
}

function onListScroll() {
  updateScrollState()
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

function resetSession() {
  reset()
  approvalCardOpen.value = false
  appStore.newSession()
}

watch(sessionId, () => {
  reset()
  localMessages.value = []
  approvalCardOpen.value = false
  onMountedGreeting()
})

function onMountedGreeting() {
  localMessages.value.push({
    id: 'greeting',
    role: 'assistant',
    text: '你好，我是本项目的研发运维助手。可以问我：流水线有哪些、环境状态如何、某段代码在哪。',
    at: Date.now(),
  })
}

onMounted(() => {
  appStore.touchSession('')
  onMountedGreeting()
  void loadActiveModel()
  void nextTick(() => scrollToLatest({ force: true }))
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
        <button type="button" class="hdr-btn" title="新会话" @click="resetSession">
          新会话
        </button>
        <button type="button" class="icon-btn" title="设置（待接入）" disabled>
          <el-icon><Setting /></el-icon>
        </button>
      </template>
    </PageHeader>

    <!-- 消息区：唯一滚动容器（min-height:0 防止被内容撑破） -->
    <div ref="listRef" class="chat-list" @scroll.passive="onListScroll">
      <ConversationTimeline
        v-model:card-open="approvalCardOpen"
        :projection="projection"
        :local-user-messages="localMessages"
        :streaming="streaming"
        @copy="copyMessage"
      >
        <template #change-action>
          <button
            type="button" class="tip-btn"
            @click="openApprovalCard"
          >
            展开确认卡片
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
              {{ activeModelName }}
              <template v-if="activeModelSource"> · {{ activeModelSource }}</template>
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
.chat-approval {
  max-width: 54rem;
  margin: 0 auto 24px clamp(0px, 2vw, 24px);
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

@media (max-width: 720px) {
  .chat-list,
  .composer-wrap {
    padding-right: 14px;
    padding-left: 14px;
  }
  .metric {
    display: none;
  }
}
</style>
