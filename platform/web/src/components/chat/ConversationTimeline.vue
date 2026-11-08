<script setup lang="ts">
import { computed } from 'vue'
import type { AgentProjection } from '../../types'
import MessageItem from './MessageItem.vue'

/**
 * 消息时间线（参考 ruoyi-copilot 的 ConversationTimeline.vue 的职责）：
 * 接收 reducer 投影 + 本地 user 消息，统一渲染成消息列；
 * 滚动容器和粘底逻辑由父视图持有（scroll container 放在 View 层便于布局）。
 */
interface Props {
  projection: AgentProjection
  /** 本地 user/greeting 消息（reducer 不处理 user 侧）。 */
  localUserMessages?: AgentProjection['messages']
  /** 是否在流式生成中（决定光标与步骤行的运行态）。 */
  streaming?: boolean
}

const props = withDefaults(defineProps<Props>(), { localUserMessages: () => [] })

const emit = defineEmits<{ (e: 'copy', text: string): void }>()

/** 确认卡是否已展开（由父视图通过 v-model 式同步；展开后隐藏提示条避免信息重复）。 */
const cardOpen = defineModel<boolean>('cardOpen', { default: false })

const items = computed(() => [...props.localUserMessages, ...props.projection.messages])

/** 是否最后一条 assistant 消息（挂流式光标）。 */
const lastAssistantId = computed(() => {
  for (let i = items.value.length - 1; i >= 0; i--) {
    if (items.value[i].role === 'assistant') return items.value[i].id
  }
  return ''
})

/**
 * 每条消息的"进行中"判定：
 * - assistant：是最后一条 assistant 且正在流式（挂光标）；
 * - step：是最后一条消息且正在流式（其后还没有任何正文/事件）；
 * - 其他：false。
 */
function itemActive(index: number, m: { role: string; id: string }): boolean {
  if (!props.streaming) return false
  if (m.role === 'assistant') return m.id === lastAssistantId.value
  if (m.role === 'step') return index === items.value.length - 1
  return false
}

/** 流式轮次作为虚拟的"最后一块 assistant 正文"追加。 */
const streamingText = computed(() => props.projection.streamingTurn?.text ?? '')
</script>

<template>
  <div class="chat-column">
    <MessageItem
      v-for="(m, i) in items"
      :key="m.id"
      :message="m"
      :streaming="itemActive(i, m)"
      @copy="emit('copy', $event)"
    />

    <!-- 流式中的 assistant 正文（尚未落盘） -->
    <div v-if="projection.streamingTurn" class="row assistant">
      <div class="assistant-block">
        <span class="streaming-text">{{ streamingText }}</span><span class="caret" />
      </div>
    </div>

    <!-- 变更待确认提示（由 change.created 事件驱动；确认卡展开后隐藏，避免信息重复） -->
    <div v-if="projection.pendingChangeId && !cardOpen" class="change-tip">
      <span class="tip-title">有变更待确认</span>
      <code class="tip-id mono">{{ projection.pendingChangeId }}</code>
      <slot name="change-action" />
    </div>
  </div>
</template>

<style scoped>
.chat-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  max-width: 54rem;
  min-height: 100%;
  padding: 0 0 24px clamp(0px, 2vw, 24px);
}
.row.assistant {
  display: flex;
  min-width: 0;
}
.assistant-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  min-width: 0;
  font-size: 14.5px;
  line-height: 1.9;
  color: var(--ink);
  word-break: break-word;
}
.streaming-text {
  white-space: pre-wrap;
}
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

/* 变更待确认提示 */
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
.tip-title {
  flex: none;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--warn);
}
.tip-id {
  overflow: hidden;
  flex: 1;
  min-width: 0;
  font-size: 11px;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
