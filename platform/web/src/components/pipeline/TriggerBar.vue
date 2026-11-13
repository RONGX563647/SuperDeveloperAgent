<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Download, Refresh, VideoPlay } from '@element-plus/icons-vue'
import type { GitCommit } from '../../api/project'
import type { DryRunReportView, PreviewStage } from '../../api/pipeline'
import { previewLabel } from '../../utils/pipelineLabels'

/**
 * 运行卡：一次运行只需要三件事——提交、选部署方式、点运行。
 *
 * - 提交从 git 历史里选（可搜索/可粘贴 sha），默认最新一条；
 * - 阶段预览来自后端同一份定义（不是前端猜的），避免"以为是发布、实际只跑了检查构建"；
 * - 部署方式只有两种：打包成镜像（Docker）/ 直接部署（本机）；流水线由环境自动解析。
 */
interface Props {
  envLabel: string
  primaryLabel: string
  sha: string
  commits: GitCommit[]
  commitsLoading?: boolean
  /** docker / host / server（prod 固定 server，不展示切换）。 */
  deployTarget: string
  /** 本机 Docker 是否可用：不可用时禁用「打包成镜像」，避免触发后失败。 */
  dockerAvailable?: boolean
  showModeSwitch?: boolean
  modeNote?: string
  /** 本机运行提示；有值时展示「停止本机服务」 */
  localHint?: string
  canStopLocal?: boolean
  /** 本次要执行的动作：发布（release）/ 只检查构建（verify）。 */
  action: 'release' | 'verify'
  preview: PreviewStage[]
  /** 只检查构建的预览：用来展示会跑几步、跳过什么。 */
  verifyPreview?: PreviewStage[]
  previewLoading?: boolean
  busy: boolean
  lastGoodSha?: string
  hint?: string
  canRun: boolean
  /** 最近一次 dry-run 报告：展示阻塞项 / 警告，不产生任何变更。 */
  dryRun?: DryRunReportView | null
  dryRunBusy?: boolean
  /** 发布 Agent：正在执行（按钮转圈）。 */
  agentBusy?: boolean
  /** 发布 Agent：不可用原因（如生产环境、只读角色）。 */
  agentDisabled?: boolean
  agentHint?: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'update:sha', v: string): void
  (e: 'update:deployTarget', v: string): void
  (e: 'update:action', v: 'release' | 'verify'): void
  (e: 'run'): void
  (e: 'dryrun'): void
  (e: 'refreshCommits'): void
  (e: 'stopLocal'): void
  (e: 'agent'): void
}>()

const SHA_PREFIX = /^([0-9a-f]{7,40})\b/i

/** 从任意粘贴内容里提取 commit sha 前缀。 */
function normalizeSha(raw: string): string {
  const text = String(raw ?? '').trim()
  const match = text.match(SHA_PREFIX)
  if (match) return match[1].toLowerCase()
  const loose = text.replace(/[^0-9a-f]/gi, '')
  return loose.length >= 7 ? loose.slice(0, 40).toLowerCase() : loose.toLowerCase()
}

/** 提交下拉只显示 sha + 标题（旧实现把整段 commit 标题塞进选择框，宽度不可控）。 */
function commitLabel(c: GitCommit): string {
  return `${c.sha.slice(0, 8)} · ${c.subject}`
}

/** 当前动作对应的阶段预览（verify 用单独的 that preview）。 */
const activePreview = computed<PreviewStage[]>(() =>
  props.action === 'verify' ? (props.verifyPreview ?? []) : props.preview)

/** 这次真正会执行的阶段（skipReason 为空的）。 */
const runnable = computed(() => activePreview.value.filter((s) => !s.skipReason))

/** 被跳过的阶段：只展示「跳过几步 + 原因」，不逐个列名字。 */
const skipNote = computed(() => {
  const skipped = activePreview.value.filter((s) => !!s.skipReason)
  if (!skipped.length) return ''
  const reasons = [...new Set(skipped.map((s) => s.skipReason))]
  return `跳过 ${skipped.length} 步：${reasons.join('；')}`
})

const openedStage = ref('')
const detail = computed<PreviewStage | null>(() =>
  runnable.value.find((s) => s.id === openedStage.value) ?? null)

watch(() => props.action, () => { openedStage.value = '' })
watch(activePreview, () => {
  if (openedStage.value && !runnable.value.some((s) => s.id === openedStage.value)) {
    openedStage.value = ''
  }
})

function toggleStage(id: string): void {
  openedStage.value = openedStage.value === id ? '' : id
}

/** 复制命令或完整脚本（优先脚本：多步 && 拼起来也能用）。 */
async function copyCommand(stage: PreviewStage): Promise<void> {
  const text = stage.script || stage.command
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

/** 脚本里是否有 ${var} 占位符：预览展示模板，运行时才替换成实际值。 */
function hasPlaceholder(stage: PreviewStage): boolean {
  return /\$\{[^}]+\}/.test(stage.script || '')
}

/** 下载脚本（pwsh → .ps1，其余 → .sh）；纯前端生成，稳定可复现。 */
function downloadScript(stage: PreviewStage): void {
  if (!stage.script) return
  const ext = stage.shell === 'pwsh' ? 'ps1' : 'sh'
  const blob = new Blob([stage.script], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${stage.id}.${ext}`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

/** 卡片元信息：可执行阶段数 + 是否产镜像。 */
function cardMeta(stages: PreviewStage[]): { count: number, image: boolean } {
  const steps = stages.filter((s) => !s.skipReason)
  return {
    count: steps.length,
    image: steps.some((s) => s.id.includes('image') || s.action.includes('image')),
  }
}

const releaseMeta = computed(() => cardMeta(props.preview))
const verifyMeta = computed(() => cardMeta(props.verifyPreview ?? []))

const runLabel = computed(() =>
  props.action === 'verify' ? '开始检查构建' : props.primaryLabel)
</script>

<template>
  <section class="run-card" aria-label="触发运行">
    <header class="rc-head">
      <h2 class="rc-title">运行</h2>
      <span class="rc-env">{{ envLabel }}</span>
      <span v-if="hint" class="rc-hint">{{ hint }}</span>
    </header>

    <div class="rc-commit">
      <label class="rc-label" for="commit-select">提交</label>
      <el-select
        id="commit-select"
        :model-value="sha"
        class="rc-sha" size="default" filterable allow-create default-first-option
        placeholder="选择提交，或直接粘贴 sha"
        :loading="commitsLoading"
        @update:model-value="(v: string) => emit('update:sha', normalizeSha(v))"
      >
        <el-option
          v-for="c in commits" :key="c.sha"
          :label="commitLabel(c)" :value="c.sha"
        >
          <div class="commit-row">
            <span class="commit-sha mono">{{ c.sha.slice(0, 8) }}</span>
            <span class="commit-subject">{{ c.subject }}</span>
            <span class="commit-meta">{{ c.author }} · {{ c.relativeTime }}</span>
          </div>
        </el-option>
      </el-select>
      <button
        type="button" class="rc-refresh" :disabled="commitsLoading"
        title="刷新提交列表" @click="emit('refreshCommits')"
      >
        <el-icon :class="{ 'is-loading': commitsLoading }"><Refresh /></el-icon>
      </button>
    </div>

    <div class="rc-pick" role="group" aria-label="选择动作">
      <button
        type="button" class="pick-card" :class="{ active: action === 'release' }"
        @click="emit('update:action', 'release')"
      >
        <span class="pc-head">
          <span class="pc-title">{{ primaryLabel }}</span>
          <span v-if="action === 'release'" class="pc-check">已选</span>
        </span>
        <span class="pc-desc">构建并部署到当前环境</span>
        <span class="pc-meta">{{ releaseMeta.count }} 步 · {{ releaseMeta.image ? '产镜像' : '不产镜像' }}</span>
      </button>
      <button
        type="button" class="pick-card" :class="{ active: action === 'verify' }"
        @click="emit('update:action', 'verify')"
      >
        <span class="pc-head">
          <span class="pc-title">只检查构建</span>
          <span v-if="action === 'verify'" class="pc-check">已选</span>
        </span>
        <span class="pc-desc">只编译和测试，不部署</span>
        <span class="pc-meta">{{ verifyMeta.count }} 步 · 不产镜像</span>
      </button>
    </div>

    <div class="rc-preview">
      <span class="rc-label">将执行</span>
      <ol v-if="runnable.length" class="flow">
        <li
          v-for="(s, i) in runnable" :key="s.id"
          class="flow-step" :class="{ open: openedStage === s.id }"
          role="button" tabindex="0"
          :title="s.command"
          @click="toggleStage(s.id)"
          @keydown.enter="toggleStage(s.id)"
        >
          <span class="flow-no">{{ i + 1 }}</span>
          <span class="flow-name">{{ previewLabel(s) }}</span>
        </li>
      </ol>
      <span v-else class="flow-empty">
        {{ previewLoading ? '读取流水线定义…' : '还没有可用的流水线定义' }}
      </span>
      <span v-if="runnable.length" class="rc-tip">点阶段看命令</span>
    </div>
    <p v-if="skipNote" class="rc-skip">{{ skipNote }}</p>

    <!-- 命令级预览：这一步实际执行什么（脚本由配方确定性渲染，可直接复制/下载） -->
    <div v-if="detail" class="cmd-panel">
      <header class="cmd-head">
        <span class="cmd-title mono">{{ detail.id }}</span>
        <span v-if="detail.shell" class="cmd-chip">{{ detail.shell }}</span>
        <span v-if="detail.workdir && detail.workdir !== '.'" class="cmd-meta">
          目录 {{ detail.workdir }}
        </span>
        <span v-if="detail.scriptSha" class="cmd-meta mono">sha {{ detail.scriptSha }}</span>
        <span v-if="hasPlaceholder(detail)" class="cmd-meta">占位符运行时替换</span>
        <span class="cmd-spacer" />
        <button type="button" class="cmd-btn" @click="copyCommand(detail)">
          <el-icon><CopyDocument /></el-icon> 复制
        </button>
        <button v-if="detail.script" type="button" class="cmd-btn" @click="downloadScript(detail)">
          <el-icon><Download /></el-icon> 下载脚本
        </button>
      </header>
      <pre v-if="detail.script" class="cmd-code">{{ detail.script }}</pre>
      <p v-else class="cmd-builtin">{{ detail.command }} · 平台内置步骤，无脚本</p>
    </div>

    <div v-if="showModeSwitch && action === 'release'" class="rc-mode">
      <span class="rc-label">方式</span>
      <div class="mode-switch" role="group" aria-label="部署方式">
        <button
          type="button" class="mode-btn"
          :class="{ active: deployTarget === 'docker' }"
          :disabled="dockerAvailable === false"
          :title="dockerAvailable === false ? '本机 Docker 不可用：请安装并启动 Docker 后刷新' : ''"
          @click="emit('update:deployTarget', 'docker')"
        >
          打包成镜像
        </button>
        <button
          type="button" class="mode-btn"
          :class="{ active: deployTarget === 'host' }"
          @click="emit('update:deployTarget', 'host')"
        >
          直接部署
        </button>
      </div>
      <span v-if="modeNote" class="rc-mode-note">{{ modeNote }}</span>
    </div>
    <span v-else-if="modeNote && action === 'release'" class="rc-mode-note solo">{{ modeNote }}</span>

    <div v-if="canStopLocal && action === 'release'" class="rc-local">
      <span class="rc-local-hint">{{ localHint }}</span>
      <button type="button" class="rc-stop" @click="emit('stopLocal')">停止本机服务</button>
    </div>

    <div class="rc-actions">
      <el-button
        type="primary" size="large" :icon="VideoPlay"
        :loading="busy" :disabled="!canRun"
        @click="emit('run')"
      >
        {{ runLabel }}
      </el-button>
      <el-button
        size="large" :loading="dryRunBusy" :disabled="!canRun"
        title="只读预检：检查配置/密钥/Docker，不执行任何变更"
        @click="emit('dryrun')"
      >
        预检
      </el-button>
      <el-button
        size="large" :loading="agentBusy" :disabled="!canRun || agentDisabled"
        :title="agentHint || '由发布 Agent 自动执行：自检 → 构建 → 部署 → 验证；执行过程实时展示'"
        @click="emit('agent')"
      >
        发布 Agent
      </el-button>
      <button
        v-if="lastGoodSha"
        type="button" class="rc-last mono"
        title="填入最近一次成功部署的提交"
        @click="emit('update:sha', lastGoodSha.slice(0, 8))"
      >
        上次成功 {{ lastGoodSha.slice(0, 8) }}
      </button>
    </div>

    <!-- dry-run 报告：阻塞项必须处理，警告可继续；不触发任何外部变更 -->
    <div v-if="dryRun" class="dr-panel" :class="{ bad: !dryRun.ok }">
      <div class="dr-head">
        <span class="dr-title">预检{{ dryRun.ok ? '通过' : '未通过' }}</span>
        <span class="dr-meta mono">report {{ dryRun.reportHash.slice(0, 10) }}</span>
        <span class="dr-meta mono">fp {{ dryRun.fingerprint.slice(0, 10) }}</span>
        <span class="dr-meta">{{ dryRun.env }} · {{ dryRun.gitSha.slice(0, 8) }}</span>
      </div>
      <ul v-if="dryRun.blockers.length" class="dr-list bad">
        <li v-for="(b, i) in dryRun.blockers" :key="i">{{ b }}</li>
      </ul>
      <ul v-if="dryRun.warnings.length" class="dr-list warn">
        <li v-for="(w, i) in dryRun.warnings" :key="i">{{ w }}</li>
      </ul>
      <p v-if="!dryRun.blockers.length && !dryRun.warnings.length" class="dr-meta">
        没有阻塞项与警告，可以运行。
      </p>
      <div v-if="dryRun.stages.length" class="dr-stages">
        <span
          v-for="s in dryRun.stages" :key="s.id"
          class="dr-stage" :class="{ off: !s.runnable }"
          :title="s.reason || s.command || s.action"
        >
          {{ s.id }}
        </span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.run-card {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.rc-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.rc-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.rc-env {
  padding: 1px 7px;
  font-size: 11px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.rc-hint {
  flex: 1;
  font-size: 11px;
  color: var(--ink-faint);
}
.rc-commit {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rc-label {
  flex: none;
  width: 42px;
  font-size: 11.5px;
  color: var(--ink-muted);
}
.rc-sha {
  flex: 1;
  min-width: 0;
}
.rc-refresh {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: 13px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.rc-refresh:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.rc-refresh:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.rc-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 26px;
}
/* 动作选择卡：发布 / 只检查构建，点一下就切换，主按钮文案跟着变 */
.rc-pick {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.pick-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  transition: border-color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.pick-card:hover {
  border-color: var(--line-soft);
}
.pick-card.active {
  background: color-mix(in srgb, var(--accent) 10%, var(--panel));
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.pc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.pc-title {
  overflow: hidden;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pick-card.active .pc-title {
  color: var(--accent-ink);
}
.pc-check {
  flex: none;
  font-size: 10px;
  color: var(--accent-ink);
}
.pc-desc {
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pc-meta {
  font-size: 10.5px;
  color: var(--ink-muted);
}
.rc-skip {
  margin: -4px 0 0 50px;
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--ink-faint);
}
.rc-tip {
  flex: none;
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-faint);
}
/* 命令级预览 */
.cmd-panel {
  padding: 8px 10px;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.cmd-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.cmd-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.cmd-chip {
  padding: 0 6px;
  font-size: 10px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.cmd-meta {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.cmd-spacer {
  flex: 1;
}
.cmd-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 22px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-xs);
}
.cmd-btn:hover {
  color: var(--accent-ink);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}
.cmd-code {
  max-height: 180px;
  padding: 8px;
  margin: 0;
  overflow: auto;
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 10.5px;
  line-height: 1.55;
  color: var(--ink-soft);
  white-space: pre;
  background: var(--panel);
  border-radius: var(--radius-xs);
}
.cmd-builtin {
  margin: 0;
  font-size: 11px;
  color: var(--ink-muted);
}
.flow {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 0;
  margin: 0;
  list-style: none;
}
.flow-step {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px 2px 3px;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
}
.flow-step:hover {
  color: var(--ink);
  border-color: var(--line-soft);
}
.flow-step.open {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}
.flow-step.open .flow-no {
  color: var(--accent-ink);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}
.flow-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  font-size: 9.5px;
  color: var(--ink-faint);
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: 50%;
}
.flow-name {
  white-space: nowrap;
}
.flow-empty {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.rc-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
/* dry-run 报告 */
.dr-panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-left: 3px solid var(--ok);
  border-radius: var(--radius-sm);
}
.dr-panel.bad {
  border-left-color: var(--danger);
}
.dr-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
}
.dr-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.dr-meta {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.dr-list {
  padding-left: 18px;
  margin: 2px 0 0;
  font-size: 11px;
  line-height: 1.6;
}
.dr-list.bad {
  color: var(--danger);
}
.dr-list.warn {
  color: var(--warn-ink);
}
.dr-stages {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 2px;
}
.dr-stage {
  padding: 0 6px;
  font-family: var(--font-mono);
  font-size: 10px;
  line-height: 18px;
  color: var(--ink-muted);
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-xs);
}
.dr-stage.off {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 35%, transparent);
  text-decoration: line-through;
}
.rc-last {
  padding: 0;
  font-size: 11px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline dotted;
}
.rc-mode {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.mode-switch {
  display: inline-flex;
  padding: 2px;
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.mode-btn {
  height: 24px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-pill);
}
.mode-btn:hover {
  color: var(--ink);
}
.mode-btn:disabled {
  color: var(--ink-faint);
  cursor: not-allowed;
  opacity: 0.5;
}
.mode-btn.active {
  color: var(--accent-ink);
  background: var(--panel);
  box-shadow: 0 1px 2px rgb(0 0 0 / 8%);
}
.rc-mode-note {
  flex: 1 1 160px;
  font-size: 11px;
  color: var(--ink-faint);
}
.rc-mode-note.solo {
  padding-left: 50px;
}
.rc-local {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 9px;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.rc-local-hint {
  flex: 1;
  font-size: 11px;
  color: var(--ink-muted);
}
.rc-stop {
  flex: none;
  padding: 0;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--danger);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline dotted;
}
.commit-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  max-width: 520px;
}
.commit-sha {
  flex: none;
  font-size: 11.5px;
  color: var(--accent-ink);
}
.commit-subject {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.commit-meta {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
}
</style>
