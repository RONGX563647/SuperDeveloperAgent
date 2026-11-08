<script setup lang="ts">
import { onBeforeUnmount, onMounted, shallowRef } from 'vue'

/**
 * 水平可拖拽分隔条（零依赖，pointer events）。
 *
 * - 拖动时计算相对容器的百分比宽度，向上 emit `resize`；
 * - 拖动中给 body 加 user-select:none，防止选中文本；光标锁 col-resize；
 * - 双击恢复默认比例；键盘可达（左右箭头 ±2%，符合 WAI-ARIA splitter 模式）。
 */
interface Props {
  /** 左栏当前宽度（%）。 */
  modelValue: number
  min?: number
  max?: number
}

const props = withDefaults(defineProps<Props>(), { min: 15, max: 50 })

const emit = defineEmits<{ (e: 'update:modelValue', v: number): void }>()

const dragging = shallowRef(false)
let startX = 0
let startPct = 0
let parentEl: HTMLElement | null = null

function onPointerDown(e: PointerEvent): void {
  parentEl = (e.currentTarget as HTMLElement).parentElement
  if (!parentEl) return
  dragging.value = true
  startX = e.clientX
  startPct = props.modelValue
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}

function onPointerMove(e: PointerEvent): void {
  if (!dragging.value || !parentEl) return
  const dx = e.clientX - startX
  const pct = startPct + (dx / parentEl.clientWidth) * 100
  emit('update:modelValue', Math.min(props.max, Math.max(props.min, pct)))
}

function onPointerUp(e: PointerEvent): void {
  if (!dragging.value) return
  dragging.value = false
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
  ;(e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId)
}

function onDblClick(): void {
  emit('update:modelValue', 25)
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'ArrowLeft') emit('update:modelValue', Math.max(props.min, props.modelValue - 2))
  if (e.key === 'ArrowRight') emit('update:modelValue', Math.min(props.max, props.modelValue + 2))
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
})
</script>

<template>
  <div
    class="resizer" role="separator" aria-orientation="vertical"
    :aria-valuenow="Math.round(modelValue)"
    tabindex="0"
    :class="{ dragging }"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @dblclick="onDblClick"
  />
</template>

<style scoped>
.resizer {
  flex: none;
  width: 5px;
  margin: 0 -2px;
  z-index: 2;
  cursor: col-resize;
  background: transparent;
  transition: background-color var(--dur-fast) ease;
}
.resizer::after {
  content: '';
  display: block;
  width: 1px;
  height: 100%;
  margin: 0 auto;
  background: var(--line-soft);
  transition: background-color var(--dur-fast) ease;
}
.resizer:hover::after,
.resizer.dragging::after {
  background: var(--accent);
}
.resizer:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
  border-radius: var(--radius-xs);
}
.resizer.dragging {
  background: var(--accent-soft);
}
</style>
