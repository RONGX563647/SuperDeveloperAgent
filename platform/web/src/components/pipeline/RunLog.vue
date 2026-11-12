<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, CopyDocument, Download, Search } from '@element-plus/icons-vue'
import { fetchRunLog } from '../../api/pipeline'
import { errorMessage } from '../../utils/feedback'

/**
 * 运行日志（增量追加渲染）：
 *
 * - 只拉 offset 之后的新文本（GET /runs/{id}/log?offset=），追加到内存，避免每秒全量重绘；
 * - 行号 + 错误行高亮（ERROR/FAIL/Exception 等）+ 关键字过滤；
 * - 跟随模式：默认贴底；用户手动上滚自动暂停，出现"跳到最新"按钮；
 * - 复制 / 下载整段日志（解决"日志只能看不能带走"）。
 */
interface Props {
  runId: string
  /** 运行是否仍在进行（决定是否继续增量拉取）。 */
  running: boolean
}

const props = defineProps<Props>()

const text = shallowRef('')
const offset = ref(0)
const loading = shallowRef(false)
const errorText = shallowRef('')
const keyword = shallowRef('')
const follow = ref(true)
const scroller = ref<HTMLElement | null>(null)

let timer: ReturnType<typeof setInterval> | null = null
const POLL_MS = 800

const ERROR_PATTERN = /(\bERROR\b|\bFAIL(ED|URE)?\b|Exception|panic:|error:|\bfatal\b)/i

interface LogLine {
  no: number
  text: string
  error: boolean
}

const allLines = computed<LogLine[]>(() =>
  text.value.split('\n')
    .filter((l, i, arr) => !(i === arr.length - 1 && l === ''))
    .map((l, i) => ({ no: i + 1, text: l, error: ERROR_PATTERN.test(l) })))

const lines = computed<LogLine[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return allLines.value
  return allLines.value.filter((l) => l.text.toLowerCase().includes(kw))
})

const errorCount = computed(() => allLines.value.filter((l) => l.error).length)
const lineNoWidth = computed(() => `${String(allLines.value.length).length + 1}ch`)

/** 拉一段增量；首次调用从 offset=0 开始，等价于整段加载。 */
async function pull(): Promise<void> {
  if (!props.runId) return
  try {
    const slice = await fetchRunLog(props.runId, offset.value)
    errorText.value = ''
    if (slice.text) {
      text.value += slice.text
      offset.value = slice.end
      if (follow.value) await scrollToBottom()
    } else {
      offset.value = slice.end
    }
    if (slice.complete) stopTimer()
  } catch (e) {
    errorText.value = errorMessage(e, '日志加载失败')
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const el = scroller.value
  if (el) el.scrollTop = el.scrollHeight
}

/** 用户上滚 → 暂停跟随；回到底部附近 → 恢复。 */
function onScroll(): void {
  const el = scroller.value
  if (!el) return
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24
  if (nearBottom && !follow.value) follow.value = true
  else if (!nearBottom && follow.value) follow.value = false
}

function jumpToLatest(): void {
  follow.value = true
  void scrollToBottom()
}

function stopTimer(): void {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

async function restart(): Promise<void> {
  stopTimer()
  text.value = ''
  offset.value = 0
  follow.value = true
  loading.value = true
  await pull()
  loading.value = false
  if (props.running) {
    timer = setInterval(() => void pull(), POLL_MS)
  }
}

watch(() => props.runId, () => void restart(), { immediate: true })
watch(() => props.running, (running) => {
  if (!running) {
    stopTimer()
    void pull() // 终态后再拉一次，补齐尾部输出
    return
  }
  if (timer === null) timer = setInterval(() => void pull(), POLL_MS)
})

onBeforeUnmount(stopTimer)

async function copyAll(): Promise<void> {
  if (!text.value) return
  try {
    await navigator.clipboard.writeText(text.value)
    ElMessage.success('日志已复制')
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板）')
  }
}

function download(): void {
  if (!text.value) return
  const blob = new Blob([text.value], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${props.runId.slice(0, 24) || 'run'}.log`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <section class="run-log" aria-label="运行日志">
    <div class="log-head">
      <span class="log-title">日志</span>
      <span class="log-stat">{{ allLines.length }} 行</span>
      <span v-if="errorCount" class="log-errors">{{ errorCount }} 处错误</span>

      <div class="log-search">
        <el-icon class="search-icon"><Search /></el-icon>
        <input v-model="keyword" type="text" class="search-input" placeholder="过滤关键字…">
      </div>

      <div class="log-actions">
        <button type="button" class="log-btn" :disabled="!text" @click="copyAll">
          <el-icon><CopyDocument /></el-icon> 复制
        </button>
        <button type="button" class="log-btn" :disabled="!text" @click="download">
          <el-icon><Download /></el-icon> 下载
        </button>
        <label class="follow-toggle">
          <input v-model="follow" type="checkbox" @change="follow && jumpToLatest()">
          跟随
        </label>
      </div>
    </div>

    <div v-if="errorText" class="log-error">{{ errorText }}</div>

    <div ref="scroller" class="log-scroll" @scroll.passive="onScroll">
      <p v-if="loading && !allLines.length" class="log-hint">加载中…</p>
      <p v-else-if="!allLines.length" class="log-hint">（暂无日志输出）</p>
      <template v-else>
        <div v-for="l in lines" :key="l.no" class="log-line" :class="{ error: l.error }">
          <span class="ln" :style="{ width: lineNoWidth }">{{ l.no }}</span>
          <span class="lc">{{ l.text || ' ' }}</span>
        </div>
        <p v-if="keyword && !lines.length" class="log-hint">没有匹配「{{ keyword }}」的行</p>
      </template>

      <button
        v-if="!follow"
        type="button" class="jump-latest"
        @click="jumpToLatest"
      >
        <el-icon><ArrowDown /></el-icon> 跳到最新
      </button>
    </div>
  </section>
</template>

<style scoped>
.run-log {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 240px;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.log-head {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line-soft);
}
.log-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.log-stat {
  font-size: 11px;
  color: var(--ink-faint);
}
.log-errors {
  padding: 1px 7px;
  font-size: 10.5px;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: var(--radius-pill);
}
.log-search {
  position: relative;
  display: flex;
  flex: 1 1 160px;
  max-width: 280px;
  align-items: center;
  margin-left: auto;
}
.search-icon {
  position: absolute;
  left: 8px;
  font-size: 12px;
  color: var(--ink-faint);
  pointer-events: none;
}
.search-input {
  width: 100%;
  height: 26px;
  padding: 0 8px 0 26px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
}
.search-input:focus {
  background: var(--panel);
  border-color: rgb(77 130 247 / 55%);
}
.log-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
}
.log-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.log-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.log-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.follow-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  user-select: none;
}
.log-error {
  padding: 6px 12px;
  font-size: 12px;
  color: var(--danger);
  background: var(--danger-soft);
}
.log-scroll {
  position: relative;
  flex: 1 1 0;
  min-height: 0;
  overflow: auto;
  padding: 8px 0;
  background: var(--code-bg);
}
.log-line {
  display: flex;
  padding: 0 12px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.65;
  color: var(--ink-soft);
  white-space: pre;
}
.log-line:hover {
  background: var(--surface-hover);
}
.log-line.error {
  color: var(--danger);
  background: var(--danger-soft);
}
.ln {
  flex: none;
  padding-right: 12px;
  color: var(--ink-faint);
  text-align: right;
  user-select: none;
}
.log-hint {
  margin: 0;
  padding: 16px 12px;
  font-size: 12px;
  color: var(--ink-faint);
}
.jump-latest {
  position: sticky;
  bottom: 12px;
  left: 50%;
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 8px auto 0;
  padding: 5px 12px;
  font-family: inherit;
  font-size: 12px;
  color: #fff;
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-pill);
  box-shadow: var(--shadow-2);
}
</style>
