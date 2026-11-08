<script setup lang="ts">
import { computed } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'
import type { ChatMessage } from '../../types'
import MarkdownView from './MarkdownView.vue'

/**
 * 单条消息渲染（参考 ruoyi-copilot 的 MessageItem.vue 的职责划分）：
 * - user：右侧软底气泡；assistant：白纸正文（Markdown），无气泡；
 * - step：工具调用行（ToolCallCard 兜底形态）；error：警示色块。
 * 复制 / 时间戳为 hover 才出现的 meta 行。
 */
interface Props {
  message: ChatMessage
  /** 是否为最后一条 assistant：挂流式光标。 */
  streaming?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{ (e: 'copy', text: string): void }>()

const isUser = computed(() => props.message.role === 'user')
const isStep = computed(() => props.message.role === 'step')
const isError = computed(() => props.message.role === 'error')
const isAssistant = computed(() => props.message.role === 'assistant')

const time = computed(() => {
  const d = new Date(props.message.at)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
})
</script>

<template>
  <div class="msg-row" :class="message.role">
    <!-- 工具调用步骤行 -->
    <div v-if="isStep" class="tool-line">
      <span class="tool-caret" aria-hidden="true">&gt;_</span>
      <span class="tool-cmd">{{ message.text }}</span>
      <span class="tool-state" :class="streaming ? 'running' : 'done'">
        {{ streaming ? '执行中' : '已完成' }}
      </span>
    </div>

    <!-- 错误行 -->
    <div v-else-if="isError" class="error-line">
      <span class="error-tag">中断</span>
      <span class="error-text">{{ message.text }}</span>
    </div>

    <template v-else>
      <!-- 用户消息：右侧软底块 -->
      <div v-if="isUser" class="user-block">
        <div class="user-bubble">{{ message.text }}</div>
        <div class="msg-meta">
          <time class="meta-time mono">{{ time }}</time>
          <button type="button" class="meta-btn" title="复制" @click="emit('copy', message.text)">
            <el-icon><CopyDocument /></el-icon>
          </button>
        </div>
      </div>

      <!-- 助手消息：白纸正文（Markdown 渲染），无气泡 -->
      <div v-else class="assistant-block">
        <MarkdownView :content="message.text" />
        <span v-if="streaming" class="caret" />
        <div v-if="message.text" class="msg-meta">
          <time class="meta-time mono">{{ time }}</time>
          <button type="button" class="meta-btn" title="复制" @click="emit('copy', message.text)">
            <el-icon><CopyDocument /></el-icon>
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.msg-row {
  display: flex;
  min-width: 0;
}
.msg-row.user {
  justify-content: flex-end;
  margin-top: 22px;
}

/* 工具调用行 */
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
  flex: none;
  font-size: 11px;
}
.tool-state.done { color: var(--ok); }
.tool-state.running { color: var(--accent-ink); }

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

/* 用户块 */
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

/* 助手块 */
.assistant-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 0;
}

/* 流式光标 */
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
  50% { opacity: 0; }
}

/* meta 行：hover 才出现 */
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

@media (max-width: 720px) {
  .user-block {
    max-width: 88%;
  }
}
</style>
