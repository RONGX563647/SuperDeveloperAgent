<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { StageView } from '../../api/pipeline'
import { formatDuration, statusLabel, statusTone } from '../../utils/status'

/**
 * Stage 进度可视化：
 * - 横向步骤条（已过 / 当前 / 待跑），一眼看出"跑到哪一步、还剩几步"；
 * - 当前阶段显示实时计时（不再等结束后才有 durationMs）；
 * - 完成/失败阶段展示耗时与 errorCode/summary。
 */
interface Props {
  stages: StageView[]
  running: boolean
  /** 运行开始时间（epoch ms），用于当前阶段的实时计时。 */
  startedAt?: number
}

const props = defineProps<Props>()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

watch(() => props.running, (running) => {
  if (running && timer === null) {
    timer = setInterval(() => (now.value = Date.now()), 1000)
  } else if (!running && timer !== null) {
    clearInterval(timer)
    timer = null
  }
}, { immediate: true })

onBeforeUnmount(() => {
  if (timer !== null) clearInterval(timer)
})

/** 当前阶段下标：最后一个 RUNNING（无则取第一个非终态）。 */
const activeIndex = computed(() => {
  const idx = props.stages.findIndex((s) => s.status === 'RUNNING')
  return idx
})

const doneCount = computed(() =>
  props.stages.filter((s) => s.status === 'SUCCEEDED' || s.status === 'SKIPPED').length)

const total = computed(() => props.stages.length)

function stageDuration(s: StageView): string {
  if (s.durationMs > 0) return formatDuration(s.durationMs)
  if (props.running && s.status === 'RUNNING' && props.startedAt) {
    // 运行中：按运行开始时间估算已耗时（后端未给阶段开始时间）
    return formatDuration(Math.max(0, now.value - props.startedAt))
  }
  return '—'
}
</script>

<template>
  <section class="stage-timeline" aria-label="阶段进度">
    <div class="st-head">
      <span class="st-title">阶段</span>
      <span class="st-progress">
        <template v-if="total">{{ doneCount }} / {{ total }} 完成</template>
        <template v-else>—</template>
      </span>
      <div class="st-bar" :aria-valuenow="doneCount" aria-valuemin="0" :aria-valuemax="total || 1" role="progressbar">
        <div class="st-bar-fill" :style="{ width: total ? `${(doneCount / total) * 100}%` : '0%' }" />
      </div>
    </div>

    <ol v-if="total" class="steps">
      <li
        v-for="(s, i) in stages"
        :key="`${s.id}-${i}`"
        class="step"
        :class="[statusTone(s.status), { active: i === activeIndex }]"
      >
        <span class="step-dot">
          <span v-if="s.status === 'RUNNING'" class="pulse" />
        </span>
        <div class="step-body">
          <div class="step-head">
            <b class="mono step-id">{{ s.id }}</b>
            <span class="step-status">{{ statusLabel(s.status) }}</span>
            <span class="step-duration mono">{{ stageDuration(s) }}</span>
          </div>
          <p v-if="s.errorCode || s.summary" class="step-meta">
            <span v-if="s.errorCode" class="mono err-code">{{ s.errorCode }}</span>
            <span v-if="s.summary">{{ s.summary }}</span>
          </p>
        </div>
      </li>
    </ol>

    <p v-else class="st-empty">运行开始时将按 pipeline.yml 依次执行各阶段</p>
  </section>
</template>

<style scoped>
.stage-timeline {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.st-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.st-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.st-progress {
  font-size: 11.5px;
  color: var(--ink-muted);
}
.st-bar {
  flex: 1;
  height: 4px;
  overflow: hidden;
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.st-bar-fill {
  height: 100%;
  background: var(--accent);
  border-radius: var(--radius-pill);
  transition: width var(--dur-base) var(--ease-out);
}
.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;
}
.step {
  display: flex;
  flex: 1 1 180px;
  gap: 8px;
  min-width: 0;
  padding: 8px 10px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.step.ok { border-color: color-mix(in srgb, var(--ok) 35%, transparent); }
.step.bad { border-color: color-mix(in srgb, var(--danger) 35%, transparent); }
.step.busy { border-color: color-mix(in srgb, var(--accent) 45%, transparent); background: var(--accent-soft); }
.step-dot {
  position: relative;
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 5px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.step.ok .step-dot { background: var(--ok); }
.step.bad .step-dot { background: var(--danger); }
.step.busy .step-dot { background: var(--accent); }
.pulse {
  position: absolute;
  inset: -3px;
  border: 1px solid var(--accent);
  border-radius: 50%;
  animation: st-pulse 1.4s ease-out infinite;
}
@keyframes st-pulse {
  from { opacity: 0.8; transform: scale(0.8); }
  to { opacity: 0; transform: scale(1.8); }
}
.step-body {
  min-width: 0;
  flex: 1;
}
.step-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.step-id {
  overflow: hidden;
  font-size: 12px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.step-status {
  flex: none;
  font-size: 11px;
  color: var(--ink-muted);
}
.step.ok .step-status { color: var(--ok); }
.step.bad .step-status { color: var(--danger); }
.step.busy .step-status { color: var(--accent-ink); }
.step-duration {
  flex: none;
  margin-left: auto;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.step-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 3px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-muted);
  word-break: break-word;
}
.err-code {
  padding: 0 5px;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: var(--radius-xs);
}
.st-empty {
  margin: 0;
  font-size: 12px;
  color: var(--ink-faint);
}
</style>
