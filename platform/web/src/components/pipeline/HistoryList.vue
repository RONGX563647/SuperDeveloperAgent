<script setup lang="ts">
import { computed, ref } from 'vue'
import { Delete, Refresh } from '@element-plus/icons-vue'
import type { HistoryItem } from '../../api/pipeline'
import { formatDuration, formatTime, statusLabel, statusTone } from '../../utils/status'
import { envShort, pipelineLabel } from '../../utils/pipelineLabels'

/**
 * 构建历史列表：
 * - 环境不单独筛选，跟随左栏上方的环境选择（env）自动切换；
 * - 每条显示构建序号（项目 + 环境内从 1 开始，最新在前）；
 * - 向左滑动条目露出「删除」，也可直接用键盘删除；
 * - 重跑按钮常显（不再 hover 才出现），键盘可聚焦；
 * - 进行中的运行带脉冲点，且不允许删除。
 */
interface Props {
  items: HistoryItem[]
  activeRunId: string
  /** 当前选中的环境（dev/test/prod），列表只展示该环境的运行。 */
  env: string
  loading?: boolean
  /** 是否还有更早的历史可加载。 */
  hasMore?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'open', item: HistoryItem): void
  (e: 'rerun', item: HistoryItem): void
  (e: 'delete', item: HistoryItem): void
  (e: 'refresh'): void
  (e: 'more'): void
}>()

type StatusKey = '' | 'ok' | 'bad' | 'busy'

const STATUS_FILTERS: Array<{ key: StatusKey, label: string }> = [
  { key: '', label: '全部' },
  { key: 'ok', label: '成功' },
  { key: 'bad', label: '失败' },
  { key: 'busy', label: '进行中' },
]

const statusFilter = ref<StatusKey>('')
const keyword = ref('')

/** 先按环境裁一次（dev 的本机部署与 test/prod 的镜像发布不混着看）。 */
const envItems = computed(() =>
  props.items.filter((h) =>
    h.env === props.env
    // 平台重启会把没跑完的运行标成 CANCELLED；0 阶段的这种没有信息量，不占列表
    && !(h.status === 'CANCELLED' && h.stageCount === 0)))

/** 再按状态 / 关键字筛选。 */
const visible = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return envItems.value.filter((h) => {
    if (statusFilter.value && statusTone(h.status) !== statusFilter.value) return false
    if (!kw) return true
    return h.gitSha.toLowerCase().includes(kw)
      || String(h.seq).includes(kw)
      || pipelineLabel(h.pipelineId).toLowerCase().includes(kw)
  })
})

function countOf(key: StatusKey): number {
  return key ? envItems.value.filter((h) => statusTone(h.status) === key).length : envItems.value.length
}

const runningCount = computed(() =>
  envItems.value.filter((h) => statusTone(h.status) === 'busy').length)

/* ------------------------------------------------------------ 滑动删除 */

const SWIPE_WIDTH = 76
const OPEN_THRESHOLD = 36
/** 每个条目当前的横向位移（0 = 关闭，负值 = 已露出删除）。 */
const offsets = ref<Record<string, number>>({})
/** 正在拖动的条目：拖动过程中禁用 transform 过渡，松手后再吸附。 */
const draggingId = ref('')
let drag: { id: string, startX: number, startY: number, base: number, moved: boolean } | null = null
let lastDragAt = 0

function offsetOf(id: string): number {
  return offsets.value[id] ?? 0
}

function setOffset(id: string, value: number): void {
  offsets.value = { ...offsets.value, [id]: value }
}

function closeAll(except = ''): void {
  const next: Record<string, number> = {}
  Object.entries(offsets.value).forEach(([id, v]) => {
    if (id !== except && v !== 0) next[id] = 0
  })
  offsets.value = next
}

function onPointerDown(e: PointerEvent, h: HistoryItem): void {
  if (e.button !== 0) return
  closeAll(h.runId)
  drag = { id: h.runId, startX: e.clientX, startY: e.clientY, base: offsetOf(h.runId), moved: false }
  // 捕获指针：滑出条目边界也能继续跟随
  try {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  } catch {
    // 某些旧版浏览器不支持指针捕获，退化为普通事件
  }
}

function onPointerMove(e: PointerEvent): void {
  if (!drag) return
  const dx = e.clientX - drag.startX
  const dy = e.clientY - drag.startY
  if (!drag.moved) {
    // 纵向意图优先：不拦截列表滚动
    if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 6) {
      drag = null
      return
    }
    if (Math.abs(dx) < 6) return
    drag.moved = true
    draggingId.value = drag.id
  }
  const next = Math.max(-SWIPE_WIDTH, Math.min(0, drag.base + dx))
  setOffset(drag.id, next)
}

function onPointerUp(): void {
  if (!drag) return
  const id = drag.id
  const moved = drag.moved
  const open = offsetOf(id) < -OPEN_THRESHOLD
  setOffset(id, open ? -SWIPE_WIDTH : 0)
  drag = null
  // 先解除「拖动中」再吸附，让松手后走过渡动画
  draggingId.value = ''
  // 刚滑动过就抑制紧随其后的 click，避免误打开运行详情
  if (moved) {
    lastDragAt = Date.now()
  }
}

function onOpen(h: HistoryItem): void {
  if (Date.now() - lastDragAt < 250) return
  // 已滑开删除的条目：点一下先收回，避免误打开运行详情
  if (offsetOf(h.runId) < 0) {
    closeAll()
    return
  }
  closeAll()
  emit('open', h)
}

function askDelete(h: HistoryItem): void {
  if (statusTone(h.status) === 'busy') return
  emit('delete', h)
}

function onKeydown(e: KeyboardEvent, item: HistoryItem): void {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault()
    onOpen(item)
  } else if (e.key === 'Delete' || e.key === 'Backspace') {
    e.preventDefault()
    askDelete(item)
  }
}
</script>

<template>
  <section class="history-panel" aria-label="构建历史">
    <div class="hp-head">
      <h2 class="hp-title">构建历史</h2>
      <span class="hp-count">{{ visible.length }} 条<template v-if="runningCount"> · {{ runningCount }} 进行中</template></span>
      <button type="button" class="hp-refresh" :disabled="loading" title="刷新" @click="emit('refresh')">
        <el-icon :class="{ 'is-loading': loading }"><Refresh /></el-icon>
      </button>
    </div>

    <div class="hp-filters">
      <div class="hp-chips" role="group" aria-label="按状态筛选">
        <button
          v-for="f in STATUS_FILTERS" :key="f.key || 'all'"
          type="button" class="hp-chip" :class="{ active: statusFilter === f.key }"
          @click="statusFilter = f.key"
        >
          {{ f.label }}<em v-if="countOf(f.key)">{{ countOf(f.key) }}</em>
        </button>
      </div>
      <input v-model="keyword" class="hp-search" type="search" placeholder="搜提交 / 序号" />
    </div>

    <div class="hp-body">
      <div
        v-for="h in visible"
        :key="h.runId"
        class="hist-swipe"
      >
        <div class="hist-swipe-actions">
          <button
            type="button"
            class="hist-del"
            :disabled="statusTone(h.status) === 'busy'"
            :title="statusTone(h.status) === 'busy' ? '运行中不能删除' : '删除这条历史'"
            @click="askDelete(h)"
          >
            <el-icon><Delete /></el-icon>
            <span>删除</span>
          </button>
        </div>
        <button
          type="button"
          class="hist-item"
          :class="{ active: h.runId === activeRunId, dragging: draggingId === h.runId }"
          :style="{ transform: `translateX(${offsetOf(h.runId)}px)` }"
          @pointerdown="onPointerDown($event, h)"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
          @click="onOpen(h)"
          @keydown="onKeydown($event, h)"
        >
          <span class="hist-dot" :class="statusTone(h.status)" />
          <span class="hist-main">
            <span class="hist-line1">
              <span class="hist-pipeline">
                <em v-if="h.seq" class="hist-seq">#{{ h.seq }}</em>
                {{ pipelineLabel(h.pipelineId) }}
                <em class="hist-env">{{ envShort(h.env) }}</em>
              </span>
              <span class="hist-status" :class="statusTone(h.status)">{{ statusLabel(h.status) }}</span>
            </span>
            <span class="hist-line2">
              <span class="mono">{{ h.gitSha.slice(0, 8) }}</span>
              <span>{{ h.stageCount }} 阶段</span>
              <span v-if="h.durationMs">{{ formatDuration(h.durationMs) }}</span>
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
      </div>

      <p v-if="!loading && !visible.length" class="hp-empty">
        {{ items.length ? '没有符合筛选条件的构建' : '暂无构建历史，先在上方运行一次流水线' }}
      </p>

      <button
        v-if="hasMore" type="button" class="hp-more"
        :disabled="loading" @click="emit('more')"
      >
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
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
  align-items: center;
  gap: 8px;
  padding: 0 12px 8px;
}
.hp-chips {
  display: inline-flex;
  flex: none;
  gap: 2px;
  padding: 2px;
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.hp-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 22px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-pill);
}
.hp-chip:hover {
  color: var(--ink);
}
.hp-chip.active {
  color: var(--accent-ink);
  background: var(--panel);
  box-shadow: 0 1px 2px rgb(0 0 0 / 8%);
}
.hp-chip em {
  font-style: normal;
  color: var(--ink-faint);
}
.hp-search {
  flex: 1;
  min-width: 0;
  height: 24px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink);
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
}
.hp-search:focus {
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.hp-more {
  flex: none;
  height: 26px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 1px dashed var(--line-soft);
  border-radius: var(--radius-sm);
}
.hp-more:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.hp-more:disabled {
  cursor: not-allowed;
  opacity: 0.5;
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
.hist-swipe {
  position: relative;
  flex: none;
  overflow: hidden;
  border-radius: var(--radius-sm);
}
.hist-swipe-actions {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: stretch;
  justify-content: flex-end;
  /* 实心底：滑动过程中不会透出被移走的那一行内容 */
  background: var(--surface-sunken);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}
.hist-del {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: center;
  justify-content: center;
  width: 76px;
  font-family: inherit;
  font-size: 11px;
  font-weight: 500;
  color: #fff;
  cursor: pointer;
  background: var(--danger);
  border: 0;
}
.hist-del .el-icon {
  font-size: 15px;
}
.hist-del:hover:not(:disabled) {
  background: color-mix(in srgb, var(--danger) 84%, #000);
}
.hist-del:disabled {
  cursor: not-allowed;
  color: var(--ink-faint);
  background: var(--surface-sunken);
}
.hist-item {
  display: flex;
  position: relative;
  z-index: 1;
  gap: 9px;
  align-items: center;
  width: 100%;
  padding: 9px 10px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  touch-action: pan-y;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition:
    background-color var(--dur-fast) ease,
    border-color var(--dur-fast) ease,
    transform var(--dur-fast) ease;
}
/* 拖动时手势优先：关掉 transform 过渡，松手后再吸附 */
.hist-item.dragging {
  transition: background-color var(--dur-fast) ease, border-color var(--dur-fast) ease;
}
.hist-item:hover {
  /* 实心底：半透明会让滑出来的删除区透上来 */
  background: color-mix(in srgb, #000 4%, var(--panel));
}
.hist-item.active {
  background: color-mix(in srgb, var(--accent) 12%, var(--panel));
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
.hist-seq {
  margin-right: 5px;
  padding: 0 4px;
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-xs);
}
.hist-env {
  margin-left: 5px;
  padding: 0 5px;
  font-size: 10px;
  font-style: normal;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
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
