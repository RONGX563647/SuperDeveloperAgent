<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

/**
 * 思考过程折叠块：SSE 的 thinking 增量逐帧写入 content，这里跟着刷新。
 * - active=true 时默认展开并自动贴底滚动（能看到"正在想什么"）；
 * - 正文开始到达（active 变 false）后自动收起，不占阅读空间；
 * - 完成后思考仍保留在该条消息上，可手动展开回看。
 */
interface Props {
  content: string
  active?: boolean
}

const props = defineProps<Props>()

const open = ref(Boolean(props.active))
const body = ref<HTMLPreElement | null>(null)

watch(() => props.active, (now) => {
  open.value = Boolean(now)
})

watch(() => props.content, async () => {
  if (!props.active || !open.value) return
  await nextTick()
  const el = body.value
  if (el) el.scrollTop = el.scrollHeight
})

function onToggle(event: Event): void {
  open.value = (event.target as HTMLDetailsElement).open
}
</script>

<template>
  <details class="thinking" :open="open" @toggle="onToggle">
    <summary class="thinking-head">
      <span class="thinking-dot" :class="{ live: active }" aria-hidden="true" />
      <span class="thinking-title">{{ active ? '思考中…' : '思考过程' }}</span>
      <span class="thinking-count mono">{{ content.length }}</span>
    </summary>
    <pre ref="body" class="thinking-body">{{ content || '…' }}</pre>
  </details>
</template>

<style scoped>
.thinking {
  margin: 2px 0 6px;
  border-left: 2px solid var(--line-soft);
}
.thinking-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  font-size: 11.5px;
  color: var(--ink-faint);
  cursor: pointer;
  list-style: none;
  user-select: none;
}
.thinking-head::-webkit-details-marker {
  display: none;
}
.thinking-head:hover {
  color: var(--ink-soft);
}
.thinking-dot {
  flex: none;
  width: 5px;
  height: 5px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.thinking-dot.live {
  background: var(--accent);
  animation: thinking-pulse 1.2s ease-in-out infinite;
}
.thinking-title {
  flex: none;
}
.thinking-count {
  flex: none;
  font-size: 10px;
  color: var(--ink-faint);
}
.thinking-body {
  max-height: 180px;
  padding: 6px 9px;
  margin: 0 0 4px;
  overflow: auto;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.65;
  color: var(--ink-muted);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
}
@keyframes thinking-pulse {
  50% { opacity: 0.25; }
}
</style>
