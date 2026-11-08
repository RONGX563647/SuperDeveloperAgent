<script setup lang="ts">
import { computed } from 'vue'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import type { GitCommit } from '../../api/project'

/**
 * 触发条（易用性改造）：
 * - 提交从项目 git 历史里**选**（可搜索、可粘贴），不再要求手敲 sha；
 * - 流水线按人类可读名称展示（发布 / 检查构建）并给出说明；
 * - 部署目标可选（方案 C：平台部署器）；
 * - 校验失败给出可读原因。
 */
interface Props {
  pipelines: string[]
  pipelineId: string
  sha: string
  busy: boolean
  lastGoodSha?: string
  engine?: string
  deployTarget: string
  deployTargets: Array<{ target: string, label: string, available: boolean, detail: string }>
  commits: GitCommit[]
  commitsLoading?: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'update:pipelineId', v: string): void
  (e: 'update:sha', v: string): void
  (e: 'update:deployTarget', v: string): void
  (e: 'run'): void
  (e: 'refreshCommits'): void
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

const shaValid = computed(() => /^[0-9a-f]{7,40}$/.test(props.sha))
const hint = computed(() => {
  // 定义通常在接入时由平台自动生成（存平台侧），仓库里的 ci/pipeline.yml 只是可选覆盖；
  // 所以这里只在"确实读不到"时给一句排查指引。
  if (!props.pipelines.length) {
    return '未读取到流水线定义：确认项目已接入后刷新，或在项目页「生成流水线定义」重新生成'
  }
  if (!props.pipelineId) return '请选择流水线'
  if (!props.sha) return '请选择提交（从项目 git 历史中选，或粘贴 sha）'
  if (!shaValid.value) return 'sha 需为 7–40 位十六进制'
  return ''
})
const canRun = computed(() => !!props.pipelineId && shaValid.value && !props.busy)

/** 流水线的人类可读名称（release/verify 不直接暴露）。 */
function labelOf(id: string): string {
  if (id === 'release') return '发布'
  if (id === 'verify') return '检查构建'
  return id
}

function descOf(id: string): string {
  if (id === 'release') return '构建 → 测试 → 镜像 → 部署 → 健康检查'
  if (id === 'verify') return '只构建 + 测试，不部署'
  return '仓库自定义'
}

function onShaChange(value: string): void {
  emit('update:sha', normalizeSha(value))
}
</script>

<template>
  <div class="trigger">
    <div class="t-row">
      <el-select
        :model-value="pipelineId"
        class="t-pipeline" size="default" placeholder="选择流水线"
        @update:model-value="emit('update:pipelineId', $event)"
      >
        <el-option v-for="p in pipelines" :key="p" :label="labelOf(p)" :value="p">
          <span class="opt-main">{{ labelOf(p) }}</span>
          <span class="opt-detail">{{ descOf(p) }}</span>
        </el-option>
      </el-select>

      <el-select
        :model-value="sha"
        class="t-sha" size="default" filterable allow-create default-first-option
        placeholder="选择提交（或粘贴 sha）"
        :loading="commitsLoading"
        @update:model-value="onShaChange"
      >
        <el-option
          v-for="c in commits" :key="c.sha"
          :label="`${c.sha} · ${c.subject}`" :value="c.sha"
        >
          <div class="commit-row">
            <span class="commit-sha mono">{{ c.sha }}</span>
            <span class="commit-subject">{{ c.subject }}</span>
            <span class="commit-meta">{{ c.author }} · {{ c.relativeTime }}</span>
          </div>
        </el-option>
      </el-select>

      <button
        type="button" class="t-refresh" :disabled="commitsLoading"
        title="刷新提交列表" @click="emit('refreshCommits')"
      >
        <el-icon :class="{ 'is-loading': commitsLoading }"><Refresh /></el-icon>
      </button>

      <el-select
        :model-value="deployTarget"
        class="t-target" size="default" title="部署目标"
        @update:model-value="emit('update:deployTarget', $event)"
      >
        <el-option
          v-for="t in deployTargets" :key="t.target"
          :label="t.label" :value="t.target" :disabled="!t.available"
        >
          <span class="opt-main">{{ t.label }}</span>
          <span class="opt-detail">{{ t.available ? t.detail : '不可用' }}</span>
        </el-option>
      </el-select>

      <el-button
        type="primary" :icon="VideoPlay" :loading="busy" :disabled="!canRun"
        @click="emit('run')"
      >
        运行
      </el-button>
    </div>

    <div class="t-meta">
      <span v-if="hint" class="t-hint">{{ hint }}</span>
      <span v-else class="t-hint ok">
        {{ engine ? `引擎 ${engine}` : '就绪' }} · {{ labelOf(pipelineId) }} · 提交 {{ sha.slice(0, 8) }}
      </span>
      <span class="t-target-note">
        部署到：{{ deployTargets.find((t) => t.target === deployTarget)?.label ?? deployTarget }}
      </span>
      <button
        v-if="lastGoodSha"
        type="button" class="t-quick mono"
        title="回填最近一次成功构建的提交"
        @click="emit('update:sha', lastGoodSha.slice(0, 8))"
      >
        上次成功 {{ lastGoodSha.slice(0, 8) }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.trigger {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.t-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.t-pipeline {
  width: 160px;
}
.t-sha {
  flex: 1 1 260px;
  min-width: 0;
}
.t-refresh {
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
.t-refresh:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.t-refresh:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.t-target {
  width: 150px;
}
.opt-main {
  margin-right: 8px;
}
.opt-detail {
  font-size: 11px;
  color: var(--ink-faint);
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
.t-target-note {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-faint);
}
.t-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 16px;
}
.t-hint {
  font-size: 11px;
  color: var(--ink-faint);
}
.t-hint.ok {
  color: var(--ok);
}
.t-quick {
  margin-left: 8px;
  padding: 0;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline dotted;
}
</style>
