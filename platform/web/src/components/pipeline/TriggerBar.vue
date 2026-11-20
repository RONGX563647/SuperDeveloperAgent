<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Download, Refresh, VideoPlay } from '@element-plus/icons-vue'
import type { GitCommit } from '../../api/project'
import type { DryRunReportView, PreviewStage } from '../../api/pipeline'
import { previewLabel } from '../../utils/pipelineLabels'
import { releaseCandidateLabel, type ReleaseCandidate } from '../../utils/releaseCandidates'

/**
 * 运行卡：一次运行只需要三件事——提交、选部署方式、点运行。
 *
 * - 提交从 git 历史里选（可搜索/可粘贴 sha），默认最新一条；
 * - 阶段预览来自后端同一份定义（不是前端猜的），避免"以为是发布、实际只跑了检查构建"；
 * - 部署方式只有两种：打包成镜像（Docker）/ 直接部署（本机）；流水线由环境自动解析。
 */
interface Props {
  envLabel: string
  /** 生产环境：只做直接部署，发布对象从测试验证过的版本里选。 */
  isProd?: boolean
  primaryLabel: string
  sha: string
  commits: GitCommit[]
  commitsLoading?: boolean
  /** 生产发布候选（来自测试环境镜像账本，含镜像 tag 与对应 git tag）。 */
  releaseCandidates?: ReleaseCandidate[]
  releaseCandidateKey?: string
  releaseCandidatesLoading?: boolean
  /** 版本来源环境名（生产固定 test），用于说明文案。 */
  sourceEnvLabel?: string
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
  /** 版本号（打 tag 时必填；不打 tag 时作为快照版本展示）。 */
  version: string
  /** 是否打 tag：true = 正式版本独立部署；false = 快照构建。 */
  tagged: boolean
  /** 发布分支（版本线分支 x.y.0-snapshot）：版本与分支强绑定。 */
  branch: string
  branches?: string[]
  /** 是否展示版本控制（正式发布/快照二分）。 */
  showVersion?: boolean
  /** 仓库现有 git 标签（版本降序）：版本号候选 + 重复校验。 */
  existingTags?: string[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'update:sha', v: string): void
  (e: 'update:deployTarget', v: string): void
  (e: 'update:action', v: 'release' | 'verify'): void
  (e: 'update:version', v: string): void
  (e: 'update:tagged', v: boolean): void
  (e: 'update:branch', v: string): void
  (e: 'update:releaseCandidateKey', v: string): void
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

// ---------------------------------------------------------------- 版本号 / tag

/** 版本线分支：0.1.0-snapshot → 0.1（一个版本线一个分支）。 */
const branchLine = computed(() => {
  const m = /^(\d+)\.(\d+)\.0-snapshot$/.exec(props.branch.trim())
  return m ? `${m[1]}.${m[2]}` : ''
})

/**
 * 推荐版本号：当前分支版本线上最大的补丁号 +1，不跳号、不带 v 前缀。
 *
 * <p>没有标签时用版本线的 x.y.0；不在版本分支上时退回 0.1.0。
 */
const suggestedTag = computed(() => {
  const line = branchLine.value || '0.1'
  let best = -1
  for (const tag of props.existingTags ?? []) {
    const m = new RegExp(`^${line.replace('.', '\\.')}\\.(\\d+)$`).exec(tag.trim())
    if (!m) continue
    best = Math.max(best, Number(m[1]))
  }
  return best < 0 ? `${line}.0` : `${line}.${best + 1}`
})

/** 候选标签：推荐版本（若还不存在）+ 已有标签，便于直接点选。 */
const tagOptions = computed(() => {
  const tags = props.existingTags ?? []
  return props.tagged && !tags.includes(suggestedTag.value)
    ? [suggestedTag.value, ...tags]
    : tags
})

/** 打了 tag 却填了已有标签：git 侧会拒绝（或指向别的提交），提前拦住。 */
const tagConflict = computed(() =>
  props.tagged && !!props.version.trim()
  && (props.existingTags ?? []).includes(props.version.trim()))

/** 打开"打 tag"时自动补一个建议版本号，避免空着触发失败。 */
watch(() => props.tagged, (on) => {
  if (on && !props.version.trim()) {
    emit('update:version', suggestedTag.value)
  }
})

function useSuggestedTag(): void {
  emit('update:version', suggestedTag.value)
}

const runLabel = computed(() => {
  if (props.isProd) return '直接部署'
  return props.action === 'verify' ? '开始检查构建' : props.primaryLabel
})

/** 选中的候选：下拉只显示摘要，下面单独把版本 / 镜像 tag / git tag 展开。 */
const selectedCandidate = computed<ReleaseCandidate | null>(() =>
  (props.releaseCandidates ?? []).find((c) => c.imageTag === props.releaseCandidateKey) ?? null)

function onCandidateChange(value: string): void {
  emit('update:releaseCandidateKey', value)
}
</script>

<template>
  <section class="run-card" aria-label="触发运行">
    <header class="rc-head">
      <h2 class="rc-title">运行</h2>
      <span class="rc-env">{{ envLabel }}</span>
      <span v-if="hint" class="rc-hint">{{ hint }}</span>
    </header>

    <!-- 生产：不按 git 分支/提交选发布对象，只从测试验证过的版本里选 -->
    <template v-if="isProd">
      <div class="rc-commit rc-commit-wide">
        <label class="rc-label" for="release-candidate-select">测试版本</label>
        <el-select
          id="release-candidate-select"
          :model-value="releaseCandidateKey"
          class="rc-sha" size="default" filterable
          placeholder="选择测试环境构建并部署过的版本"
          :loading="releaseCandidatesLoading"
          @update:model-value="(v: string) => onCandidateChange(v)"
        >
          <el-option
            v-for="c in releaseCandidates ?? []" :key="c.imageTag"
            :label="releaseCandidateLabel(c)" :value="c.imageTag"
          />
        </el-select>
      </div>
      <div v-if="selectedCandidate" class="rc-release-tags">
        <span class="rc-rel-item">
          <span class="rc-rel-key">测试版本</span>
          <code class="mono">{{ selectedCandidate.version || '—' }}</code>
        </span>
        <span class="rc-rel-item">
          <span class="rc-rel-key">镜像 tag</span>
          <code class="mono">{{ selectedCandidate.imageTag }}</code>
        </span>
        <span class="rc-rel-item">
          <span class="rc-rel-key">git 标签</span>
          <code class="mono" :class="{ 'rc-rel-warn': selectedCandidate.tagStatus === 'LOCAL' }">
            {{ selectedCandidate.gitTag || '未打正式标签' }}
          </code>
          <span v-if="selectedCandidate.tagStatus === 'LOCAL'" class="rc-rel-warn">仅本地</span>
        </span>
        <span class="rc-rel-item">
          <span class="rc-rel-key">提交</span>
          <code class="mono">{{ selectedCandidate.gitSha.slice(0, 8) }}</code>
        </span>
      </div>
      <p v-else class="rc-release-empty">
        {{ releaseCandidatesLoading ? '正在读取测试环境版本…'
          : `测试环境还没有可发布的版本：请先在${sourceEnvLabel || '测试'}环境构建并部署一次` }}
      </p>
    </template>

    <template v-else>
      <div class="rc-commit">
        <label class="rc-label" for="branch-select">分支</label>
      <el-select
        id="branch-select"
        :model-value="branch"
        class="rc-sha" size="default" filterable
        placeholder="选择发布分支（版本线 x.y.0-snapshot）"
        @update:model-value="(v: string) => emit('update:branch', v)"
      >
        <el-option v-for="b in branches ?? []" :key="b" :label="b" :value="b" />
      </el-select>
      <span class="rc-version-note" :class="{ bad: !branchLine && tagged }">
        {{ branchLine
          ? `版本线 ${branchLine}：补丁版本共用该分支`
          : '非版本分支：只能做快照构建' }}
      </span>
    </div>

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
    </template>

    <div class="rc-pick" :class="{ solo: isProd }" role="group" aria-label="选择动作">
      <button
        type="button" class="pick-card" :class="{ active: action === 'release' || isProd }"
        @click="emit('update:action', 'release')"
      >
        <span class="pc-head">
          <span class="pc-title">{{ isProd ? '直接部署' : primaryLabel }}</span>
          <span v-if="isProd || action === 'release'" class="pc-check">已选</span>
        </span>
        <span class="pc-desc">
          {{ isProd ? '把测试验证过的版本发布到生产，不重新构建' : '构建并部署到当前环境' }}
        </span>
        <span class="pc-meta">{{ releaseMeta.count }} 步 · {{ releaseMeta.image ? '产镜像' : '不产镜像' }}</span>
      </button>
      <button
        v-if="!isProd"
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

    <!-- 版本控制：打 tag = 独立版本槽（旧版本继续运行）；不打 tag = 覆盖默认部署 -->
    <div v-if="showVersion && !isProd && action === 'release'" class="rc-version">
      <span class="rc-label">版本</span>
      <el-select
        :model-value="version"
        class="rc-version-input"
        size="default"
        filterable
        allow-create
        default-first-option
        clearable
        placeholder="版本号，例如 0.1.0（不带 v）"
        @update:model-value="(v: string) => emit('update:version', v)"
      >
        <el-option v-for="t in tagOptions" :key="t" :label="t" :value="t">
          <span class="tag-option">
            <span class="mono">{{ t }}</span>
            <span v-if="t === suggestedTag" class="tag-option-hint">推荐</span>
          </span>
        </el-option>
      </el-select>
      <el-switch
        :model-value="tagged"
        size="default"
        active-text="打 tag"
        @update:model-value="(v: boolean | string | number) => emit('update:tagged', v === true)"
      />
      <span v-if="tagged && !version.trim()" class="rc-tag-suggest">
        用建议版本
        <button type="button" @click="useSuggestedTag">{{ suggestedTag }}</button>
      </span>
      <span class="rc-version-note" :class="{ bad: tagConflict }">
        {{ tagConflict
          ? `标签 ${version.trim()} 已存在，请换一个补丁版本`
          : tagged
            ? `正式版本：发布成功后创建标签 ${version.trim() || suggestedTag} 并推送 origin（补丁不新建分支）`
            : `快照构建：不打 tag，按 ${(version.trim() || suggestedTag)}-snapshot 独立部署` }}
      </span>
    </div>

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
        v-if="!isProd"
        size="large" :loading="agentBusy" :disabled="!canRun || agentDisabled"
        :title="agentHint || '由发布 Agent 自动执行：自检 → 构建 → 部署 → 验证；执行过程实时展示'"
        @click="emit('agent')"
      >
        发布 Agent
      </el-button>
      <button
        v-if="lastGoodSha && !isProd"
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
.rc-commit-wide .rc-label {
  width: 60px;
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
.rc-pick.solo {
  grid-template-columns: minmax(0, 1fr);
}
/* 生产候选的版本/tag 明细：不发 Token，一眼核对发布对象 */
.rc-release-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  padding: 7px 9px;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.rc-rel-item {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  min-width: 0;
}
.rc-rel-key {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.rc-rel-item code {
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rc-rel-warn {
  color: var(--warn-ink) !important;
}
.rc-release-empty {
  margin: -2px 0 0 50px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--warn-ink);
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
  background: var(--accent-soft);
  border-color: var(--accent-ring);
}
.pc-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-3);
  min-width: 0;
}
.pc-title {
  min-width: 0;
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
  overflow-wrap: anywhere;
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
  line-height: 1.45;
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
.rc-version {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.rc-version-input {
  width: 180px;
}
.rc-version-note {
  flex: 1 1 180px;
  font-size: 11px;
  color: var(--ink-faint);
}
.rc-version-note.bad {
  color: var(--danger);
}
.tag-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tag-option-hint {
  font-size: 10px;
  color: var(--accent-ink);
}
.rc-tag-suggest {
  font-size: 11px;
  color: var(--ink-muted);
}
.rc-tag-suggest button {
  padding: 0;
  font-family: inherit;
  font-size: 11px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline dotted;
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
