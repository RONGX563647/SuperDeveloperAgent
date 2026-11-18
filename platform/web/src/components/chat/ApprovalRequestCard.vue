<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getChange, getChangePatch, confirmChange, applyChange } from '../../api/change'
import type { ChangePatchView, ChangeView } from '../../types'

/**
 * 变更确认卡：加载变更单 → 确认（高危需密码）→ 带令牌执行。
 *
 * <p>紧凑布局：一行标题 + 一行摘要，其余元数据与 diff 收进「详情」，
 * 按钮固定在底部。执行失败时除本地提示外还会 emit('failed')，
 * 由对话页把失败原因回灌给 Agent（否则模型永远等不到结果）。
 */
interface Props {
  changeId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'applied', result: {
    changeId: string, action: string, status: string, durationMs: number
  }): void
  (e: 'failed', result: { changeId: string, reason: string }): void
  (e: 'dismiss'): void
}>()

const change = ref<ChangeView | null>(null)
const patch = ref<ChangePatchView | null>(null)
const password = ref('')
const confirmToken = ref<string | null>(null)
const applyResult = ref<string | null>(null)
const applyLog = ref<string | null>(null)
const applyError = ref('')
const applyBusy = ref(false)
const detailOpen = ref(false)
/** diff 默认收起：卡片只保留「几个文件 / 增删行」，需要复核时再展开。 */
const diffOpen = ref(false)
/** 执行输出单独开关，避免和 diff 共用状态互相干扰。 */
const logOpen = ref(false)

function statusLabel(): string {
  switch (change.value?.status) {
    case 'CONFIRMED': return '已确认'
    case 'APPLIED': return '已执行'
    case 'FAILED': return '执行失败'
    default: return '待确认'
  }
}

async function load(): Promise<void> {
  try {
    change.value = await getChange(props.changeId)
    patch.value = change.value.kind.startsWith('patch:')
      ? await getChangePatch(props.changeId)
      : null
    confirmToken.value = null
    applyResult.value = null
    applyLog.value = null
    applyError.value = ''
    password.value = ''
    detailOpen.value = false
    diffOpen.value = false
    logOpen.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载变更失败')
  }
}

async function doConfirm(): Promise<void> {
  if (!change.value) return
  applyError.value = ''
  try {
    const result = await confirmChange(change.value.changeId, change.value.highRisk ? password.value : undefined)
    confirmToken.value = result.confirmToken
    change.value.status = 'CONFIRMED'
  } catch (error) {
    const message = error instanceof Error ? error.message : '确认失败'
    applyError.value = message
    ElMessage.error(message)
    // 确认阶段失败（含高危密码错误）同样要回执：否则 Agent 只会看到"待确认"并重复推卡
    emit('failed', { changeId: props.changeId, reason: `确认阶段失败：${message}` })
  }
}

async function doApply(): Promise<void> {
  if (!change.value || !confirmToken.value) return
  applyBusy.value = true
  applyError.value = ''
  try {
    const result = await applyChange(change.value.changeId, confirmToken.value)
    change.value.status = 'APPLIED'
    applyResult.value = `${result.action} → ${result.status}（${result.durationMs} ms）`
    applyLog.value = result.logTail || null
    emit('applied', {
      changeId: props.changeId,
      action: result.action,
      status: result.status,
      durationMs: result.durationMs,
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : '执行失败'
    change.value.status = 'FAILED'
    applyError.value = message
    ElMessage.error(message)
    // 失败也要有回执：对话页据此让 Agent 知道需要重登记或放弃
    emit('failed', { changeId: props.changeId, reason: message })
  } finally {
    applyBusy.value = false
  }
}

defineExpose({ load })
</script>

<template>
  <div v-if="change" class="change-card">
    <div class="card-head">
      <span class="dot" :class="{ risk: change.highRisk }"></span>
      <span class="head-title">变更确认</span>
      <span class="tag mono">{{ change.kind }}</span>
      <span class="tag mono">{{ change.env }}</span>
      <span class="status-chip" :class="change.status.toLowerCase()">{{ statusLabel() }}</span>
      <span class="head-id mono" :title="change.changeId">{{ change.changeId }}</span>
      <span class="spacer"></span>
      <span v-if="change.highRisk" class="risk-text">高危</span>
      <button type="button" class="link-btn" @click="emit('dismiss')">忽略</button>
    </div>

    <div class="summary" :title="change.summary">{{ change.summary }}</div>

    <div class="meta-line">
      <button type="button" class="link-btn" @click="detailOpen = !detailOpen">
        {{ detailOpen ? '收起详情' : '详情' }}
      </button>
      <template v-if="patch">
        <span class="meta mono">{{ patch.branch }}</span>
        <span class="meta">{{ patch.files.length }} 个文件</span>
        <span class="meta add">+{{ patch.added }}</span>
        <span class="meta del">-{{ patch.removed }}</span>
        <button type="button" class="link-btn" @click="diffOpen = !diffOpen">
          {{ diffOpen ? '收起 diff' : '查看 diff' }}
        </button>
      </template>
    </div>

    <div v-if="detailOpen" class="detail-grid">
      <span>项目 / 环境</span><b>{{ change.projectId }} / {{ change.env }}</b>
      <span>镜像 digest</span><b class="mono">{{ change.imageDigest ?? '—' }}</b>
      <span>计划哈希</span><b class="mono">{{ change.planHash.slice(0, 32) }}…</b>
      <template v-if="patch">
        <span>基于提交</span><b class="mono">{{ patch.baseSha.slice(0, 12) }}</b>
      </template>
    </div>

    <pre v-if="patch && diffOpen" class="diff mono">{{ patch.diff }}</pre>

    <el-input
      v-if="change.highRisk && !confirmToken"
      v-model="password"
      size="small"
      type="password"
      placeholder="高危变更需输入密码"
      show-password
    />

    <div class="card-foot">
      <span v-if="applyResult" class="ok-text">已执行 · {{ applyResult }}</span>
      <span v-else-if="confirmToken" class="ok-text">已确认 · 令牌 5 分钟有效</span>
      <span v-else-if="applyError" class="err-text" :title="applyError">{{ applyError }}</span>
      <span v-else class="hint">确认前不会执行任何操作</span>
      <span class="spacer"></span>
      <el-button
        v-if="!confirmToken" size="small"
        :disabled="change.highRisk && !password"
        @click="doConfirm"
      >
        确认变更
      </el-button>
      <el-button
        v-else size="small" type="primary"
        :disabled="applyBusy || !!applyResult" :loading="applyBusy"
        @click="doApply"
      >
        {{ patch ? '应用补丁' : '执行变更' }}
      </el-button>
    </div>

    <div v-if="applyLog" class="log-box">
      <button type="button" class="link-btn" @click="logOpen = !logOpen">
        {{ logOpen ? '收起执行输出' : '查看执行输出' }}
      </button>
      <pre v-if="logOpen" class="diff mono">{{ applyLog }}</pre>
    </div>
  </div>
</template>

<style scoped>
.change-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 720px;
  padding: 10px 12px;
  margin-top: 10px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-1, 0 1px 2px rgba(0, 0, 0, 0.04));
}
.card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.dot {
  flex: none;
  width: 6px;
  height: 6px;
  background: var(--warn, #d97706);
  border-radius: 50%;
}
.dot.risk {
  background: var(--danger, #dc2626);
}
.head-title {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.tag {
  flex: none;
  padding: 0 5px;
  font-size: 10px;
  line-height: 16px;
  color: var(--ink-soft);
  background: var(--surface-sunken, rgba(0, 0, 0, 0.04));
  border-radius: 4px;
}
.head-id {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint, var(--ink-soft));
  white-space: nowrap;
  text-overflow: ellipsis;
}
.status-chip {
  flex: none;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  border-radius: 4px;
}
.status-chip.pending {
  color: #b45309;
  background: rgba(245, 158, 11, 0.12);
}
.status-chip.confirmed {
  color: #2563eb;
  background: rgba(37, 99, 235, 0.1);
}
.status-chip.applied {
  color: #15803d;
  background: rgba(22, 163, 74, 0.12);
}
.status-chip.failed {
  color: #dc2626;
  background: rgba(220, 38, 38, 0.1);
}
.spacer {
  flex: 1;
}
.risk-text {
  flex: none;
  font-size: 10px;
  font-weight: 600;
  color: var(--danger, #dc2626);
}
.summary {
  overflow: hidden;
  font-size: 12px;
  line-height: 1.5;
  color: var(--ink);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.meta-line {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: center;
}
.meta {
  font-size: 10px;
  color: var(--ink-muted, var(--ink-soft));
}
.add {
  color: #16a34a;
}
.del {
  color: #dc2626;
}
.detail-grid {
  display: grid;
  grid-template-columns: 76px 1fr;
  gap: 4px 10px;
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.5;
  background: var(--surface-sunken, rgba(0, 0, 0, 0.03));
  border-radius: var(--radius-sm, 6px);
}
.detail-grid span {
  color: var(--ink-muted, var(--ink-soft));
}
.detail-grid b {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 500;
}
.diff {
  max-height: 300px;
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.55;
  white-space: pre;
  background: var(--bg-soft, rgba(0, 0, 0, 0.04));
  border-radius: var(--radius-sm, 6px);
}
.card-foot {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-top: 8px;
  border-top: 1px dashed var(--line);
}
.hint,
.ok-text,
.err-text {
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.hint {
  color: var(--ink-muted, var(--ink-soft));
}
.ok-text {
  color: #16a34a;
}
.err-text {
  color: var(--danger, #dc2626);
}
.log-box {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.link-btn {
  flex: none;
  padding: 0;
  font-size: 11px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: none;
}
.link-btn:hover {
  color: var(--brand, #2563eb);
}
</style>
