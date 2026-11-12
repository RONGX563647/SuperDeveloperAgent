<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Search } from '@element-plus/icons-vue'
import type { ToolActivity, TraceStep, TraceTurn } from '../../types'
import { agentProfile } from '../../api/agent'
import { formatAmount, formatCost, formatTokens } from '../../utils/deepseekPricing'
import { errorMessage } from '../../utils/feedback'
import { formatClockSeconds, formatDateTimeMillis } from '../../utils/status'

/**
 * 轨迹视图：左侧密集步骤列表 + 右侧详情检查器（Detail Inspector）。
 *
 * <p>左侧每个工具/助手步骤只占一行（类型 · 名称 · 参数摘要 → 结果摘要），
 * 点某一步右侧展示完整执行参数：概述 / 参数 / 结果 / 计时 四个页签，参数按 JSON 高亮。
 * 点某一轮则展示该轮的用户输入、耗时与步骤统计。
 *
 * <p>耗时都是按事件时间戳相邻差值估算（后端事件不带开始时间），页签里写明了这一点。
 */
const props = defineProps<{
  turns: TraceTurn[]
  activeId: string
  tools: Record<string, ToolActivity>
  projectId: string
  env: string
  streaming?: boolean
}>()

const emit = defineEmits<{ (e: 'locate', id: string): void }>()

type InspTab = 'overview' | 'args' | 'result' | 'timing'

const tab = ref<'trace' | 'system'>('trace')
const query = ref('')
/** 当前选中：轮次，或轮次里的某一步。 */
const sel = ref<{ turnId: string; stepId: string }>({ turnId: '', stepId: '' })
const inspTab = ref<InspTab>('overview')
const prompt = ref('')
const promptLoading = ref(false)
const promptError = ref('')

watch(() => props.activeId, (id) => {
  if (id) sel.value = { turnId: id, stepId: '' }
}, { immediate: true })

const selTurn = computed(() =>
  props.turns.find((t) => t.id === sel.value.turnId) ?? null)

const selStep = computed(() => {
  if (!sel.value.stepId) return null
  return selTurn.value?.items.find((s) => s.id === sel.value.stepId) ?? null
})

/** 选到工具步骤直接给「参数」页：用户最常看的就是入参。 */
watch(selStep, (step) => {
  inspTab.value = step?.kind === 'tool' ? 'args' : 'overview'
})

const filteredTurns = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.turns
  return props.turns
    .map((turn) => ({
      turn,
      steps: turn.items.filter((s) =>
        `${s.title} ${s.text} ${argsOf(s)} ${resultOf(s)}`.toLowerCase().includes(q)),
    }))
    .filter(({ turn, steps }) =>
      steps.length > 0 || turn.userText.toLowerCase().includes(q))
    .map(({ turn, steps }) => ({
      ...turn,
      items: steps.length ? steps : turn.items,
    }))
})

const stats = computed(() => {
  const first = props.turns[0]
  const last = props.turns[props.turns.length - 1]
  return {
    durationMs: first && last ? Math.max(0, last.endedAt - first.startedAt) : 0,
    turns: props.turns.length,
    calls: props.turns.reduce((n, t) => n + t.tools, 0),
    cost: props.turns.reduce((sum, t) => sum + (t.cost?.totalCost ?? 0), 0),
  }
})

function turnMs(turn: TraceTurn): number {
  return Math.max(0, turn.endedAt - turn.startedAt)
}

/** 概览带悬浮提示：轮次 + 耗时 + 步数 + 本轮费用。 */
function roundTitle(turn: TraceTurn): string {
  const money = turn.cost ? ` · ￥${formatAmount(turn.cost.totalCost)}` : ''
  return `第 ${turn.index} 轮 · ${duration(turnMs(turn))} · ${turn.steps} 步${money}`
}

function duration(ms: number): string {
  if (ms <= 0) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`
}

function kindLabel(step: TraceStep): string {
  if (step.kind === 'tool') return '工具'
  if (step.kind === 'error') return '错误'
  return '助手'
}

function stateText(status?: string): string {
  return ({
    RUNNING: '执行中',
    COMPLETED: '完成',
    FAILED: '失败'
  } as Record<string, string>)[status ?? ''] ?? ''
}

function argsOf(step: TraceStep): string {
  return step.toolCallId ? (props.tools[step.toolCallId]?.arguments ?? '') : ''
}

function resultOf(step: TraceStep): string {
  return step.toolCallId ? (props.tools[step.toolCallId]?.resultText ?? '') : ''
}

/** 原始工具名（run_action / shell_command），展示名之外给排查用。 */
function rawToolName(step: TraceStep): string {
  return step.toolCallId ? (props.tools[step.toolCallId]?.toolName ?? '') : ''
}

function errorCode(step: TraceStep): string {
  return step.toolCallId ? (props.tools[step.toolCallId]?.code ?? '') : ''
}

/** 入参顶层键：一眼看出这次调用带了哪些字段（比纯字符数有用）。 */
function argKeys(raw: string): string[] {
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return Object.keys(parsed as Record<string, unknown>)
    }
  } catch {
    // 非法 JSON：不展示键
  }
  return []
}

function textMeta(raw: string): { chars: number; lines: number } {
  return { chars: raw.length, lines: raw ? raw.split('\n').length : 0 }
}

/** 一行预览：工具看参数，助手看正文。 */
function previewOf(step: TraceStep): string {
  const source = step.kind === 'tool' ? argsOf(step) : step.text
  const oneLine = (source || '').replace(/\s+/g, ' ').trim()
  if (!oneLine || oneLine === '{}') return step.text && step.kind === 'tool' ? step.text : ''
  return oneLine
}

function stepIndex(turn: TraceTurn, step: TraceStep): number {
  return turn.items.indexOf(step) + 1
}

function selectRound(turn: TraceTurn): void {
  sel.value = { turnId: turn.id, stepId: '' }
}

function selectStep(turn: TraceTurn, step: TraceStep): void {
  sel.value = { turnId: turn.id, stepId: step.id }
}

/** 概览带定位：选中该轮并滚到左侧对应位置。 */
function focusTurn(id: string): void {
  sel.value = { turnId: id, stepId: '' }
  document.getElementById(`trace-round-${id}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

function jsonText(raw: string): string {
  const text = (raw ?? '').trim()
  if (!text) return ''
  if (!text.startsWith('{') && !text.startsWith('[')) return text
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

/** JSON 语法高亮：先转义 HTML，再把 "key": / "value" / 数字 / 布尔 包成 span。 */
function highlight(raw: string): string {
  const code = jsonText(raw)
  if (!code) return ''
  const escaped = code.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return escaped.replace(
    /("(?:\\u[\da-fA-F]{4}|\\[^u]|[^\\"])*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (match, str: string, colon: string, bool: string, num: string) => {
      if (str) {
        return colon
          ? `<span class="j-key">${str}</span>${colon}`
          : `<span class="j-str">${str}</span>`
      }
      if (bool) return `<span class="j-bool">${bool}</span>`
      if (num) return `<span class="j-num">${num}</span>`
      return match
    })
}

async function copy(text: string): Promise<void> {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板）')
  }
}

async function loadPrompt(): Promise<void> {
  if (prompt.value || promptLoading.value) return
  promptLoading.value = true
  promptError.value = ''
  try {
    prompt.value = (await agentProfile(props.projectId, props.env)).prompt
  } catch (e) {
    promptError.value = errorMessage(e, '系统提示词加载失败')
  } finally {
    promptLoading.value = false
  }
}

watch(tab, (value) => {
  if (value === 'system') void loadPrompt()
})
watch(() => `${props.projectId}:${props.env}`, () => {
  prompt.value = ''
  promptError.value = ''
})
</script>

<template>
  <div class="trace-view">
    <div class="trace-bar">
      <div class="stats">
        <span class="stat"><b class="mono">{{ duration(stats.durationMs) }}</b><em>总时长</em></span>
        <span class="stat"><b class="mono">{{ stats.turns }}</b><em>轮次</em></span>
        <span class="stat"><b class="mono">{{ stats.calls }}</b><em>工具调用</em></span>
        <span v-if="stats.cost > 0" class="stat">
          <b class="mono">￥{{ formatAmount(stats.cost) }}</b><em>总费用</em>
        </span>
        <span v-if="streaming" class="live"><i />进行中</span>
      </div>
      <div class="bar-right">
        <label class="search">
          <el-icon><Search /></el-icon>
          <input v-model="query" type="text" placeholder="搜索问题、工具名或参数">
        </label>
        <div class="seg">
          <button type="button" :class="{ on: tab === 'trace' }" @click="tab = 'trace'">执行轨迹</button>
          <button type="button" :class="{ on: tab === 'system' }" @click="tab = 'system'">系统提示词</button>
        </div>
      </div>
    </div>

    <template v-if="tab === 'trace'">
      <!-- 概览带 -->
      <div v-if="turns.length" class="overview">
        <div class="ov-strip">
          <button
            v-for="turn in turns"
            :key="turn.id"
            type="button"
            class="ov-turn"
            :class="{ on: turn.id === sel.turnId, bad: turn.failed }"
            :style="{ flexGrow: Math.max(turnMs(turn), 40) }"
            :title="roundTitle(turn)"
            @click="focusTurn(turn.id)"
          >
            <span class="ov-input" />
            <span
              v-for="(item, i) in turn.items"
              :key="i"
              class="ov-seg"
              :class="item.kind"
              :style="{ flexGrow: Math.max(item.durationMs, 12) }"
            />
          </button>
        </div>
        <div class="legend mono">
          <span><i class="input" />输入</span>
          <span><i class="model" />模型</span>
          <span><i class="tool" />工具</span>
          <span class="estimate">点步骤看参数 · 耗时按事件时间估算</span>
        </div>
      </div>

      <div class="trace-body">
        <!-- 左：按轮分组的密集步骤列表 -->
        <div class="trace-left">
          <div v-if="!turns.length" class="empty">
            <p>还没有可追踪的执行记录。</p>
            <span>先在对话里问一个问题，这里会按轮次列出每一步的模型输出与工具调用。</span>
          </div>

          <template v-else>
            <section
              v-for="turn in filteredTurns"
              :id="`trace-round-${turn.id}`"
              :key="turn.id"
              class="round"
            >
              <button
                type="button"
                class="round-head"
                :class="{ on: turn.id === sel.turnId && !sel.stepId }"
                @click="selectRound(turn)"
              >
                <span class="round-no mono">{{ String(turn.index).padStart(2, '0') }}</span>
                <span class="round-q">{{ turn.question || '（无文本）' }}</span>
                <span class="round-meta mono">
                  {{ formatClockSeconds(turn.at) }} · {{ turn.steps }} 步 · {{ turn.tools }} 工具 · {{ duration(turnMs(turn)) }}
                </span>
                <span v-if="turn.cost" class="round-money mono">
                  ￥{{ formatAmount(turn.cost.totalCost) }}
                </span>
                <span v-if="turn.failed" class="badge">失败</span>
              </button>

              <ul class="steps">
                <li v-for="step in turn.items" :key="step.id">
                  <button
                    type="button"
                    class="step-row"
                    :class="[step.kind, { on: step.id === sel.stepId }]"
                    @click="selectStep(turn, step)"
                  >
                    <span class="step-kind">{{ kindLabel(step) }}</span>
                    <span v-if="step.kind === 'tool'" class="step-name mono">{{ step.title }}</span>
                    <span class="step-preview mono">{{ previewOf(step) }}</span>
                    <span
                      v-if="step.kind === 'tool'"
                      class="step-state mono"
                      :class="(step.status || 'running').toLowerCase()"
                    >{{ stateText(step.status) }}</span>
                    <span v-else class="step-cost mono">{{ duration(step.durationMs) }}</span>
                    <!-- 每步费用：模型输出步才有，工具步无 -->
                    <span v-if="step.cost" class="step-money mono">
                      ￥{{ formatAmount(step.cost.totalCost) }}
                    </span>
                  </button>
                </li>
                <li v-if="!turn.items.length" class="step-empty">这一轮还没有步骤</li>
              </ul>
            </section>
            <p v-if="!filteredTurns.length" class="no-match">没有匹配的步骤</p>
          </template>
        </div>

        <!-- 右：详情检查器 -->
        <aside class="inspector">
          <!-- 步骤详情 -->
          <template v-if="selStep && selTurn">
            <header class="insp-head">
              <span class="insp-kind" :class="selStep.kind">{{ kindLabel(selStep) }}</span>
              <span class="insp-title mono">
                {{ selStep.kind === 'text' ? '模型输出' : selStep.title }}
              </span>
              <span
                v-if="selStep.kind === 'tool' && selStep.status"
                class="insp-status" :class="selStep.status.toLowerCase()"
              >{{ stateText(selStep.status) }}</span>
            </header>
            <p class="insp-sub mono">
              <template v-if="rawToolName(selStep)">{{ rawToolName(selStep) }} · </template>
              第 {{ selTurn.index }} 轮 · 第 {{ stepIndex(selTurn, selStep) }} 步
              · {{ formatClockSeconds(selStep.at) }} · {{ duration(selStep.durationMs) }}
              <template v-if="selStep.toolCallId"> · {{ selStep.toolCallId }}</template>
            </p>

            <nav class="insp-tabs">
              <button
                v-for="item in ([
                  ['overview', '概述'], ['args', '参数'], ['result', '结果'], ['timing', '计时']
                ] as Array<[InspTab, string]>)"
                :key="item[0]"
                type="button"
                :class="{ on: inspTab === item[0] }"
                @click="inspTab = item[0]"
              >{{ item[1] }}</button>
            </nav>

            <div class="insp-body">
              <template v-if="inspTab === 'overview'">
                <dl class="kv">
                  <dt>类型</dt>
                  <dd>{{ kindLabel(selStep) }}调用</dd>
                  <dt>名称</dt>
                  <dd class="mono">{{ selStep.kind === 'text' ? 'assistant' : selStep.title }}</dd>
                  <template v-if="rawToolName(selStep)">
                    <dt>工具标识</dt>
                    <dd class="mono">{{ rawToolName(selStep) }}</dd>
                  </template>
                  <template v-if="selStep.kind === 'tool'">
                    <dt>状态</dt>
                    <dd :class="selStep.status?.toLowerCase()">{{ stateText(selStep.status) || '—' }}</dd>
                    <dt v-if="errorCode(selStep)">错误码</dt>
                    <dd v-if="errorCode(selStep)" class="danger">{{ errorCode(selStep) }}</dd>
                  </template>
                  <dt>所属轮次</dt>
                  <dd>第 {{ selTurn.index }} 轮 · 第 {{ stepIndex(selTurn, selStep) }} 步</dd>
                  <dt>开始时间</dt>
                  <dd class="mono">{{ formatDateTimeMillis(selStep.at) }}</dd>
                  <dt>估算耗时</dt>
                  <dd class="mono">{{ duration(selStep.durationMs) }}</dd>
                  <template v-if="selStep.cost">
                    <dt>本步费用</dt>
                    <dd class="mono">{{ formatCost(selStep.cost.totalCost) }}</dd>
                    <dt>输入 / 输出</dt>
                    <dd class="mono">
                      {{ formatCost(selStep.cost.inputCost) }} / {{ formatCost(selStep.cost.outputCost) }}
                    </dd>
                    <dt>Token</dt>
                    <dd class="mono">
                      {{ formatTokens(selStep.cost.hitTokens + selStep.cost.missTokens) }} in /
                      {{ formatTokens(selStep.cost.completionTokens) }} out
                    </dd>
                    <dt>时段</dt>
                    <dd>{{ selStep.cost.isPeak ? '高峰' : '空闲' }}</dd>
                  </template>
                  <template v-if="selStep.kind === 'tool'">
                    <dt>入参</dt>
                    <dd class="mono">
                      {{ textMeta(argsOf(selStep)).chars }} 字符 /
                      {{ textMeta(argsOf(selStep)).lines }} 行
                    </dd>
                    <dt>返回</dt>
                    <dd class="mono">
                      {{ textMeta(resultOf(selStep)).chars }} 字符 /
                      {{ textMeta(resultOf(selStep)).lines }} 行
                    </dd>
                  </template>
                  <template v-else>
                    <dt>正文</dt>
                    <dd class="mono">{{ textMeta(selStep.text).chars }} 字符</dd>
                  </template>
                </dl>

                <template v-if="selStep.kind === 'tool'">
                  <div class="code-head">
                    <span class="mono">入参字段（{{ argKeys(argsOf(selStep)).length }}）</span>
                  </div>
                  <div v-if="argKeys(argsOf(selStep)).length" class="keys">
                    <code v-for="key in argKeys(argsOf(selStep))" :key="key" class="key mono">{{ key }}</code>
                  </div>
                  <p v-else class="muted">
                    {{ argsOf(selStep) ? '入参不是 JSON 对象，切到「参数」看原文。' : '该工具没有入参。' }}
                  </p>

                  <div class="code-head"><span class="mono">入参快照</span></div>
                  <pre
                    v-if="argsOf(selStep)" class="code mono snapshot"
                    v-html="highlight(argsOf(selStep))"
                  />
                  <p v-else class="muted">（无）</p>

                  <div class="code-head"><span class="mono">返回摘要</span></div>
                  <pre v-if="resultOf(selStep)" class="code mono snapshot">{{ resultOf(selStep) }}</pre>
                  <p v-else class="muted">（无输出）</p>
                </template>
                <template v-else>
                  <div class="code-head"><span class="mono">正文预览</span></div>
                  <pre class="code mono snapshot">{{ selStep.text || '（空输出）' }}</pre>
                </template>
              </template>

              <template v-else-if="inspTab === 'args'">
                <div class="code-head">
                  <span class="mono">输入参数</span>
                  <button v-if="argsOf(selStep)" type="button" class="mini" @click="copy(argsOf(selStep))">
                    <el-icon><CopyDocument /></el-icon>复制
                  </button>
                </div>
                <pre
                  v-if="argsOf(selStep)"
                  class="code mono"
                  v-html="highlight(argsOf(selStep))"
                />
                <p v-else class="muted">
                  {{ selStep.kind === 'tool' ? '该工具没有入参' : '模型输出没有入参，切到「结果」看正文' }}
                </p>
              </template>

              <template v-else-if="inspTab === 'result'">
                <div class="code-head">
                  <span class="mono">{{ selStep.kind === 'tool' ? '工具返回' : '模型输出' }}</span>
                  <button
                    v-if="selStep.kind === 'tool' ? resultOf(selStep) : selStep.text"
                    type="button" class="mini"
                    @click="copy(selStep.kind === 'tool' ? resultOf(selStep) : selStep.text)"
                  >
                    <el-icon><CopyDocument /></el-icon>复制
                  </button>
                </div>
                <template v-if="selStep.kind === 'tool'">
                  <pre v-if="resultOf(selStep)" class="code mono" v-html="highlight(resultOf(selStep))" />
                  <p v-else class="muted">（无输出）</p>
                </template>
                <pre v-else class="code mono result-text">{{ selStep.text || '（空输出）' }}</pre>
              </template>

              <template v-else>
                <dl class="kv">
                  <dt>开始时间</dt><dd class="mono">{{ formatDateTimeMillis(selStep.at) }}</dd>
                  <dt>结束时间</dt><dd class="mono">{{ formatDateTimeMillis(selStep.at + selStep.durationMs) }}</dd>
                  <dt>估算耗时</dt><dd class="mono">{{ duration(selStep.durationMs) }}</dd>
                  <dt>计时来源</dt><dd>相邻事件时间戳之差</dd>
                </dl>
                <p class="hint">
                  后端事件只带时间戳、不带开始/结束时刻，因此这里的耗时是「本条事件到下一条事件」的间隔，
                  用于判断慢在模型还是慢在工具，不作为精确计时。
                </p>
              </template>
            </div>
          </template>

          <!-- 轮次详情 -->
          <template v-else-if="selTurn">
            <header class="insp-head">
              <span class="insp-kind round-kind">轮次</span>
              <span class="insp-title">第 {{ selTurn.index }} 轮</span>
              <span v-if="selTurn.failed" class="insp-status failed">失败</span>
            </header>
            <p class="insp-sub mono">
              {{ formatDateTimeMillis(selTurn.startedAt) }} · {{ duration(turnMs(selTurn)) }}
            </p>
            <div class="insp-body">
              <div class="code-head"><span class="mono">用户输入</span></div>
              <pre class="code mono result-text">{{ selTurn.userText || '（空）' }}</pre>
              <dl class="kv">
                <dt>步骤数</dt><dd class="mono">{{ selTurn.steps }}</dd>
                <dt>工具调用</dt><dd class="mono">{{ selTurn.tools }}</dd>
                <dt>模型输出</dt><dd class="mono">{{ selTurn.items.filter((s) => s.kind === 'text').length }}</dd>
                <dt>总耗时</dt><dd class="mono">{{ duration(turnMs(selTurn)) }}</dd>
                <template v-if="selTurn.cost">
                  <dt>本轮费用</dt>
                  <dd class="mono">{{ formatCost(selTurn.cost.totalCost) }}</dd>
                  <dt>输入（命中）</dt>
                  <dd class="mono">{{ formatTokens(selTurn.cost.hitTokens) }} tokens</dd>
                  <dt>输入（未命中）</dt>
                  <dd class="mono">{{ formatTokens(selTurn.cost.missTokens) }} tokens</dd>
                  <dt>输出</dt>
                  <dd class="mono">{{ formatTokens(selTurn.cost.completionTokens) }} tokens</dd>
                </template>
              </dl>
              <div class="code-head"><span class="mono">工具清单</span></div>
              <ul v-if="selTurn.tools" class="tool-list">
                <li v-for="step in selTurn.items.filter((s) => s.kind === 'tool')" :key="step.id">
                  <button type="button" class="tool-item" @click="selectStep(selTurn, step)">
                    <span class="mono">{{ step.title }}</span>
                    <span class="tool-item-state" :class="(step.status || 'running').toLowerCase()">
                      {{ stateText(step.status) }}
                    </span>
                    <span class="mono tool-item-cost">{{ duration(step.durationMs) }}</span>
                  </button>
                </li>
              </ul>
              <p v-else class="muted">这一轮没有工具调用。</p>
            </div>
<!--            <div class="insp-foot">-->
<!--              <button type="button" class="ghost-btn" @click="emit('locate', selTurn.id)">-->
<!--                在对话中定位-->
<!--              </button>-->
<!--            </div>-->
          </template>

          <div v-else class="insp-empty">
            <p>点击左侧的轮次或步骤</p>
            <span>这里会展示完整的执行参数、工具返回与计时信息。</span>
          </div>
        </aside>
      </div>
    </template>

    <!-- 系统提示词 -->
    <div v-else class="system-pane">
      <div class="system-inner">
        <div class="system-head">
          <span class="mono">devops-agent · 初始系统提示词</span>
          <button v-if="prompt" type="button" class="mini" @click="copy(prompt)">
            <el-icon><CopyDocument /></el-icon>复制
          </button>
        </div>
        <pre v-if="prompt" class="code prompt">{{ prompt }}</pre>
        <p v-else-if="promptLoading" class="muted">加载中…</p>
        <p v-else class="muted">{{ promptError || '切换到本标签后自动加载' }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trace-view {
  --model-ink: #5b48bd;
  --model-fill: #a99bf2;
  --tool-ink: #8f6116;
  --tool-fill: #e6b775;

  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
  background: var(--surface-raised);
}
.mono {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}

/* ---- 顶部 ---- */
.trace-bar {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 24px 10px;
}
.stats {
  display: flex;
  align-items: baseline;
  gap: 18px;
}
.stat {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
}
.stat b {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.stat em {
  font-size: 10.5px;
  font-style: normal;
  color: var(--ink-faint);
}
.live {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--accent-ink);
}
.live i {
  width: 5px;
  height: 5px;
  background: var(--accent);
  border-radius: 50%;
  animation: trace-pulse 1.4s ease-in-out infinite;
}
@keyframes trace-pulse {
  50% { opacity: 0.3; }
}
.bar-right {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
}
.search {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 200px;
  height: 26px;
  padding: 0 9px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
}
.search:focus-within {
  background: var(--surface-raised);
  border-color: var(--line-strong);
}
.search input {
  width: 100%;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink);
  background: transparent;
  border: 0;
  outline: none;
}
.search input::placeholder {
  color: var(--ink-faint);
}
.seg {
  display: flex;
  flex: none;
  padding: 2px;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.seg button {
  height: 22px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-muted);
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.seg button.on {
  color: var(--ink);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}

/* ---- 概览带 ---- */
.overview {
  flex: none;
  padding: 0 20px;
}
.ov-strip {
  display: flex;
  gap: 2px;
  height: 8px;
}
.ov-turn {
  display: flex;
  flex: 1 1 0;
  gap: 1px;
  min-width: 2px;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 2px;
  opacity: 0.55;
}
.ov-turn:hover,
.ov-turn.on {
  opacity: 1;
}
.ov-turn.on {
  box-shadow: 0 0 0 1.5px var(--ink-soft);
}
.ov-turn.bad {
  box-shadow: 0 0 0 1.5px var(--danger);
}
.ov-input {
  flex: 0 0 2px;
  background: var(--ink-faint);
}
.ov-seg {
  flex: 1 1 0;
  min-width: 1px;
}
.ov-seg.text { background: var(--model-fill); }
.ov-seg.tool { background: var(--tool-fill); }
.ov-seg.error { background: var(--danger); }
.legend {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 7px 0 10px;
  font-size: 10px;
  color: var(--ink-faint);
}
.legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.legend i {
  width: 10px;
  height: 3px;
  border-radius: 1px;
}
.legend i.input { background: var(--ink-faint); }
.legend i.model { background: var(--model-fill); }
.legend i.tool { background: var(--tool-fill); }
.legend .estimate {
  margin-left: auto;
}

/* ---- 左列表 + 右检查器 ---- */
.trace-body {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  border-top: 1px solid var(--line-soft);
}
.trace-left {
  flex: 1 1 0;
  min-width: 0;
  padding: 4px 0 28px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.round {
  padding: 2px 0 6px;
}
.round-head {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 7px 20px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-left: 2px solid transparent;
}
.round-head:hover {
  background: var(--surface-hover);
}
.round-head.on {
  background: var(--surface-sunken);
  border-left-color: var(--ink-soft);
}
.round-no {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.round-q {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.round-meta {
  flex: none;
  font-size: 10px;
  color: var(--ink-faint);
}
/* 本轮费用：比耗时信息更醒目一点，方便横向比较哪轮更贵 */
.round-money {
  flex: none;
  font-size: 10px;
  color: var(--ink-muted);
}
.badge {
  flex: none;
  padding: 0 5px;
  font-size: 9.5px;
  line-height: 15px;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: var(--radius-xs);
}

.steps {
  padding: 0;
  margin: 0;
  list-style: none;
}
.step-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 3px 20px 3px 40px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-left: 2px solid transparent;
}
.step-row:hover {
  background: var(--surface-hover);
}
.step-row.on {
  background: var(--accent-soft);
  border-left-color: var(--accent);
}
.step-kind {
  flex: none;
  width: 26px;
  font-size: 9.5px;
  color: var(--ink-faint);
}
.step-row.tool .step-kind { color: var(--tool-ink); }
.step-row.text .step-kind { color: var(--model-ink); }
.step-row.error .step-kind { color: var(--danger); }
.step-name {
  flex: none;
  max-width: 120px;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.step-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.step-state,
.step-cost {
  flex: none;
  font-size: 9.5px;
  color: var(--ink-faint);
}
/* 每步费用：模型输出步才有 */
.step-money {
  flex: none;
  font-size: 9.5px;
  color: var(--ink-muted);
}
.step-state.completed { color: var(--ok); }
.step-state.running { color: var(--accent-ink); }
.step-state.failed { color: var(--danger); }
.step-empty,
.no-match {
  padding: 8px 20px 8px 40px;
  font-size: 11px;
  color: var(--ink-faint);
}

/* ---- 检查器 ---- */
.inspector {
  display: flex;
  flex: 0 0 360px;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  background: var(--surface-raised);
  border-left: 1px solid var(--line-soft);
}
.insp-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 12px 16px 0;
}
.insp-kind {
  flex: none;
  padding: 0 5px;
  font-size: 9.5px;
  line-height: 16px;
  color: var(--tool-ink);
  background: rgb(230 183 117 / 22%);
  border-radius: var(--radius-xs);
}
.insp-kind.text {
  color: var(--model-ink);
  background: rgb(169 155 242 / 22%);
}
.insp-kind.error {
  color: var(--danger);
  background: var(--danger-soft);
}
.insp-kind.round-kind {
  color: var(--ink-muted);
  background: var(--surface-sunken);
}
.insp-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.insp-status {
  flex: none;
  font-size: 10px;
}
.insp-status.completed { color: var(--ok); }
.insp-status.running { color: var(--accent-ink); }
.insp-status.failed { color: var(--danger); }
.insp-sub {
  padding: 2px 16px 0;
  margin: 0;
  font-size: 10px;
  color: var(--ink-faint);
}
.insp-tabs {
  display: flex;
  flex: none;
  gap: 12px;
  padding: 10px 16px 0;
  border-bottom: 1px solid var(--line-soft);
}
.insp-tabs button {
  padding: 0 0 6px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 1.5px solid transparent;
}
.insp-tabs button:hover {
  color: var(--ink-soft);
}
.insp-tabs button.on {
  color: var(--ink);
  border-bottom-color: var(--accent);
}
.insp-body {
  flex: 1;
  padding: 12px 16px 16px;
  min-height: 0;
}
.insp-foot {
  flex: none;
  padding: 0 16px 16px;
}

.kv {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 6px 10px;
  margin: 0 0 12px;
  font-size: 11px;
}
.kv dt {
  color: var(--ink-faint);
}
.kv dd {
  min-width: 0;
  margin: 0;
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}
.kv dd.completed { color: var(--ok); }
.kv dd.failed { color: var(--danger); }
.kv dd.running { color: var(--accent-ink); }
.kv dd.danger { color: var(--danger); }

/* 入参字段 chips */
.keys {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 12px;
}
.key {
  padding: 1px 5px;
  font-size: 10px;
  line-height: 15px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-xs);
}
/* 概述里的快照：比正文页签矮，避免一处塞满 */
.snapshot {
  max-height: 180px;
  margin-bottom: 12px;
}

.code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 10px;
  color: var(--ink-faint);
}
.mini {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0 4px;
  font-family: inherit;
  font-size: 10px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.mini:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.code {
  max-height: 420px;
  padding: 10px 12px;
  margin: 0 0 10px;
  overflow: auto;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--code-bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.code.result-text {
  color: var(--ink-soft);
}
.prompt {
  max-height: none;
  font-size: 11.5px;
  line-height: 1.8;
}
/* JSON 高亮 */
.code :deep(.j-key) { color: var(--accent-ink); }
.code :deep(.j-str) { color: #2f7d5c; }
.code :deep(.j-num) { color: #a35b1f; }
.code :deep(.j-bool) { color: #8a4bc4; }

.hint {
  margin: 10px 0 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}
.muted {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-faint);
}
.tool-list {
  padding: 0;
  margin: 0 0 10px;
  list-style: none;
}
.tool-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 4px 6px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-soft);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.tool-item:hover {
  background: var(--surface-hover);
}
.tool-item-state {
  margin-left: auto;
  font-size: 9.5px;
  color: var(--ink-faint);
}
.tool-item-state.completed { color: var(--ok); }
.tool-item-state.failed { color: var(--danger); }
.tool-item-cost {
  flex: none;
  font-size: 9.5px;
  color: var(--ink-faint);
}
.ghost-btn {
  height: 24px;
  padding: 0 9px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.ghost-btn:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.insp-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 40px 24px;
  text-align: center;
}
.insp-empty p {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--ink-soft);
}
.insp-empty span {
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-faint);
}

/* ---- 系统提示词 ---- */
.system-pane {
  flex: 1 1 0;
  min-height: 0;
  padding: 4px 24px 28px;
  overflow-y: auto;
}
.system-inner {
  max-width: 56rem;
  margin: 0 auto;
}
.system-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 2px 0 10px;
  font-size: 10.5px;
  color: var(--ink-faint);
}

/* ---- 空态 ---- */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 60px 24px;
  text-align: center;
}
.empty p {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--ink-soft);
}
.empty span {
  max-width: 26rem;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-faint);
}

@media (max-width: 1080px) {
  .inspector {
    flex-basis: 300px;
  }
  .search {
    display: none;
  }
}
@media (max-width: 860px) {
  .inspector {
    display: none;
  }
  .step-preview {
    display: none;
  }
}
</style>
