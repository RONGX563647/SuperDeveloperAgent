<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowDown, Refresh, Setting, Warning } from '@element-plus/icons-vue'
import {
  checkProjectEnv,
  getEffectiveEnvironment,
  type EffectiveEnvironmentView,
  type EnvCheckView,
} from '../../api/settings'
import { envLabel } from '../../utils/pipelineLabels'

/**
 * 环境状态条：一行横向摘要（环境 / 健康度 / 中间件数 / 运行时状态），
 * 点击展开一份明细浮层（网络、端口、变量、中间件列表、去配置）。
 *
 * <p>取代原来的右上角悬浮卡：它压在正文上、和 Header 的「就绪」各说各话，
 * 现在两件事收在同一条可折叠的状态栏里，不遮内容、不随滚动漂移。
 */
const props = defineProps<{
  projectId: string
  env: string
  projectName: string
  /** 会话是否正在生成（原 Header 的「就绪 / 生成中」移到这里）。 */
  streaming?: boolean
}>()

const emit = defineEmits<{ navigate: [] }>()

const OPEN_KEY = 'agent.envBar.open'

function loadOpen(): boolean {
  try {
    return localStorage.getItem(OPEN_KEY) === '1'
  } catch {
    return false
  }
}

const open = ref(loadOpen())
const effective = ref<EffectiveEnvironmentView | null>(null)
const check = ref<EnvCheckView | null>(null)
const loading = ref(false)
const failed = ref(false)
const checkFailed = ref(false)

/** 只认被本项目引用的中间件：其它项目/环境共享的容器不计入健康。 */
const resources = computed(() => (effective.value?.resources ?? []).filter((r) => r.referenced !== false))
const shared = computed(() => (effective.value?.resources.length ?? 0) - resources.value.length)
const total = computed(() => resources.value.length)
const running = computed(() => resources.value.filter((r) => r.status === 'RUNNING').length)
const missing = computed(() => resources.value.filter((r) => r.status === 'MISSING').length)
const stopped = computed(() =>
  resources.value.filter((r) => r.status === 'STOPPED' || r.status === 'UNKNOWN').length)
const varCount = computed(() => effective.value?.vars.length ?? 0)
/** 本项目源码声明的必填变量；只有这些缺失才算"变量没配齐"。 */
const missingVars = computed(() => check.value?.missingVars ?? [])
const sourceProvided = computed(() => check.value?.sourceProvidedVars ?? [])
const requiredCount = computed(() => check.value?.requiredCount ?? 0)

/** 摘要：运行健康 / 有待处理 / 读取失败。 */
const health = computed<'ok' | 'warn' | 'error' | 'loading'>(() => {
  if (loading.value && !effective.value) return 'loading'
  if (failed.value) return 'error'
  if (missingVars.value.length > 0) return 'error'
  if (missing.value > 0) return 'error'
  if (checkFailed.value) return 'warn'
  if (total.value > 0 && running.value < total.value) return 'warn'
  return 'ok'
})

const healthText = computed(() => {
  if (health.value === 'loading') return '读取中'
  if (failed.value) return '读取失败'
  if (missingVars.value.length > 0) return '变量缺失'
  if (missing.value > 0) return '中间件缺配置'
  if (checkFailed.value) return '变量未校验'
  if (health.value === 'warn') return '部分未运行'
  return '环境就绪'
})

function toggle(): void {
  open.value = !open.value
  try {
    localStorage.setItem(OPEN_KEY, open.value ? '1' : '0')
  } catch {
    // 隐私模式下不可写，忽略
  }
  if (open.value && !effective.value) void load()
}

function statusText(status: string): string {
  return ({
    RUNNING: '运行',
    STOPPED: '停止',
    MISSING: '缺配置',
    UNKNOWN: '未知'
  } as Record<string, string>)[status] ?? status
}

async function load(): Promise<void> {
  if (!props.projectId) {
    effective.value = null
    check.value = null
    return
  }
  loading.value = true
  failed.value = false
  checkFailed.value = false
  const [envResult, checkResult] = await Promise.allSettled([
    getEffectiveEnvironment(props.projectId, props.env),
    checkProjectEnv(props.projectId, props.env),
  ])
  if (envResult.status === 'fulfilled') {
    effective.value = envResult.value
  } else {
    effective.value = null
    failed.value = true
  }
  if (checkResult.status === 'fulfilled') {
    check.value = checkResult.value
  } else {
    check.value = null
    checkFailed.value = true
  }
  loading.value = false
}

watch([() => props.projectId, () => props.env], () => {
  effective.value = null
  check.value = null
  void load()
})

onMounted(() => void load())
</script>

<template>
  <div class="env-bar" :class="[`is-${health}`, { open }]">
    <button
      type="button" class="bar-trigger"
      :title="`${projectName} · ${envLabel(env)} · ${healthText}`"
      @click="toggle"
    >
      <span class="dot" />
      <span class="env-name">{{ envLabel(env) }}</span>
      <span class="sep">·</span>
      <span class="env-health">{{ healthText }}</span>
      <span class="sep">·</span>
      <span class="env-count mono">中间件 {{ running }}/{{ total }}</span>
      <template v-if="missingVars.length">
        <span class="sep">·</span>
        <span class="env-count mono danger">变量缺 {{ missingVars.length }}</span>
      </template>
      <el-icon class="caret" :class="{ flip: open }"><ArrowDown /></el-icon>
    </button>

    <span class="bar-split" />

    <span class="run" :class="{ busy: streaming }">
      <span class="dot" />
      {{ streaming ? '生成中' : '就绪' }}
    </span>

    <button
      type="button" class="bar-icon" title="刷新环境状态"
      :disabled="loading" @click="load"
    >
      <el-icon :class="{ spin: loading }"><Refresh /></el-icon>
    </button>

    <template v-if="open">
      <div class="backdrop" @click="open = false" />
      <div class="panel">
        <div class="panel-head">
          <span class="dot" />
          <b>{{ envLabel(env) }}</b>
          <span class="panel-health">{{ healthText }}</span>
          <span class="panel-project mono">{{ projectName }}</span>
        </div>

        <div class="kv">
          <span>网络</span><b class="mono">{{ effective?.network || '默认网络' }}</b>
        </div>
        <div class="kv">
          <span>端口</span><b class="mono">:{{ effective?.defaultPort || '—' }}</b>
        </div>
        <div class="kv">
          <span>注入变量</span><b class="mono">{{ varCount }}</b>
        </div>
        <div class="kv">
          <span>必填变量</span>
          <b class="mono" :class="{ danger: missingVars.length > 0 }">
            <template v-if="checkFailed">未校验</template>
            <template v-else-if="missingVars.length">缺 {{ missingVars.length }} / {{ requiredCount }}</template>
            <template v-else>齐全<template v-if="requiredCount">（{{ requiredCount }}）</template></template>
          </b>
        </div>
        <div class="kv">
          <span>中间件</span>
          <b class="mono">
            运行 {{ running }}
            <template v-if="stopped"> · 停止 {{ stopped }}</template>
            <template v-if="missing"> · 缺配 {{ missing }}</template>
            <template v-if="total"> / {{ total }}</template>
          </b>
        </div>

        <p v-if="failed" class="note danger">
          <el-icon><Warning /></el-icon> 环境配置读取失败
        </p>
        <p v-else-if="missingVars.length" class="note danger">
          <el-icon><Warning /></el-icon> 缺少必填变量：{{ missingVars.join('、') }}
        </p>
        <p v-else-if="sourceProvided.length" class="note">
          源码已提供 {{ sourceProvided.length }} 个变量（部署时自动注入）
        </p>
        <p v-if="!total" class="note">
          本项目在当前环境未引用中间件<template v-if="shared">；另有 {{ shared }} 个环境共享中间件，不计入健康</template>。
        </p>
        <ul v-else class="mw-list">
          <li v-for="r in resources" :key="r.id" class="mw">
            <span class="dot" :class="r.status.toLowerCase()" />
            <span class="mw-name" :title="`${r.name} · ${r.kind}`">{{ r.name }}</span>
            <span class="mw-state">{{ statusText(r.status) }}</span>
          </li>
        </ul>

        <button type="button" class="link" @click="emit('navigate')">
          <el-icon><Setting /></el-icon> 配置项目环境
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.env-bar {
  position: relative;
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

/* 状态点：一处逻辑，全局复用 */
.dot {
  flex: none;
  width: 6px;
  height: 6px;
  background: var(--ok);
  border-radius: 50%;
}
.is-warn .dot,
.dot.stopped,
.dot.unknown {
  background: var(--warn);
}
.is-error .dot,
.dot.missing {
  background: var(--danger);
}
.is-loading .dot {
  background: var(--ink-faint);
}

/* ---- 一行摘要 ---- */
.bar-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 24px;
  padding: 0 7px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-soft);
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.bar-trigger:hover,
.env-bar.open .bar-trigger {
  color: var(--ink);
  background: var(--surface-hover);
}
.env-name {
  font-weight: 600;
}
.sep {
  color: var(--ink-faint);
}
.env-health,
.env-count {
  color: var(--ink-muted);
}
.env-count.danger {
  color: var(--danger);
}
.caret {
  font-size: 10px;
  color: var(--ink-faint);
  transition: transform var(--dur-fast) ease;
}
.caret.flip {
  transform: rotate(180deg);
}

.bar-split {
  width: 1px;
  height: 12px;
  background: var(--line-strong);
}

/* 运行时状态（原 Header 的「就绪」） */
.run {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: var(--ink-muted);
  white-space: nowrap;
}
.run.busy .dot {
  background: var(--accent);
  animation: env-pulse 1.4s ease-in-out infinite;
}
@keyframes env-pulse {
  50% { opacity: 0.3; }
}

.bar-icon {
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
  border-radius: var(--radius-xs);
}
.bar-icon:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.bar-icon:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.spin {
  animation: env-spin 0.9s linear infinite;
}
@keyframes env-spin {
  to { transform: rotate(360deg); }
}

/* ---- 展开明细 ---- */
.backdrop {
  position: fixed;
  inset: 0;
  z-index: 39;
}
.panel {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 40;
  width: 300px;
  padding: 12px 14px 12px;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-2);
}
.panel-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 8px;
  font-size: 12px;
  color: var(--ink);
}
.panel-head b {
  font-weight: 600;
}
.panel-health {
  font-size: 10.5px;
  color: var(--ink-muted);
}
.panel-project {
  margin-left: auto;
  overflow: hidden;
  max-width: 120px;
  font-size: 10px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.kv {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
  font-size: 11px;
  border-bottom: 1px solid var(--line-soft);
}
.kv:last-of-type {
  border-bottom: 0;
}
.kv span {
  flex: none;
  color: var(--ink-muted);
}
.kv b {
  min-width: 0;
  overflow: hidden;
  font-weight: 500;
  color: var(--ink);
  text-align: right;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.kv b.danger {
  color: var(--danger);
}
.note {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 8px 0 0;
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--ink-muted);
}
.note.danger {
  color: var(--danger);
}
.mw-list {
  max-height: 168px;
  padding: 6px 0 0;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}
.mw {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  font-size: 10.5px;
  color: var(--ink-soft);
}
.mw-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.mw-state {
  flex: none;
  color: var(--ink-faint);
}
.link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  margin-top: 10px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
}
.link:hover {
  text-decoration: underline;
}
.mono {
  font-family: var(--font-mono);
}

@media (max-width: 900px) {
  .env-health,
  .env-count {
    display: none;
  }
  .bar-split {
    display: none;
  }
  .panel {
    width: 264px;
  }
}
</style>
