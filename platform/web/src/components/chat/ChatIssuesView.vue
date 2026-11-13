<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, Close, Delete, Edit, Link, Plus, Refresh, RefreshRight, Search,
} from '@element-plus/icons-vue'
import {
  addGitIssueComment, closeGitIssue, createGitIssue, deleteGitIssue, deleteGitIssueComment,
  listGitIssueComments, listGitIssues, reopenGitIssue, syncGitIssue, updateGitIssue,
  type GitIssueCommentRow, type GitIssueProvider, type GitIssueRow,
} from '../../api/issues'
import MarkdownView from './MarkdownView.vue'
import { appStore } from '../../stores/app'

/**
 * 对话页「任务」Tab：Gitee / GitHub / GitLab 双向同步的 Issue 工单。
 *
 * 左列筛选 + 任务列表，右侧详情 / 编辑 / 评论。数据来自 /api/v1/git-issues，
 * 与 Agent 工具（list_git_issues / create_git_issue / comment_git_issue ...）完全同源。
 */
const emit = defineEmits<{ (e: 'count', value: number): void }>()

const items = ref<GitIssueRow[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const stateFilter = ref('')
const providerFilter = ref('')
const activeId = ref('')
const active = computed(() => items.value.find((i) => i.id === activeId.value) ?? null)

const comments = ref<GitIssueCommentRow[]>([])
const commentsLoading = ref(false)
const commentText = ref('')
const commenting = ref(false)
const actionBusy = ref('')

let loadSeq = 0
let keywordTimer: ReturnType<typeof setTimeout> | undefined

const PROVIDER_LABEL: Record<string, string> = {
  GITEE: 'Gitee', GITHUB: 'GitHub', GITLAB: 'GitLab',
}

function providerLabel(provider: string): string {
  return PROVIDER_LABEL[provider] ?? (provider || '本地')
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

async function load(silent = false): Promise<void> {
  const seq = ++loadSeq
  if (!silent) loading.value = true
  error.value = ''
  try {
    const rows = await listGitIssues({
      keyword: keyword.value.trim() || undefined,
      state: stateFilter.value || undefined,
      provider: providerFilter.value || undefined,
    })
    if (seq !== loadSeq) return
    items.value = rows
    emit('count', rows.filter((r) => r.state === 'OPEN').length)
    if (!rows.some((r) => r.id === activeId.value)) {
      activeId.value = rows[0]?.id ?? ''
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
watch([stateFilter, providerFilter], () => void load())

async function loadComments(): Promise<void> {
  if (!activeId.value) {
    comments.value = []
    return
  }
  commentsLoading.value = true
  try {
    comments.value = await listGitIssueComments(activeId.value)
  } catch (e) {
    comments.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载评论失败')
  } finally {
    commentsLoading.value = false
  }
}

watch(activeId, () => {
  commentText.value = ''
  void loadComments()
})

onMounted(() => void load())

// ---------------------------------------------------------------- 新建 / 编辑

const dialogOpen = ref(false)
const editingId = ref('')
const saving = ref(false)
const form = reactive<{
  title: string
  body: string
  provider: GitIssueProvider
  repo: string
  labels: string
}>({
  title: '', body: '', provider: '', repo: '', labels: '',
})

function openCreate(): void {
  editingId.value = ''
  Object.assign(form, { title: '', body: '', provider: '', repo: '', labels: '' })
  dialogOpen.value = true
}

function openEdit(): void {
  const row = active.value
  if (!row) return
  editingId.value = row.id
  Object.assign(form, {
    title: row.title,
    body: row.body,
    provider: row.provider,
    repo: row.repo,
    labels: row.labels,
  })
  dialogOpen.value = true
}

async function submit(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写任务标题')
    return
  }
  if (form.provider && !form.repo.trim()) {
    ElMessage.warning('选择 Git 平台后需要填写仓库（owner/repo）')
    return
  }
  saving.value = true
  try {
    const editingRow = editingId.value
      ? items.value.find((i) => i.id === editingId.value)
      : undefined
    const payload = {
      // 关联当前项目：Git 评论里的指令才能唤醒项目上下文里的 Agent
      projectId: editingRow?.projectId || appStore.projectId,
      title: form.title.trim(),
      body: form.body,
      provider: form.provider,
      repo: form.provider ? form.repo.trim() : '',
      labels: form.labels.trim(),
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
    dialogOpen.value = false
    await load(true)
    activeId.value = row.id
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

async function toggleState(): Promise<void> {
  const row = active.value
  if (!row) return
  actionBusy.value = 'state'
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

async function resync(): Promise<void> {
  const row = active.value
  if (!row) return
  actionBusy.value = 'sync'
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

async function removeIssue(): Promise<void> {
  const row = active.value
  if (!row) return
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
  actionBusy.value = 'delete'
  try {
    await deleteGitIssue(row.id)
    items.value = items.value.filter((i) => i.id !== row.id)
    activeId.value = items.value[0]?.id ?? ''
    emit('count', items.value.filter((i) => i.state === 'OPEN').length)
    ElMessage.success('任务已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    actionBusy.value = ''
  }
}

// ---------------------------------------------------------------- 评论

async function submitComment(): Promise<void> {
  const row = active.value
  const text = commentText.value.trim()
  if (!row || !text) return
  commenting.value = true
  try {
    const comment = await addGitIssueComment(row.id, text)
    comments.value = [...comments.value, comment]
    commentText.value = ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评论失败')
  } finally {
    commenting.value = false
  }
}

async function removeComment(comment: GitIssueCommentRow): Promise<void> {
  const row = active.value
  if (!row || comment.source !== 'PLATFORM') return
  try {
    await deleteGitIssueComment(row.id, comment.id)
    comments.value = comments.value.filter((c) => c.id !== comment.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除评论失败')
  }
}

function isBound(row: GitIssueRow): boolean {
  return !!row.provider && !!row.repo && !!row.number
}
</script>

<template>
  <div class="issues-view">
    <div class="issues-shell">
      <header class="issues-head">
        <div class="issues-head-main">
          <h1 class="issues-title">任务</h1>
          <p class="issues-desc">
            Gitee / GitHub / GitLab 双向同步的 Issue 工单；Agent 可读取并操作同一批任务。
          </p>
        </div>
        <div class="issues-head-side">
          <span class="issues-stat">
            <span class="stat-dot" />
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

      <div class="issues-body">
        <aside class="issue-list">
          <div class="issue-filters">
            <el-input v-model="keyword" size="default" clearable placeholder="搜索标题 / 描述 / 仓库">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <div class="filter-row">
              <el-select v-model="stateFilter" size="small" placeholder="状态">
                <el-option label="全部状态" value="" />
                <el-option label="待处理" value="OPEN" />
                <el-option label="已完成" value="CLOSED" />
              </el-select>
              <el-select v-model="providerFilter" size="small" placeholder="平台">
                <el-option label="全部平台" value="" />
                <el-option label="Gitee" value="GITEE" />
                <el-option label="GitHub" value="GITHUB" />
                <el-option label="GitLab" value="GITLAB" />
              </el-select>
            </div>
          </div>

          <div class="issue-scroll">
            <div class="list-head">
              <span class="list-count mono">{{ items.length }} 个任务</span>
            </div>
            <div v-if="loading && !items.length" class="group-empty">加载中…</div>
            <div v-else-if="error" class="group-empty error">{{ error }}</div>
            <div v-else-if="!items.length" class="group-empty">
              还没有任务。新建任务，或在 Git 上创建 Issue 自动同步过来。
            </div>

            <button
              v-for="row in items" :key="row.id"
              type="button" class="issue-item"
              :class="{ active: activeId === row.id, closed: row.state === 'CLOSED' }"
              @click="activeId = row.id"
            >
              <span class="state-dot" :class="row.state.toLowerCase()" />
              <span class="item-main">
                <span class="item-title">{{ row.title }}</span>
                <span class="item-meta">
                  <span class="item-provider">{{ providerLabel(row.provider) }}</span>
                  <span v-if="isBound(row)" class="mono item-remote">
                    {{ row.repo }}#{{ row.number }}
                  </span>
                  <span v-else class="item-remote">仅本地</span>
                </span>
                <span class="item-foot">
                  <span class="sync-pill" :class="row.syncStatus.toLowerCase()">
                    {{ syncLabel(row) }}
                  </span>
                  <span v-for="label in labelList(row.labels)" :key="label" class="mini-chip">
                    {{ label }}
                  </span>
                </span>
              </span>
            </button>
          </div>
        </aside>

        <section class="issue-detail">
          <template v-if="active">
            <div class="detail-hero">
              <div class="hero-main">
                <div class="hero-eyebrow">
                  <span class="state-pill" :class="active.state.toLowerCase()">
                    {{ active.state === 'OPEN' ? '待处理' : '已完成' }}
                  </span>
                  <span class="provider-pill">{{ providerLabel(active.provider) }}</span>
                  <a
                    v-if="isBound(active)" class="hero-link mono"
                    :href="active.url" target="_blank" rel="noreferrer"
                  >
                    <el-icon><Link /></el-icon> {{ active.repo }}#{{ active.number }}
                  </a>
                  <span v-else class="hero-link muted">未绑定远端仓库</span>
                </div>
                <h2 class="hero-title">{{ active.title }}</h2>
                <div class="hero-chips">
                  <span class="chip" :class="active.syncStatus.toLowerCase()">
                    {{ syncLabel(active) }}
                  </span>
                  <span class="chip">更新 {{ formatTime(active.updatedAt) }}</span>
                  <span v-for="label in labelList(active.labels)" :key="label" class="chip on">
                    {{ label }}
                  </span>
                </div>
                <p v-if="active.syncError" class="sync-error">{{ active.syncError }}</p>
              </div>
              <div class="hero-actions">
                <el-button size="small" @click="openEdit">
                  <el-icon><Edit /></el-icon> 编辑
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'state'" @click="toggleState"
                >
                  <el-icon><component :is="active.state === 'OPEN' ? Close : RefreshRight" /></el-icon>
                  {{ active.state === 'OPEN' ? '关闭' : '重开' }}
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'sync'"
                  :disabled="!active.provider || !active.repo" @click="resync"
                >
                  <el-icon><Refresh /></el-icon> 同步
                </el-button>
                <el-button
                  size="small" type="danger" plain :loading="actionBusy === 'delete'"
                  @click="removeIssue"
                >
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </div>
            </div>

            <div class="detail-block">
              <div class="block-head">
                <span class="block-title">任务描述</span>
                <span class="block-sub mono">{{ active.id }}</span>
              </div>
              <div v-if="!active.body" class="block-empty">（无描述）</div>
              <div v-else class="markdown-body">
                <MarkdownView :content="active.body" />
              </div>
            </div>

            <div class="comment-block">
              <div class="block-head">
                <span class="block-title">评论</span>
                <span class="block-sub">{{ comments.length }} 条 · 平台与 Git 双向同步</span>
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
                  v-model="commentText" type="textarea" :rows="3" resize="none"
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
          </template>

          <div v-else class="detail-empty">
            <span class="empty-icon"><el-icon><ChatDotRound /></el-icon></span>
            <span>{{ loading ? '正在加载任务…' : '从左侧选择一个任务' }}</span>
          </div>
        </section>
      </div>
    </div>

    <el-dialog
      v-model="dialogOpen" :title="editingId ? '编辑任务' : '新建任务'"
      width="560px" destroy-on-close
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="一句话描述问题" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.body" type="textarea" :rows="5" resize="none"
            placeholder="补充背景、复现步骤或期望结果（支持 Markdown）"
          />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="同步到 Git">
            <el-select v-model="form.provider" placeholder="仅建本地任务">
              <el-option label="仅本地（不同步）" value="" />
              <el-option label="Gitee" value="GITEE" />
              <el-option label="GitHub" value="GITHUB" />
              <el-option label="GitLab" value="GITLAB" />
            </el-select>
          </el-form-item>
          <el-form-item label="仓库">
            <el-input
              v-model="form.repo" :disabled="!form.provider" placeholder="owner/repo"
            />
          </el-form-item>
        </div>
        <el-form-item label="标签">
          <el-input v-model="form.labels" placeholder="bug, 待排查（逗号分隔）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editingId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
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
  gap: 16px;
  width: 100%;
  max-width: 1160px;
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
  gap: 10px;
  align-items: center;
}
.issues-stat {
  display: flex;
  gap: 6px;
  align-items: center;
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
.stat-dot {
  width: 6px;
  height: 6px;
  background: var(--warn, #d19a00);
  border-radius: 50%;
}

/* 左右两栏 */
.issues-body {
  display: grid;
  flex: 1;
  grid-template-columns: 320px minmax(0, 1fr);
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}
.issue-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--sidebar-background-color);
  border-right: 1px solid var(--line-soft);
}
.issue-filters {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 11px 10px;
  border-bottom: 1px solid var(--line-soft);
}
.filter-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.issue-scroll {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  padding: 10px 8px 12px;
  overflow-y: auto;
}
.list-head {
  display: flex;
  align-items: center;
  padding: 0 6px 8px;
}
.list-count {
  font-size: 10px;
  color: var(--ink-faint);
}
.group-empty {
  padding: 8px 7px;
  font-size: 11px;
  color: var(--ink-faint);
}
.group-empty.error {
  color: var(--danger, #c45656);
}
.issue-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  width: 100%;
  padding: 9px 9px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 1px solid transparent;
  border-radius: 8px;
  transition: background-color var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out);
}
.issue-item:hover {
  background: var(--surface-raised);
}
.issue-item.active {
  background: var(--surface-raised);
  border-color: var(--line);
  box-shadow: var(--shadow-1), inset 2px 0 var(--accent);
}
.issue-item.closed .item-title {
  color: var(--ink-muted);
  text-decoration: line-through;
  text-decoration-color: var(--line-strong);
}
.state-dot {
  flex: none;
  width: 7px;
  height: 7px;
  margin-top: 5px;
  border-radius: 50%;
}
.state-dot.open {
  background: var(--warn, #d19a00);
}
.state-dot.closed {
  background: var(--ok);
}
.item-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.item-title {
  display: -webkit-box;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.issue-item.active .item-title {
  font-weight: 600;
}
.item-meta {
  display: flex;
  gap: 5px;
  align-items: center;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.item-provider {
  flex: none;
  padding: 0 5px;
  font-size: 9.5px;
  line-height: 15px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 4px;
}
.item-remote {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.item-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.sync-pill {
  padding: 0 5px;
  font-size: 9px;
  line-height: 15px;
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
.mini-chip {
  padding: 0 5px;
  font-size: 9px;
  line-height: 15px;
  color: var(--ink-muted);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}

/* 右栏 */
.issue-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
  padding: 22px 24px;
  overflow-y: auto;
}
.detail-hero {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line-soft);
}
.hero-main {
  flex: 1;
  min-width: 0;
}
.hero-eyebrow {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.state-pill {
  padding: 1px 8px;
  font-size: 10px;
  font-weight: 600;
  border-radius: var(--radius-pill);
}
.state-pill.open {
  color: var(--warn-ink, #8a6200);
  background: var(--warn-soft, #fff4d6);
}
.state-pill.closed {
  color: var(--ok-ink, #2f7d4f);
  background: var(--ok-soft, #e8f5ec);
}
.provider-pill {
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--ink-muted);
  text-transform: uppercase;
}
.hero-link {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--accent-ink);
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hero-link.muted {
  color: var(--ink-faint);
}
.hero-title {
  margin: 6px 0 0;
  font-size: 18px;
  font-weight: 650;
  line-height: 1.4;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.hero-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}
.chip {
  padding: 2px 9px;
  font-size: 10.5px;
  color: var(--ink-muted);
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
}
.chip.on {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: transparent;
}
.chip.synced {
  color: var(--ok-ink, #2f7d4f);
  background: var(--ok-soft, #e8f5ec);
  border-color: transparent;
}
.chip.failed {
  color: var(--danger, #c45656);
  background: var(--danger-soft, #fdecec);
  border-color: transparent;
}
.sync-error {
  margin: 9px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--danger, #c45656);
  overflow-wrap: anywhere;
}
.hero-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
  max-width: 250px;
}
.detail-block,
.comment-block {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}
.block-head {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: baseline;
  padding: 10px 14px;
  background: var(--code-bg);
  border-bottom: 1px solid var(--line-soft);
}
.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.block-sub {
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.block-empty {
  padding: 16px 13px;
  font-size: 11px;
  color: var(--ink-faint);
}
.markdown-body {
  padding: 14px 16px;
}
.markdown-body :deep(.markdown-view) {
  font-size: 12.5px;
  line-height: 1.75;
}
.comment-list {
  display: flex;
  flex-direction: column;
}
.comment-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 11px 14px;
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
  font-size: 11.5px;
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
  font-size: 12px;
  line-height: 1.65;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.comment-compose {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
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
.detail-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--ink-faint);
}
.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  font-size: 19px;
  color: var(--ink-muted);
  background: var(--code-bg);
  border: 1px solid var(--line-soft);
  border-radius: 13px;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

@media (max-width: 900px) {
  .issues-body {
    grid-template-columns: 1fr;
    overflow-y: auto;
  }
  .issue-list {
    max-height: 300px;
    border-right: 0;
    border-bottom: 1px solid var(--line-soft);
  }
  .issue-detail {
    overflow: visible;
  }
  .detail-hero {
    flex-direction: column;
  }
  .hero-actions {
    max-width: none;
    justify-content: flex-start;
  }
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
