<script setup lang="ts">
import { computed } from 'vue'
import type { AgentProjection, UsageCost } from '../../types'
import { formatAmount, sumCost } from '../../utils/deepseekPricing'
import MessageItem from './MessageItem.vue'
import ThinkingBlock from './ThinkingBlock.vue'

/**
 * 消息时间线（参考 ruoyi-copilot 的 ConversationTimeline.vue 的职责）：
 * 只渲染 reducer 投影里的消息列（user 消息也在其中，保证多轮顺序）；
 * 滚动容器和粘底逻辑由父视图持有（scroll container 放在 View 层便于布局）。
 */
interface Props {
  projection: AgentProjection
  /** 是否在流式生成中（决定光标与步骤行的运行态）。 */
  streaming?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{ (e: 'copy', text: string): void }>()

/** 确认卡是否已展开（由父视图通过 v-model 式同步；展开后隐藏提示条避免信息重复）。 */
const cardOpen = defineModel<boolean>('cardOpen', { default: false })

const items = computed(() => props.projection.messages)

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
/** 思考增量：与正文同样逐帧刷新，正文开始后自动收起。 */
const streamingThinking = computed(() => props.projection.streamingTurn?.thinking ?? '')

/**
 * 每轮费用合计：一轮 = 一条 user 消息 → 下一条 user 消息之前的所有消息
 * （含轮内多次模型调用与工具步骤），统计落在该轮最后一条消息下方。
 *
 * <p>末尾轮在流式中先不显示，避免金额反复跳动；本轮结束后自然出现。</p>
 */
const turnCosts = computed(() => {
  const map = new Map<string, UsageCost>()
  let acc: UsageCost | undefined
  let lastId = ''
  let tail = false
  const flush = () => {
    if (acc && lastId && (!tail || !props.streaming)) map.set(lastId, acc)
  }
  for (const message of items.value) {
    if (message.role === 'user') {
      flush()
      acc = undefined
      lastId = ''
      tail = false
      continue
    }
    if (message.cost) acc = sumCost(acc, message.cost)
    lastId = message.id
  }
  tail = true
  flush()
  return map
})

/** 渲染行：消息 + 是否挂流式光标 + 该轮费用合计（仅该轮最后一条有值）。 */
const rows = computed(() => items.value.map((message, index) => ({
  message,
  streaming: itemActive(index, message),
  cost: turnCosts.value.get(message.id),
})))
</script>

<template>
  <div class="chat-column">
    <template v-for="row in rows" :key="row.message.id">
      <MessageItem
        :message="row.message"
        :tool="row.message.toolCallId ? projection.tools[row.message.toolCallId] : undefined"
        :streaming="row.streaming"
        @copy="emit('copy', $event)"
      />
      <!-- 每轮费用合计：等宽小字，贴在该轮最后一条消息下方 -->
      <div v-if="row.cost" class="turn-cost mono">
        ￥ 本轮消耗：{{ formatAmount(row.cost.totalCost) }}
        (输入: {{ formatAmount(row.cost.inputCost) }} | 输出: {{ formatAmount(row.cost.outputCost) }})
        [{{ row.cost.isPeak ? '高峰时段' : '空闲时段' }}]
      </div>
    </template>

    <!-- 流式中的 assistant 思考 + 正文（尚未落盘，随 SSE 增量实时刷新） -->
    <div v-if="projection.streamingTurn" class="row assistant">
      <div class="assistant-block">
        <ThinkingBlock
          v-if="streamingThinking"
          :content="streamingThinking"
          :active="!streamingText"
        />
        <span v-if="streamingText" class="streaming-text">{{ streamingText }}</span>
        <span class="caret" />
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
  gap: 4px;
  width: 100%;
  max-width: 45rem;
  min-height: 100%;
  padding: 0 0 28px;
  margin: 0 auto;
}
.row.assistant {
  display: flex;
  min-width: 0;
  margin-top: 6px;
}
/* 每轮费用：等宽小字，常驻显示（轮末一行，不随 hover 隐藏） */
.turn-cost {
  padding: 1px 2px 2px;
  margin-bottom: 2px;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-faint);
  word-break: break-all;
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
