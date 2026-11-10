<script setup lang="ts">
import { computed, ref } from 'vue'
import { Operation } from '@element-plus/icons-vue'
import type { TraceTurn } from '../../types'

/**
 * 对话页右侧的回答定位刻度（参考 DSH 对话页的细轨）。
 *
 * <p>一轮一段：当前阅读位置压深、失败轮标红、流式末轮呼吸；悬停出摘要，点击滚回该轮。
 * 更完整的执行过程在「轨迹」页查看，这里只做轻量定位，不再内嵌面板。
 */
const props = defineProps<{
  turns: TraceTurn[]
  activeId: string
  streaming?: boolean
}>()

const emit = defineEmits<{
  (e: 'locate', id: string): void
  (e: 'open-trace'): void
}>()

const hoverId = ref('')

function tickWidth(turn: TraceTurn): string {
  return `${Math.min(34, 18 + turn.question.length * 0.6)}px`
}

function duration(ms: number): string {
  if (ms < 1000) return `${Math.max(0, ms)}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`
}

function time(at: number): string {
  const d = new Date(at)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function isLast(turn: TraceTurn): boolean {
  return props.turns.length > 0 && props.turns[props.turns.length - 1].id === turn.id
}

const hovered = computed(() => props.turns.find((t) => t.id === hoverId.value) ?? null)

function locate(id: string): void {
  hoverId.value = ''
  emit('locate', id)
}
</script>

<template>
  <div v-if="turns.length" class="trace-rail" @mouseleave="hoverId = ''">
    <button
      type="button"
      class="rail-toggle"
      title="打开轨迹"
      @click="emit('open-trace')"
    >
      <el-icon><Operation /></el-icon>
    </button>

    <div class="ticks">
      <button
        v-for="turn in turns"
        :key="turn.id"
        type="button"
        class="tick-hit"
        :aria-label="`第 ${turn.index} 轮`"
        @mouseenter="hoverId = turn.id"
        @focus="hoverId = turn.id"
        @click="locate(turn.id)"
      >
        <span
          class="tick"
          :class="{
            active: turn.id === activeId,
            live: streaming && isLast(turn),
            bad: turn.failed,
          }"
          :style="{ '--w': tickWidth(turn) }"
        />
      </button>
    </div>

    <!-- 悬停摘要：贴着刻度左侧，一条一段，不堆叠 -->
    <span v-if="hovered" class="tick-card">
      <span class="card-head mono">第 {{ hovered.index }} 轮 · {{ time(hovered.at) }}</span>
      <span class="card-q">{{ hovered.question || '（无文本）' }}</span>
      <span class="card-meta mono">
        {{ hovered.steps }} 步 · {{ hovered.tools }} 调用
        · {{ duration(hovered.endedAt - hovered.startedAt) }}
      </span>
    </span>
  </div>
</template>

<style scoped>
.trace-rail {
  position: absolute;
  top: 50%;
  right: 8px;
  z-index: 6;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
  transform: translateY(-50%);
}

/* 打开轨迹页的入口：默认安静，悬停才显形 */
.rail-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  font-size: 12px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  opacity: 0.55;
  transition: opacity var(--dur-fast) ease, color var(--dur-fast) ease,
    background-color var(--dur-fast) ease;
}
.rail-toggle:hover {
  color: var(--ink);
  background: var(--surface-hover);
  opacity: 1;
}

.ticks {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 7px;
  padding: 2px 0;
}

.tick-hit {
  position: relative;
  display: flex;
  justify-content: flex-end;
  padding: 2px 0;
  background: none;
  border: 0;
  cursor: pointer;
}

/* 刻度：一轮一段。默认是极淡的分隔色，当前轮压深 */
.tick {
  width: var(--w);
  height: 3px;
  background: var(--line-strong);
  border-radius: 2px;
  transition: width var(--dur-fast) ease, background-color var(--dur-fast) ease,
    opacity var(--dur-fast) ease;
}
.tick-hit:hover .tick {
  width: 40px;
  background: var(--accent);
}
.tick.active {
  background: var(--ink);
}
.tick.bad {
  background: var(--danger);
}
.tick.live {
  background: var(--ink);
  animation: tick-breathe 1.6s ease-in-out infinite;
}
@keyframes tick-breathe {
  50% { opacity: 0.4; }
}

/* 悬停摘要卡 */
.tick-card {
  position: absolute;
  top: 50%;
  right: calc(100% + 10px);
  display: flex;
  flex-direction: column;
  gap: 3px;
  width: 232px;
  padding: 8px 10px;
  pointer-events: none;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  box-shadow: 0 8px 24px rgb(16 24 40 / 10%);
  transform: translateY(-50%);
}
.card-head {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.card-q {
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.card-meta {
  font-size: 10.5px;
  color: var(--ink-muted);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* 窄屏保留定位能力，但收窄刻度并收起悬停卡，避免压住正文 */
@media (max-width: 760px) {
  .trace-rail {
    right: 2px;
    gap: 8px;
  }
  .rail-toggle,
  .tick-card {
    display: none;
  }
  .tick {
    width: 14px;
    height: 2px;
  }
}
</style>
