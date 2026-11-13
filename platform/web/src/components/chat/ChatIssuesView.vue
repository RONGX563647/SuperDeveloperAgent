<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight, ChatDotRound, Close, Delete, Edit, Link, Plus, Refresh, RefreshRight, Search,
} from '@element-plus/icons-vue'
import {
  addGitIssueComment, closeGitIssue, createGitIssue, deleteGitIssue, deleteGitIssueComment,
  listGitIssueComments, listGitIssues, reopenGitIssue, syncGitIssue, updateGitIssue,
  type GitIssueCommentRow, type GitIssueRow,
} from '../../api/issues'
import MarkdownView from './MarkdownView.vue'
import { appStore } from '../../stores/app'
import { listProjects, type ProjectRow } from '../../api/project'

/**
 * 对话页「任务」Tab：项目托管的 Git Issue 双向同步任务。
 *
 * 采用单列列表：一行一个任务，点击行展开描述 / 评论 / 操作；新建与编辑
 * 直接以列表顶部的卡片形式出现，不使用悬浮弹窗。数据与 Agent 工具同源。
 */
const emit = defineEmits<{ (e: 'count', value: number): void }>()

const items = ref<GitIssueRow[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const stateFilter = ref('')
const kindFilter = ref('')
const expandedId = ref('')

const comments = ref<GitIssueCommentRow[]>([])
const commentsLoading = ref(false)
const commentText = ref('')
const commenting = ref(false)
const actionBusy = ref('')

const stateOptions = [
  { label: '全部', value: '' },
  { label: '待处理', value: 'OPEN' },
  { label: '已完成', value: 'CLOSED' },
]

/** 任务分类：Bug（缺陷） / Feat（需求）。 */
const kindOptions = [
  { label: '全部类型', value: '' },
  { label: 'Bug', value: 'BUG' },
  { label: 'Feat', value: 'FEAT' },
]

/** 分类在前端过滤：数据量小，切 tab 无需再请求。 */
const visibleItems = computed(() => kindFilter.value
  ? items.value.filter((i) => (i.kind ?? 'BUG') === kindFilter.value)
  : items.value)

const PROVIDER_LABEL: Record<string, string> = {
  GITEE: 'Gitee', GITHUB: 'GitHub', GITLAB: 'GitLab',
}

const projects = ref<ProjectRow[]>([])
const currentProject = computed(
  () => projects.value.find((p) => p.id === appStore.projectId) ?? null)

/** 当前项目托管的仓库（通常只有一个），用于页头提示与新建任务同步目标。 */
const currentRepo = computed(() => {
  const url = currentProject.value?.repoUrl
  return { provider: providerOfUrl(url), repo: repoOfUrl(url) }
})

/** 已绑定仓库去重：只有一个仓库时行内只显示 #编号，避免每行重复仓库名。 */
const boundRepos = computed(() => [
  ...new Set(items.value.filter((i) => isBound(i)).map((i) => `${i.provider}/${i.repo}`)),
])
const singleRepo = computed(() => boundRepos.value.length <= 1)

let loadSeq = 0
let keywordTimer: ReturnType<typeof setTimeout> | undefined

function providerLabel(provider: string): string {
  return PROVIDER_LABEL[provider] ?? (provider || 'Git')
}

function providerOfUrl(url?: string | null): string {
  const text = (url ?? '').toLowerCase()
  if (text.includes('gitee')) return 'GITEE'
  if (text.includes('gitlab')) return 'GITLAB'
  if (text.includes('github')) return 'GITHUB'
  return ''
}

/** https://host/owner/repo(.git) 或 git@host:owner/repo.git → owner/repo */
function repoOfUrl(url?: string | null): string {
  if (!url) return ''
  return url
    .replace(/^git@([^:]+):/, 'https://$1/')
    .replace(/^https?:\/\/[^/]+\//, '')
    .replace(/\.git$/, '')
    .replace(/\/+$/, '')
}

function syncLabel(row: GitIssueRow): string {
  if (row.syncStatus === 'SYNCED') return '已同步'
  if (row.syncStatus === 'FAILED') return '同步失败'
  if (row.syncStatus === 'PENDING') return '同步中'
  return '仅本地'
}

function formatTime(ts?: number): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  })
}

function labelList(labels: string): string[] {
  return (labels ?? '').split(/[,\n]/).map((s) => s.trim()).filter(Boolean)
}

function kindLabel(kind?: string): string {
  return kind === 'FEAT' ? 'Feat' : 'Bug'
}

function isBound(row: GitIssueRow): boolean {
  return !!row.provider && !!row.repo && !!row.number
}

function remoteText(row: GitIssueRow): string {
  if (!isBound(row)) return '仅本地'
  return singleRepo.value ? `#${row.number}` : `${row.repo}#${row.number}`
}

async function load(silent = false): Promise<void> {
  const seq = ++loadSeq
  if (!silent) loading.value = true
  error.value = ''
  try {
    const rows = await listGitIssues({
      keyword: keyword.value.trim() || undefined,
      state: stateFilter.value || undefined,
    })
    if (seq !== loadSeq) return
    items.value = rows
    emit('count', rows.filter((r) => r.state === 'OPEN').length)
    if (!rows.some((r) => r.id === expandedId.value)) {
      expandedId.value = ''
    }
  } catch (e) {
    if (seq === loadSeq) error.value = e instanceof Error ? e.message : '加载任务失败'
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

watch(keyword, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => void load(), 260)
})
watch(stateFilter, () => void load())
watch(kindFilter, () => {
  if (expandedId.value && !visibleItems.value.some((i) => i.id === expandedId.value)) {
    expandedId.value = ''
  }
})

onMounted(() => {
  void load()
  void listProjects().then((rows) => { projects.value = rows }).catch(() => {})
})

// ---------------------------------------------------------------- 展开 / 评论

async function toggleExpand(row: GitIssueRow): Promise<void> {
  if (expandedId.value === row.id) {
    expandedId.value = ''
    return
  }
  expandedId.value = row.id
  commentText.value = ''
  await loadComments()
}

async function loadComments(): Promise<void> {
  if (!expandedId.value) {
    comments.value = []
    return
  }
  const id = expandedId.value
  commentsLoading.value = true
  try {
    const rows = await listGitIssueComments(id)
    if (expandedId.value === id) comments.value = rows
  } catch (e) {
    comments.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载评论失败')
  } finally {
    commentsLoading.value = false
  }
}

async function submitComment(): Promise<void> {
  const id = expandedId.value
  const text = commentText.value.trim()
  if (!id || !text) return
  commenting.value = true
  try {
    const comment = await addGitIssueComment(id, text)
    comments.value = [...comments.value, comment]
    commentText.value = ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评论失败')
  } finally {
    commenting.value = false
  }
}

async function removeComment(comment: GitIssueCommentRow): Promise<void> {
  const id = expandedId.value
  if (!id || comment.source !== 'PLATFORM') return
  try {
    await deleteGitIssueComment(id, comment.id)
    comments.value = comments.value.filter((c) => c.id !== comment.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除评论失败')
  }
}

// ---------------------------------------------------------------- 新建 / 编辑

const editorOpen = ref(false)
const editingId = ref('')
const saving = ref(false)
const form = reactive({ title: '', body: '', labels: '', kind: 'BUG' as 'BUG' | 'FEAT' })
const listRef = ref<HTMLElement | null>(null)

const editorTarget = computed(() => {
  const row = editingId.value
    ? items.value.find((i) => i.id === editingId.value)
    : undefined
  if (row && isBound(row)) {
    return { provider: row.provider, repo: row.repo }
  }
  return currentRepo.value
})

function openCreate(): void {
  editingId.value = ''
  Object.assign(form, { title: '', body: '', labels: '', kind: 'BUG' })
  editorOpen.value = true
  expandedId.value = ''
  scrollToEditor()
}

function openEdit(row: GitIssueRow): void {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title, body: row.body, labels: row.labels, kind: row.kind ?? 'BUG',
  })
  editorOpen.value = true
  scrollToEditor()
}

/** 编辑卡片在列表顶部，滚动到顶避免用户看不到刚打开的卡片。 */
function scrollToEditor(): void {
  void nextTick(() => listRef.value?.scrollTo({ top: 0, behavior: 'smooth' }))
}

function closeEditor(): void {
  editorOpen.value = false
  editingId.value = ''
}

async function submit(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写任务标题')
    return
  }
  saving.value = true
  try {
    const editingRow = editingId.value
      ? items.value.find((i) => i.id === editingId.value)
      : undefined
    const payload = {
      // 新建跟随当前项目；编辑保留原归属，避免把已绑定任务换到别的仓库
      projectId: editingRow ? editingRow.projectId : appStore.projectId,
      title: form.title.trim(),
      body: form.body,
      labels: form.labels.trim(),
      kind: form.kind,
      state: editingRow?.state ?? ('OPEN' as const),
    }
    const row = editingId.value
      ? await updateGitIssue(editingId.value, payload)
      : await createGitIssue(payload)
    if (row.syncStatus === 'FAILED') {
      ElMessage.warning(`任务已保存，但同步到 Git 失败：${row.syncError}`)
    } else {
      ElMessage.success(editingId.value ? '任务已更新' : '任务已创建')
    }
    closeEditor()
    await load(true)
    expandedId.value = row.id
    await loadComments()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存任务失败')
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------- 状态 / 同步 / 删除

function replaceRow(row: GitIssueRow): void {
  const index = items.value.findIndex((i) => i.id === row.id)
  if (index >= 0) items.value[index] = row
  emit('count', items.value.filter((i) => i.state === 'OPEN').length)
}

async function toggleState(row: GitIssueRow): Promise<void> {
  actionBusy.value = 'state:' + row.id
  try {
    const next = row.state === 'OPEN'
      ? await closeGitIssue(row.id)
      : await reopenGitIssue(row.id)
    replaceRow(next)
    if (next.syncStatus === 'FAILED') {
      ElMessage.warning(`本地状态已更新，同步 Git 失败：${next.syncError}`)
    } else {
      ElMessage.success(next.state === 'CLOSED' ? '任务已关闭' : '任务已重开')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新状态失败')
  } finally {
    actionBusy.value = ''
  }
}

async function resync(row: GitIssueRow): Promise<void> {
  actionBusy.value = 'sync:' + row.id
  try {
    const next = await syncGitIssue(row.id)
    replaceRow(next)
    if (next.syncStatus === 'FAILED') {
      ElMessage.error(`同步失败：${next.syncError}`)
    } else {
      ElMessage.success('已同步到 Git')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '同步失败')
  } finally {
    actionBusy.value = ''
  }
}

async function removeIssue(row: GitIssueRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      isBound(row)
        ? `删除任务「${row.title}」？仅解除平台绑定，不会删除 Git 上的 Issue。`
        : `删除任务「${row.title}」？`,
      '删除任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionBusy.value = 'delete:' + row.id
  try {
    await deleteGitIssue(row.id)
    items.value = items.value.filter((i) => i.id !== row.id)
    if (expandedId.value === row.id) expandedId.value = ''
    emit('count', items.value.filter((i) => i.state === 'OPEN').length)
    ElMessage.success('任务已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    actionBusy.value = ''
  }
}
</script>

<template>
  <div class="issues-view">
    <div class="issues-shell">
      <header class="issues-head">
        <div class="issues-head-main">
          <h1 class="issues-title">任务</h1>
          <p class="issues-desc">
            项目托管的 Git Issue 自动双向同步；Agent 可读取并操作同一批任务。
          </p>
        </div>
        <div class="issues-head-side">
          <span v-if="currentRepo.repo" class="repo-chip">
            <span class="repo-dot" />
            {{ providerLabel(currentRepo.provider) }}
            <span class="mono">{{ currentRepo.repo }}</span>
          </span>
          <span class="issues-stat">
            待处理 <b class="mono">{{ items.filter((i) => i.state === 'OPEN').length }}</b>
          </span>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon> 新建任务
          </el-button>
          <el-button size="small" :loading="loading" @click="load()">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </header>

      <div class="issues-panel">
        <div class="issues-toolbar">
          <el-input
            v-model="keyword" size="default" clearable class="search"
            placeholder="搜索标题 / 描述"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <div class="state-tabs">
            <button
              v-for="opt in stateOptions" :key="opt.value" type="button"
              class="state-tab" :class="{ active: stateFilter === opt.value }"
              @click="stateFilter = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
          <div class="state-tabs kind-tabs">
            <button
              v-for="opt in kindOptions" :key="opt.value" type="button"
              class="state-tab"
              :class="[
                opt.value ? opt.value.toLowerCase() : '',
                { active: kindFilter === opt.value },
              ]"
              @click="kindFilter = opt.value"
            >
              {{ opt.label }}
            </button>
          </div>
          <span class="toolbar-count mono">{{ visibleItems.length }} 个任务</span>
        </div>

        <div ref="listRef" class="issues-list">
          <!-- 新建 / 编辑：页内卡片，不遮挡列表 -->
          <section v-if="editorOpen" class="editor-card">
            <div class="editor-head">
              <h2 class="editor-title">{{ editingId ? '编辑任务' : '新建任务' }}</h2>
              <p class="editor-desc">
                {{ editingId
                  ? '修改后会同步到已绑定的 Git Issue；Agent 也能读取并操作这条任务。'
                  : '保存后自动同步到项目托管的 Git 仓库；Agent 也能读取并操作这条任务。' }}
              </p>
            </div>
            <div class="editor-body">
              <div class="editor-field">
                <span class="editor-label">标题</span>
                <el-input
                  v-model="form.title" maxlength="200" show-word-limit
                  placeholder="一句话描述问题"
                />
              </div>
              <div class="editor-field">
                <span class="editor-label">描述</span>
                <el-input
                  v-model="form.body" type="textarea" :rows="4" resize="none"
                  placeholder="补充背景、复现步骤或期望结果（支持 Markdown）"
                />
              </div>
              <div class="editor-row">
                <div class="editor-field kind-field">
                  <span class="editor-label">类型</span>
                  <div class="kind-picker">
                    <button
                      type="button" class="kind-option bug"
                      :class="{ active: form.kind === 'BUG' }" @click="form.kind = 'BUG'"
                    >
                      Bug
                    </button>
                    <button
                      type="button" class="kind-option feat"
                      :class="{ active: form.kind === 'FEAT' }" @click="form.kind = 'FEAT'"
                    >
                      Feat
                    </button>
                  </div>
                </div>
                <div class="editor-field grow">
                  <span class="editor-label">标签</span>
                  <el-input v-model="form.labels" placeholder="bug, 待排查（逗号分隔）" />
                </div>
                <div class="editor-target">
                  <span class="target-icon"><el-icon><Link /></el-icon></span>
                  <span v-if="editorTarget.repo" class="target-text">
                    同步到 {{ providerLabel(editorTarget.provider) }}
                    <span class="mono target-repo">{{ editorTarget.repo }}</span>
                  </span>
                  <span v-else class="target-text muted">
                    项目未关联 Git 仓库，任务仅保存在平台
                  </span>
                </div>
              </div>
            </div>
            <div class="editor-foot">
              <el-button size="small" @click="closeEditor">取消</el-button>
              <el-button size="small" type="primary" :loading="saving" @click="submit">
                {{ editingId ? '保存' : '创建任务' }}
              </el-button>
            </div>
          </section>

          <div v-if="loading && !items.length" class="list-empty">加载中…</div>
          <div v-else-if="error" class="list-empty error">{{ error }}</div>
          <div v-else-if="!visibleItems.length" class="list-empty">
            {{ items.length
              ? '当前分类下没有任务，切换上面的分类看看。'
              : '还没有任务。新建一个，或在 Git 上创建 Issue 自动同步过来。' }}
          </div>

          <article
            v-for="row in visibleItems" :key="row.id" class="task-row"
            :class="{ open: expandedId === row.id, closed: row.state === 'CLOSED' }"
          >
            <button type="button" class="task-main" @click="toggleExpand(row)">
              <span class="state-dot" :class="row.state.toLowerCase()" />
              <span class="task-text">
                <span class="kind-chip" :class="row.kind === 'FEAT' ? 'feat' : 'bug'">
                  {{ kindLabel(row.kind) }}
                </span>
                <span class="task-title">{{ row.title }}</span>
              </span>
              <span class="task-labels">
                <span v-for="label in labelList(row.labels).slice(0, 2)" :key="label" class="mini-chip">
                  {{ label }}
                </span>
              </span>
              <span class="task-remote mono" :class="{ local: !isBound(row) }">
                {{ remoteText(row) }}
              </span>
              <span
                v-if="isBound(row) || row.syncStatus === 'FAILED'"
                class="sync-pill" :class="row.syncStatus.toLowerCase()"
              >
                {{ syncLabel(row) }}
              </span>
              <span v-else class="sync-pill spacer" aria-hidden="true" />
              <span class="task-time mono">{{ formatTime(row.updatedAt) }}</span>
              <el-icon class="chevron"><ArrowRight /></el-icon>
            </button>

            <div v-if="expandedId === row.id" class="task-detail">
              <div v-if="row.syncError" class="sync-error">{{ row.syncError }}</div>

              <div class="detail-block">
                <div class="block-head">
                  <span class="block-title">描述</span>
                  <a
                    v-if="isBound(row)" class="block-link mono"
                    :href="row.url" target="_blank" rel="noreferrer"
                  >
                    <el-icon><Link /></el-icon> {{ row.repo }}#{{ row.number }}
                  </a>
                  <span class="block-sub mono">{{ row.id }}</span>
                </div>
                <div v-if="!row.body" class="block-empty">（无描述）</div>
                <div v-else class="markdown-body">
                  <MarkdownView :content="row.body" />
                </div>
              </div>

              <div class="task-actions">
                <el-button size="small" @click="openEdit(row)">
                  <el-icon><Edit /></el-icon> 编辑
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'state:' + row.id"
                  @click="toggleState(row)"
                >
                  <el-icon><component :is="row.state === 'OPEN' ? Close : RefreshRight" /></el-icon>
                  {{ row.state === 'OPEN' ? '关闭' : '重开' }}
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'sync:' + row.id"
                  :disabled="!isBound(row)" @click="resync(row)"
                >
                  <el-icon><Refresh /></el-icon> 同步
                </el-button>
                <el-button
                  size="small" type="danger" plain
                  :loading="actionBusy === 'delete:' + row.id" @click="removeIssue(row)"
                >
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </div>

              <div class="comment-block">
                <div class="block-head">
                  <span class="block-title">评论</span>
                  <span class="block-sub">
                    {{ comments.length }} 条 · 平台与 Git 双向同步
                  </span>
                </div>

                <div v-if="commentsLoading" class="block-empty">加载评论…</div>
                <div v-else-if="!comments.length" class="block-empty">还没有评论</div>
                <div v-else class="comment-list">
                  <div v-for="c in comments" :key="c.id" class="comment-item">
                    <div class="comment-head">
                      <span class="comment-author">
                        <el-icon><ChatDotRound /></el-icon>
                        {{ c.author }}
                      </span>
                      <span class="comment-source" :class="c.source.toLowerCase()">
                        {{ c.source === 'GIT' ? 'Git' : c.source === 'AGENT' ? 'Agent' : '平台' }}
                      </span>
                      <span class="comment-time mono">{{ formatTime(c.createdAt) }}</span>
                      <button
                        v-if="c.source === 'PLATFORM'" type="button" class="comment-del"
                        @click="removeComment(c)"
                      >
                        删除
                      </button>
                    </div>
                    <div class="comment-body">{{ c.body }}</div>
                  </div>
                </div>

                <div class="comment-compose">
                  <el-input
                    v-model="commentText" type="textarea" :rows="2" resize="none"
                    placeholder="写评论；已绑定 Git 的任务会同步为远端评论"
                  />
                  <div class="compose-foot">
                    <span class="compose-hint">Agent 也可以直接调用工具评论 / 关闭任务</span>
                    <el-button
                      size="small" type="primary" :loading="commenting"
                      :disabled="!commentText.trim()" @click="submitComment"
                    >
                      发表评论
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
          </article>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.issues-view {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  padding: 22px 26px 26px;
}
.issues-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  max-width: 1080px;
  min-height: 0;
  margin: 0 auto;
}

/* 页头 */
.issues-head {
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line-soft);
}
.issues-head-main {
  flex: 1;
  min-width: 0;
}
.issues-title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.015em;
  color: var(--ink);
}
.issues-desc {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.issues-head-side {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}
.repo-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 4px 10px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.repo-chip .mono {
  color: var(--ink);
}
.repo-dot {
  width: 6px;
  height: 6px;
  background: var(--accent);
  border-radius: 50%;
}
.issues-stat {
  padding: 4px 10px;
  font-size: 11px;
  color: var(--ink-muted);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.issues-stat b {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}

/* 列表容器 */
.issues-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}
.issues-toolbar {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  background: var(--sidebar-background-color);
  border-bottom: 1px solid var(--line-soft);
}
.search {
  max-width: 320px;
}
.state-tabs {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.state-tab {
  padding: 5px 12px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
  transition: background-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}
.state-tab:hover {
  color: var(--ink);
}
.state-tab.active {
  font-weight: 600;
  color: var(--ink);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.kind-tabs .state-tab.bug.active {
  color: #b54a4a;
}
.kind-tabs .state-tab.feat.active {
  color: var(--accent-ink, #4a6cf7);
}
.toolbar-count {
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-faint);
}
.issues-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
}
.list-empty {
  padding: 28px 20px;
  font-size: 11.5px;
  color: var(--ink-faint);
  text-align: center;
}
.list-empty.error {
  color: var(--danger, #c45656);
}

/* 任务行 */
.task-row {
  border-bottom: 1px solid var(--line-soft);
}
.task-row:last-child {
  border-bottom: 0;
}
.task-main {
  display: grid;
  grid-template-columns: 8px minmax(0, 1fr) auto auto auto auto 14px;
  gap: 12px;
  align-items: center;
  width: 100%;
  padding: 12px 16px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  transition: background-color var(--dur-fast) var(--ease-out);
}
.task-main:hover {
  background: var(--surface-hover);
}
.task-row.open .task-main {
  background: var(--surface-sunken);
}
.state-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.state-dot.open {
  background: var(--warn, #d19a00);
}
.state-dot.closed {
  background: var(--ok);
}
.task-title {
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-text {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.kind-chip {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  font-weight: 600;
  line-height: 16px;
  letter-spacing: 0.02em;
  border-radius: 4px;
}
.kind-chip.bug {
  color: #b54a4a;
  background: rgb(181 74 74 / 10%);
}
.kind-chip.feat {
  color: var(--accent-ink, #4a6cf7);
  background: rgb(74 108 247 / 12%);
}
.task-row.closed .task-title {
  color: var(--ink-muted);
}
.task-labels {
  display: flex;
  gap: 4px;
  align-items: center;
}
.mini-chip {
  padding: 0 6px;
  font-size: 9px;
  line-height: 16px;
  color: var(--ink-muted);
  white-space: nowrap;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.task-remote {
  font-size: 10.5px;
  color: var(--ink-muted);
  white-space: nowrap;
}
.task-remote.local {
  color: var(--ink-faint);
}
.sync-pill {
  padding: 0 6px;
  font-size: 9px;
  line-height: 16px;
  white-space: nowrap;
  border-radius: 4px;
}
.sync-pill.synced {
  color: var(--ok-ink, #2f7d4f);
  background: var(--ok-soft, #e8f5ec);
}
.sync-pill.failed {
  color: var(--danger, #c45656);
  background: var(--danger-soft, #fdecec);
}
.sync-pill.local,
.sync-pill.pending {
  color: var(--ink-faint);
  background: var(--surface-sunken);
}
.sync-pill.spacer {
  visibility: hidden;
}
.task-time {
  font-size: 10px;
  color: var(--ink-faint);
  white-space: nowrap;
}
.chevron {
  font-size: 11px;
  color: var(--ink-faint);
  transition: transform var(--dur-fast) var(--ease-out);
}
.task-row.open .chevron {
  color: var(--accent-ink);
  transform: rotate(90deg);
}

/* 展开详情 */
.task-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 20px 18px 36px;
}
.sync-error {
  font-size: 11px;
  line-height: 1.6;
  color: var(--danger, #c45656);
  overflow-wrap: anywhere;
}
.detail-block,
.comment-block {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 9px;
}
.block-head {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: baseline;
  padding: 9px 13px;
  background: var(--code-bg);
  border-bottom: 1px solid var(--line-soft);
}
.block-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.block-sub {
  overflow: hidden;
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.block-link {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 10.5px;
  color: var(--accent-ink);
  text-decoration: none;
}
.block-empty {
  padding: 14px 13px;
  font-size: 11px;
  color: var(--ink-faint);
}
.markdown-body {
  padding: 12px 14px;
}
.markdown-body :deep(.markdown-view) {
  font-size: 12.5px;
  line-height: 1.75;
}
.task-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.comment-list {
  display: flex;
  flex-direction: column;
}
.comment-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 13px;
}
.comment-item + .comment-item {
  border-top: 1px solid var(--line-soft);
}
.comment-head {
  display: flex;
  gap: 8px;
  align-items: center;
}
.comment-author {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink);
}
.comment-source {
  padding: 0 5px;
  font-size: 9px;
  line-height: 15px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 4px;
}
.comment-source.git {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.comment-time {
  flex: 1;
  font-size: 10px;
  color: var(--ink-faint);
}
.comment-del {
  padding: 1px 6px;
  font-size: 10px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.comment-del:hover {
  color: var(--danger, #c45656);
  background: var(--surface-hover);
}
.comment-body {
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.comment-compose {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 11px 13px;
  border-top: 1px solid var(--line-soft);
}
.compose-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}
.compose-hint {
  font-size: 10.5px;
  color: var(--ink-faint);
}

/* 页内编辑卡片 */
.editor-card {
  margin: 12px;
  background: var(--surface-raised);
  border: 1px solid var(--accent-line, var(--line));
  border-radius: 10px;
  box-shadow: var(--shadow-1);
}
.editor-head {
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--line-soft);
}
.editor-title {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ink);
}
.editor-desc {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.editor-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px 16px;
}
.editor-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.kind-field {
  flex: none;
}
.kind-picker {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.kind-option {
  padding: 4px 12px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.kind-option.bug.active {
  font-weight: 600;
  color: #b54a4a;
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.kind-option.feat.active {
  font-weight: 600;
  color: var(--accent-ink, #4a6cf7);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.editor-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
}
.editor-row {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}
.grow {
  flex: 1;
  min-width: 0;
}
.editor-target {
  display: flex;
  flex: none;
  gap: 7px;
  align-items: center;
  max-width: 300px;
  padding: 8px 11px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 8px;
}
.target-icon {
  display: inline-flex;
  flex: none;
  color: var(--ink-faint);
}
.target-text {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  min-width: 0;
}
.target-text.muted {
  color: var(--ink-faint);
}
.target-repo {
  padding: 1px 5px;
  font-size: 10px;
  color: var(--ink);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 4px;
}
.editor-foot {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  padding: 10px 16px;
  background: var(--surface-sunken);
  border-top: 1px solid var(--line-soft);
  border-radius: 0 0 10px 10px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

@media (max-width: 860px) {
  .issues-view {
    padding: 16px;
  }
  .task-main {
    grid-template-columns: 8px minmax(0, 1fr) auto 14px;
  }
  .task-labels,
  .sync-pill,
  .task-time {
    display: none;
  }
  .task-remote {
    font-size: 10px;
  }
  .issues-head {
    flex-direction: column;
  }
  .issues-head-side {
    justify-content: flex-start;
  }
  .editor-row {
    flex-direction: column;
    align-items: stretch;
  }
  .editor-target {
    max-width: none;
  }
  .task-detail {
    padding: 4px 14px 16px 26px;
  }
}
</style>
