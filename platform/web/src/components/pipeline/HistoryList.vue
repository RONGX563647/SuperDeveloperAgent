<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import type { HistoryItem } from '../../api/pipeline'
import { formatTime, statusLabel, statusTone } from '../../utils/status'

/**
 * 构建历史列表：
 * - 状态筛选（全部 / 成功 / 失败 / 进行中）+ 关键字（sha / 流水线）；
 * - 补齐信息：分支缺失时显示 sha 前缀，新增 stage 数、耗时占位；
 * - 重跑按钮常显（不再 hover 才出现），键盘可聚焦；
 * - 进行中的运行置顶并带脉冲点。
 */
interface Props {
  items: HistoryItem[]
  activeRunId: string
  loading?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'open', item: HistoryItem): void
  (e: 'rerun', item: HistoryItem): void
  (e: 'refresh'): void
}>()

type Filter = 'all' | 'ok' | 'bad' | 'busy'

const filter = shallowRef<Filter>('all')
const keyword = shallowRef('')

const FILTERS: Array<{ key: Filter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'busy', label: '进行中' },
  { key: 'ok', label: '成功' },
  { key: 'bad', label: '失败' },
]

const visible = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.items.filter((h) => {
    if (filter.value !== 'all' && statusTone(h.status) !== filter.value) return false
    if (!kw) return true
    return h.gitSha.toLowerCase().includes(kw)
      || h.pipelineId.toLowerCase().includes(kw)
      || h.runId.toLowerCase().includes(kw)
  })
})

const runningCount = computed(() =>
  props.items.filter((h) => statusTone(h.status) === 'busy').length)

function onKeydown(e: KeyboardEvent, item: HistoryItem): void {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    emit('open', item)
  }
}
</script>

<template>
  <section class="history-panel" aria-label="构建历史">
    <div class="hp-head">
      <h2 class="hp-title">构建历史</h2>
      <span class="hp-count">{{ items.length }} 条<template v-if="runningCount"> · {{ runningCount }} 进行中</template></span>
      <button type="button" class="hp-refresh" :disabled="loading" title="刷新" @click="emit('refresh')">
        <el-icon :class="{ 'is-loading': loading }"><Refresh /></el-icon>
      </button>
    </div>

    <div class="hp-filters">
      <button
        v-for="f in FILTERS" :key="f.key"
        type="button" class="filter-chip"
        :class="{ active: filter === f.key }"
        @click="filter = f.key"
      >
        {{ f.label }}
      </button>
      <input v-model="keyword" type="text" class="hp-search" placeholder="sha / 流水线…">
    </div>

    <div class="hp-body">
      <button
        v-for="h in visible"
        :key="h.runId"
        type="button"
        class="hist-item"
        :class="{ active: h.runId === activeRunId }"
        @click="emit('open', h)"
        @keydown="onKeydown($event, h)"
      >
        <span class="hist-dot" :class="statusTone(h.status)" />
        <span class="hist-main">
          <span class="hist-line1">
            <span class="hist-pipeline">{{ h.pipelineId }}</span>
            <span class="hist-status" :class="statusTone(h.status)">{{ statusLabel(h.status) }}</span>
          </span>
          <span class="hist-line2">
            <span class="mono">{{ h.gitSha.slice(0, 8) }}</span>
            <span>{{ h.stageCount }} 阶段</span>
            <span class="hist-time">{{ formatTime(h.createdAt) }}</span>
          </span>
        </span>
        <span
          class="hist-rerun" role="button" tabindex="0" title="用该提交重跑"
          @click.stop="emit('rerun', h)"
          @keydown.enter.stop="emit('rerun', h)"
        >
          <el-icon><Refresh /></el-icon>
        </span>
      </button>

      <p v-if="!loading && !visible.length" class="hp-empty">
        {{ items.length ? '没有符合条件的构建' : '暂无构建历史，先在上方运行一次流水线' }}
      </p>
    </div>
  </section>
</template>

<style scoped>
.history-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.hp-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  padding: 12px 14px 8px;
}
.hp-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.hp-count {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.hp-refresh {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  margin-left: auto;
  font-size: 13px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.hp-refresh:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.hp-refresh:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.hp-filters {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  padding: 0 14px 8px;
}
.filter-chip {
  height: 22px;
  padding: 0 9px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
}
.filter-chip:hover {
  color: var(--ink);
}
.filter-chip.active {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 30%, transparent);
}
.hp-search {
  flex: 1 1 100px;
  min-width: 0;
  height: 22px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink);
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
  outline: none;
}
.hp-search:focus {
  border-color: rgb(77 130 247 / 55%);
  background: var(--panel);
}
.hp-body {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 10px 12px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.hist-item {
  display: flex;
  gap: 9px;
  align-items: center;
  width: 100%;
  padding: 9px 10px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.hist-item:hover {
  background: var(--surface-hover);
}
.hist-item.active {
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.hist-item:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}
.hist-dot {
  flex: none;
  width: 7px;
  height: 7px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.hist-dot.ok { background: var(--ok); }
.hist-dot.bad { background: var(--danger); }
.hist-dot.busy { background: var(--accent); animation: dot-pulse 1.4s ease-in-out infinite; }
@keyframes dot-pulse { 50% { opacity: 0.35; } }
.hist-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.hist-line1,
.hist-line2 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}
.hist-pipeline {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hist-status {
  flex: none;
  font-size: 11px;
  color: var(--ink-faint);
}
.hist-status.ok { color: var(--ok); }
.hist-status.bad { color: var(--danger); }
.hist-status.busy { color: var(--accent-ink); }
.hist-line2 {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.hist-time {
  margin-left: auto;
}
.hist-rerun {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  font-size: 12px;
  color: var(--ink-faint);
  border-radius: var(--radius-xs);
}
.hist-rerun:hover {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.hp-empty {
  margin: 0;
  padding: 20px 8px;
  font-size: 12px;
  color: var(--ink-faint);
  text-align: center;
}
</style>
