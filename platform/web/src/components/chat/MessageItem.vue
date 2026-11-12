<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'
import type { ChatMessage, ToolActivity } from '../../types'
import { formatClock } from '../../utils/status'
import MarkdownView from './MarkdownView.vue'
import ThinkingBlock from './ThinkingBlock.vue'

/**
 * 单条消息渲染（参考 ruoyi-copilot 的 MessageItem.vue 的职责划分）：
 * - user：右侧软底气泡；assistant：白纸正文（Markdown），无气泡；
 * - step：工具调用行（ToolCallCard 兜底形态）+ 工具返回摘要；error：警示色块。
 * 复制 / 时间戳为 hover 才出现的 meta 行。
 */
interface Props {
  message: ChatMessage
  /** 步骤消息对应的工具活动（含返回文本与失败状态）。 */
  tool?: ToolActivity
  /** 是否为最后一条 assistant：挂流式光标。 */
  streaming?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{ (e: 'copy', text: string): void }>()

const isUser = computed(() => props.message.role === 'user')
const isStep = computed(() => props.message.role === 'step')
const isError = computed(() => props.message.role === 'error')
const isAssistant = computed(() => props.message.role === 'assistant')

const toolFailed = computed(() => props.tool?.status === 'FAILED')
const toolRunning = computed(() =>
  props.tool ? props.tool.status === 'RUNNING' : props.streaming)

/**
 * AgentScope 会把工具返回值再 JSON 序列化一次，前端拿到的是带外层引号且换行被转义的字符串
 * （如 `"history:\n- run-1"`）。这里在结果完整后解析回原文，避免时间线出现字面量 \n。
 */
const normalizedResult = computed(() => {
  const raw = props.tool?.resultText?.trim()
  if (!raw) return ''
  if (raw.startsWith('"') && raw.endsWith('"')) {
    try {
      const parsed = JSON.parse(raw)
      if (typeof parsed === 'string') return parsed
    } catch {
      // 流式中间态可能不是合法 JSON，保持原样
    }
  }
  return raw
})

/** 结果默认折叠：只显示一行摘要，点击后在原地展开完整内容。 */
const resultOpen = ref(false)

/** 工具入参摘要：优先展示参数（那才是"调用了什么"），没有参数再退回结果首行。 */
const argsSummary = computed(() => {
  const raw = props.tool?.arguments?.trim()
  if (!raw || raw === '{}' || raw === '[]') return ''
  const compact = raw.replace(/\s+/g, ' ')
  return compact.length > 64 ? `${compact.slice(0, 64)}…` : compact
})

const resultPreview = computed(() => {
  // 流式中间态还没闭合引号时，先去掉外层引号与 \n 转义，避免摘要里出现字面量
  const source = normalizedResult.value.startsWith('"')
    ? normalizedResult.value.slice(1).replace(/\\n/g, ' ').replace(/\\"/g, '"')
    : normalizedResult.value
  const oneLine = source.replace(/\s+/g, ' ').trim()
  return oneLine.length > 64 ? `${oneLine.slice(0, 64)}…` : oneLine
})

const stepSummary = computed(() => argsSummary.value || resultPreview.value)

/** 运行结束就收起：步骤完成后不该继续占着正文。 */
watch(() => props.tool?.status, (now, prev) => {
  if (prev === 'RUNNING' && now && now !== 'RUNNING') resultOpen.value = false
})

const time = computed(() => formatClock(props.message.at))
</script>

<template>
  <div :id="`msg-${message.id}`" class="msg-row" :class="message.role">
    <!-- 工具调用步骤行：一行式（类型 · 摘要），点击展开返回 -->
    <div v-if="isStep" class="tool-step">
      <button
        type="button" class="tool-line" :class="toolFailed ? 'bad' : toolRunning ? 'running' : 'done'"
        :aria-expanded="resultOpen"
        @click="resultOpen = !resultOpen"
      >
        <span class="tool-chev" aria-hidden="true">›</span>
        <span class="tool-kind">{{ message.text }}</span>
        <span v-if="stepSummary" class="tool-sep" aria-hidden="true">·</span>
        <span class="tool-summary mono" :title="tool?.arguments">{{ stepSummary }}</span>
        <span class="tool-state mono">
          {{ toolFailed ? `失败${tool?.code ? ` ${tool.code}` : ''}` : toolRunning ? '执行中' : '完成' }}
        </span>
      </button>
      <pre v-if="normalizedResult && resultOpen" class="tool-result">{{ normalizedResult }}</pre>
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
        <ThinkingBlock v-if="message.thinking" :content="message.thinking" />
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
/* 一轮的节奏：换轮（新 user 消息）拉开距离，轮内的工具步骤紧贴正文 */
.msg-row.user {
  justify-content: flex-end;
  margin-top: 20px;
}
.msg-row.assistant {
  margin-top: 6px;
}
.msg-row.step {
  margin-top: 2px;
}
/* 连续工具调用并成一段（行距收到最小），读起来是一组动作而不是一堆独立消息 */
.msg-row.step + .msg-row.step {
  margin-top: -2px;
}

/* 工具调用行：一行式，和正文同缩进，不打断阅读节奏 */
.tool-step {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.tool-line {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  max-width: 100%;
  padding: 3px 6px;
  margin: 1px -6px;
  font-family: inherit;
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: background var(--dur-fast) ease;
}
.tool-line:hover {
  background: var(--surface-hover);
}
.tool-chev {
  flex: none;
  width: 8px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1;
  color: var(--ink-faint);
  transition: transform var(--dur-fast) ease;
}
.tool-line[aria-expanded='true'] .tool-chev {
  transform: rotate(90deg);
}
.tool-kind {
  flex: none;
  font-size: 11.5px;
  color: var(--ink-soft);
}
.tool-line.bad .tool-kind {
  color: var(--danger);
}
.tool-sep {
  flex: none;
  color: var(--ink-faint);
}
.tool-summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.tool-state {
  flex: none;
  font-size: 10px;
  color: var(--ink-faint);
}
.tool-line.done .tool-state { color: var(--ok); }
.tool-line.running .tool-state { color: var(--accent-ink); }
.tool-line.bad .tool-state { color: var(--danger); }

/* 工具返回：展开后贴在步骤行下方 */
.tool-result {
  max-height: 340px;
  padding: 6px 9px;
  margin: 0 0 4px 14px;
  overflow: auto;
  font-family: var(--font-mono);
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-muted);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--surface-sunken);
  border-left: 2px solid var(--line-soft);
  border-radius: var(--radius-xs);
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
  padding: 9px 14px;
  font-size: 14px;
  line-height: 1.72;
  color: var(--ink);
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  /* 右下角收窄：指向发送者一侧的「尾巴」，比正圆更像一次发言 */
  border-radius: 16px 16px 5px 16px;
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
