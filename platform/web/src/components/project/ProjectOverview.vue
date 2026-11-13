<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import { gitCommits, gitStatus, type GitCommit, type GitStatus, type ProjectRow } from '../../api/project'
import ProjectReadiness from './ProjectReadiness.vue'

/**
 * 项目概览（左侧树选中根节点时的右侧详情）：
 * 名称/来源标签、Git 仓库地址（一键复制）、本地路径、分支、最近提交；
 * 底部快捷操作：运行流水线。
 */
interface Props {
  project: ProjectRow
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'runPipeline'): void
}>()

const status = ref<GitStatus | null>(null)
const loading = ref(false)

/** 提交历史（独立分页，比概览的最近 5 条更全）。 */
const commits = ref<GitCommit[]>([])
const commitsLoading = ref(false)
const commitsHasMore = ref(false)
const COMMIT_PAGE = 20

async function load(): Promise<void> {
  loading.value = true
  try {
    status.value = await gitStatus(props.project.id)
  } catch {
    // 预置项目可能没有 git 托管，静默降级为纯静态信息
    status.value = null
  } finally {
    loading.value = false
  }
  await loadCommits(true)
}

/** 加载提交历史；reset=true 从头开始。 */
async function loadCommits(reset = false): Promise<void> {
  if (commitsLoading.value) {
    return
  }
  commitsLoading.value = true
  try {
    const skip = reset ? 0 : commits.value.length
    const page = await gitCommits(props.project.id, COMMIT_PAGE, skip)
    commits.value = reset ? page.commits : [...commits.value, ...page.commits]
    commitsHasMore.value = page.hasMore
  } catch {
    if (reset) {
      commits.value = []
    }
    commitsHasMore.value = false
  } finally {
    commitsLoading.value = false
  }
}

onMounted(load)

/** 滚动到接近底部时自动加载下一页（懒加载；无按钮）。 */
function onCommitsScroll(e: Event): void {
  const el = e.target as HTMLElement | null
  if (!el || commitsLoading.value || !commitsHasMore.value) {
    return
  }
  // 距底部 60px 内触发；overscroll-behavior 保证不带动页面
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 60) {
    void loadCommits(false)
  }
}

// 切换项目时重新加载（否则面板停留在上一个项目的状态）
watch(
  () => props.project.id,
  () => {
    status.value = null
    commits.value = []
    commitsHasMore.value = false
    void load()
  }
)

const SOURCE_LABEL: Record<string, string> = {
  PRESET: '预置',
  LOCAL: '本地',
  REMOTE: '远程',
}

const headText = computed(() => status.value?.head ? status.value.head.slice(0, 10) : '—')
/** 分支：优先单值 branch（DIRECT），回退 branches 列表（MANAGED）。 */
const branchText = computed(() => {
  const s = status.value
  if (!s) return '—'
  if (s.branch) return s.branch
  return s.branches?.join(', ') || '—'
})
/** 远程地址：优先 git 配置的 origin（本地接入项目 project.repoUrl 为空）。 */
const remoteUrl = computed(() => {
  const remotes = status.value?.remotes ?? []
  return (remotes.find(r => r.name === 'origin') ?? remotes[0])?.url ?? props.project.repoUrl ?? ''
})
const remoteName = computed(() => {
  const remotes = status.value?.remotes ?? []
  return (remotes.find(r => r.name === 'origin') ?? remotes[0])?.name ?? ''
})
const uncommittedCount = computed(() => status.value?.uncommitted.length ?? 0)
const storageModeText = computed(() =>
  status.value?.storageMode === 'DIRECT' ? '就地（仓库在你的目录）' : '平台托管（bare）'
)

async function copyRepo(): Promise<void> {
  const url = remoteUrl.value
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('仓库地址已复制')
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板）')
  }
}
</script>

<template>
  <div class="proj-overview">
    <div class="po-head">
      <div class="po-id-block">
        <h2 class="po-name">{{ project.name }}</h2>
        <span class="po-id mono">{{ project.id }}</span>
        <span class="po-src" :class="project.source.toLowerCase()">
          {{ SOURCE_LABEL[project.source] ?? project.source }}
        </span>
      </div>
      <div class="po-actions">
        <button type="button" class="po-btn" :disabled="loading" @click="load">
          <el-icon><RefreshRight /></el-icon> 刷新
        </button>
        <button type="button" class="po-btn primary" @click="emit('runPipeline')">
          <el-icon><VideoPlay /></el-icon> 运行流水线
        </button>
      </div>
    </div>

    <!-- 首次发布就绪检查：前置条件与修复入口集中在这里，避免到处找配置 -->
    <ProjectReadiness :project-id="project.id" />

    <div class="po-grid">
      <section class="po-card">
        <h3 class="po-card-title">仓库</h3>
        <div class="po-line">
          <span class="po-label">Git 地址</span>
          <template v-if="remoteUrl">
            <code class="po-value mono" :title="remoteUrl">{{ remoteUrl }}</code>
            <button type="button" class="po-copy" title="复制" @click="copyRepo">
              <el-icon><CopyDocument /></el-icon>
            </button>
          </template>
          <span v-else class="po-value muted">（无远程仓库）</span>
        </div>
        <div class="po-line">
          <span class="po-label">远程名</span>
          <span class="po-value mono">{{ remoteName || '—' }}</span>
        </div>
        <div class="po-line">
          <span class="po-label">本地路径</span>
          <code class="po-value mono">{{ project.localPath ?? '—' }}</code>
        </div>
        <div class="po-line">
          <span class="po-label">类型</span>
          <span class="po-value">{{ project.type || '—' }}</span>
        </div>
      </section>

      <section class="po-card">
        <h3 class="po-card-title">git 状态</h3>
        <template v-if="status?.initialized">
          <div class="po-line">
            <span class="po-label">HEAD</span>
            <span class="po-value mono">{{ headText }}</span>
          </div>
          <div class="po-line">
            <span class="po-label">分支</span>
            <span class="po-value mono">
              {{ branchText }}
              <span v-if="status.upstream" class="po-upstream">→ {{ status.upstream }}</span>
            </span>
          </div>
          <div class="po-line">
            <span class="po-label">与远端</span>
            <span v-if="status.upstream" class="po-value ab">
              <span class="ab-ahead">↑ {{ status.ahead ?? 0 }} 待推送</span>
              <span class="ab-behind">↓ {{ status.behind ?? 0 }} 待拉取</span>
            </span>
            <span v-else class="po-value muted">本地分支未跟踪远端</span>
          </div>
          <div class="po-line">
            <span class="po-label">未提交</span>
            <span class="po-value" :class="{ warn: uncommittedCount > 0 }">
              {{ uncommittedCount }} 个文件
            </span>
          </div>
          <div class="po-line">
            <span class="po-label">工作树</span>
            <span class="po-value">{{ status.worktrees.length }} 个（{{ storageModeText }}）</span>
          </div>
        </template>
        <p v-else-if="loading" class="po-hint">加载中…</p>
        <p v-else class="po-hint">该项目尚未完成 git 接入</p>
      </section>
    </div>

    <!-- 提交历史（卡片内独立滚动，不牵动整页） -->
    <section class="po-card po-commits-card">
      <div class="po-commits-head">
        <h3 class="po-card-title" style="margin: 0">提交历史</h3>
        <span class="po-commits-count">{{ commits.length }} 条</span>
      </div>
      <div v-if="commits.length" class="po-commits-scroll" @scroll.passive="onCommitsScroll">
        <div class="po-commits">
          <div v-for="c in commits" :key="c.sha" class="po-commit">
            <code class="po-commit-sha mono">{{ c.sha }}</code>
            <span class="po-commit-subject" :title="c.subject">{{ c.subject }}</span>
            <span class="po-commit-meta">{{ c.author }} · {{ c.relativeTime }}</span>
          </div>
        </div>
        <!-- 懒加载状态行：滚到底部自动加载，无需按钮 -->
        <div class="po-scroll-status">
          <span v-if="commitsLoading">加载中…</span>
          <span v-else-if="!commitsHasMore">已全部加载（{{ commits.length }} 条）</span>
        </div>
      </div>
      <p v-else-if="commitsLoading" class="po-hint">加载提交历史…</p>
      <p v-else-if="status?.initialized" class="po-hint">
        HEAD <code class="mono">{{ headText }}</code>
        <span v-if="uncommittedCount"> · {{ uncommittedCount }} 个未提交变更</span>
      </p>
      <p v-else class="po-hint">接入后可查看提交历史</p>
    </section>
  </div>
</template>

<style scoped>
.proj-overview {
  flex: 1 1 0;
  min-height: 0;
  padding: 24px 28px;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}
.po-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.po-id-block {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.po-name {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--ink);
}
.po-id {
  font-size: 12.5px;
  color: var(--ink-soft);
}
.po-src {
  padding: 2px 9px;
  font-size: 11px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.po-src.local {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.po-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}
.po-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.po-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.po-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.po-btn.primary {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}
.po-btn.primary:hover:not(:disabled) {
  background: var(--accent-hover);
}

.po-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

/* 手机：卡片单列、头部操作换行，避免 320px 卡片撑出横向滚动 */
@media (max-width: 600px) {
  .proj-overview {
    padding: 14px 12px calc(20px + env(safe-area-inset-bottom));
  }
  .po-head {
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 14px;
  }
  .po-actions {
    width: 100%;
    justify-content: flex-end;
  }
  .po-grid {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }
}
.po-card {
  padding: 16px 18px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.po-card-title {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.po-line {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 0;
  font-size: 13px;
}
.po-label {
  flex: none;
  width: 68px;
  font-size: 12px;
  color: var(--ink-muted);
}
.po-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.po-value.muted {
  color: var(--ink-faint);
}
.po-value.warn {
  color: var(--warn);
}
.po-copy {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.po-copy:hover {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.po-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-faint);
}
.po-upstream {
  margin-left: 6px;
  font-size: 11px;
  color: var(--ink-faint);
}
.ab {
  display: flex;
  gap: 10px;
  font-size: 12px;
}
.ab-ahead {
  color: var(--warn);
}
.ab-behind {
  color: var(--accent-ink);
}
.po-commits {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
/* 提交历史卡片：固定高度 + 内部独立滚动（整页不跟着滚） */
.po-commits-card {
  display: flex;
  flex-direction: column;
  max-height: 360px;
}
.po-commits-scroll {
  flex: 1;
  min-height: 0;
  padding-right: 6px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: var(--line) transparent;
}
.po-commits-scroll::-webkit-scrollbar {
  width: 8px;
}
.po-commits-scroll::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--ink-faint) 35%, transparent);
  border: 2px solid transparent;
  border-radius: 4px;
  background-clip: padding-box;
}
.po-commits-scroll::-webkit-scrollbar-thumb:hover {
  background: color-mix(in srgb, var(--ink-muted) 50%, transparent);
  background-clip: padding-box;
}
.po-commits-scroll::-webkit-scrollbar-track {
  background: transparent;
}
.po-commits-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}
.po-commits-count {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.po-scroll-status {
  padding: 10px 0 4px;
  font-size: 11.5px;
  color: var(--ink-faint);
  text-align: center;
}
.po-commit {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 12.5px;
}
.po-commit-sha {
  flex: none;
  padding: 1px 6px;
  font-size: 11px;
  color: var(--accent-ink);
  background: var(--accent-soft, var(--surface-sunken));
  border-radius: 3px;
}
.po-commit-subject {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.po-commit-meta {
  flex: none;
  font-size: 11px;
  color: var(--ink-faint);
}
</style>
