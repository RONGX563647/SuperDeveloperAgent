<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CaretRight, RefreshRight } from '@element-plus/icons-vue'
import PageHeader from '../layouts/components/PageHeader.vue'
import { gitStatus, gitSync, removeWorktree, type GitStatus, type WorktreeRow } from '../api/project'
import { appStore } from '../stores/app'
import { notifyError } from '../utils/feedback'

/**
 * 项目详情：git 状态卡 / worktree 列表 / 操作区。
 * 边界承诺显性化：平台只读用户目录，绝不 push（UI 上"平台只读"标 + origin 不可操作）。
 */
const route = useRoute()
const router = useRouter()
const projectId = String(route.params.id ?? '')

const status = ref<GitStatus | null>(null)
const loading = ref(false)
const syncing = ref(false)

/** 主远程地址（优先 origin，否则第一个）。 */
const primaryRemoteUrl = computed(() => {
  const remotes = status.value?.remotes ?? []
  const origin = remotes.find(r => r.name === 'origin')
  return (origin ?? remotes[0])?.url ?? ''
})

const ROLE_LABEL: Record<string, string> = {
  BUILD: '构建',
  VERIFY: '常驻（自检/检索）',
  USER: '你的本地目录 · 平台只读'
}

async function load() {
  loading.value = true
  try {
    status.value = await gitStatus(projectId)
  } catch (e) {
    notifyError(e, '加载失败')
  } finally {
    loading.value = false
  }
}

async function doSync() {
  syncing.value = true
  try {
    await gitSync(projectId)
    ElMessage.success('已同步（fetch）')
    await load()
  } catch (e) {
    notifyError(e, '同步失败')
  } finally {
    syncing.value = false
  }
}

async function cleanup(wt: WorktreeRow) {
  try {
    await removeWorktree(projectId, wt.name)
    ElMessage.success(`已清理 ${wt.name}`)
    await load()
  } catch (e) {
    notifyError(e, '清理失败')
  }
}

function goVerify() {
  appStore.projectId = projectId
  void router.push('/pipeline')
}

onMounted(load)
</script>

<template>
  <div class="detail-page">
    <PageHeader title="项目详情" :subtitle="projectId">
      <template #actions>
        <button type="button" class="hdr-btn" @click="load">刷新</button>
      </template>
    </PageHeader>

    <div class="detail-body">
      <!-- git 状态卡 -->
      <section class="card">
        <h2 class="card-title">git 状态</h2>

        <!-- 未接入：显示引导而不是报错 -->
        <div v-if="status && !status.initialized" class="not-initialized">
          <p class="ni-title">该项目尚未完成 git 接入</p>
          <p class="ni-sub">平台还没有它的 bare 仓库与工作树，无法显示 HEAD / 分支 / 同步状态。</p>
          <button type="button" class="primary-btn" @click="router.push('/projects/intake')">
            前往项目接入
            <el-icon><CaretRight /></el-icon>
          </button>
        </div>

        <div v-else-if="status" class="status-lines">
          <div class="s-line">
            <span class="s-label">HEAD</span>
            <span class="mono">{{ status.head.slice(0, 12) }}</span>
          </div>
          <div class="s-line">
            <span class="s-label">分支</span>
            <span class="mono">
              {{ status.branch || '—' }}
              <span v-if="status.upstream" class="upstream">→ {{ status.upstream }}</span>
            </span>
          </div>
          <!-- 远程：修复"检测不到远程" -->
          <div class="s-line">
            <span class="s-label">远程</span>
            <span v-if="status.remotes?.length" class="remotes">
              <span v-for="r in status.remotes" :key="r.name" class="remote-chip mono" :title="r.url">
                {{ r.name }}
              </span>
            </span>
            <span v-else class="warn-text">未配置远程（纯本地仓库）</span>
          </div>
          <div class="s-line">
            <span class="s-label">远程地址</span>
            <span class="mono url">{{ primaryRemoteUrl || '—' }}</span>
          </div>
          <!-- 领先/落后 -->
          <div class="s-line">
            <span class="s-label">与远端</span>
            <span v-if="status.upstream" class="ab">
              <span class="ab-item ahead">↑ {{ status.ahead ?? 0 }} 待推送</span>
              <span class="ab-item behind">↓ {{ status.behind ?? 0 }} 待拉取</span>
              <span v-if="!status.ahead && !status.behind" class="ab-item synced">已同步</span>
            </span>
            <span v-else class="muted">无 upstream（本地分支未跟踪远端）</span>
          </div>
          <div class="s-line">
            <span class="s-label">未提交</span>
            <span>{{ status.uncommitted.length }} 个文件变动</span>
          </div>
          <div class="s-line">
            <span class="s-label">存储模式</span>
            <span class="mono">{{ status.storageMode === 'DIRECT' ? '就地（仓库在你的目录）' : '平台托管（bare）' }}</span>
          </div>
          <div class="s-line">
            <span class="s-label">同步</span>
            <button
              type="button"
              class="mini-btn"
              :disabled="syncing"
              @click="doSync"
            >
              <el-icon><RefreshRight /></el-icon>
              {{ syncing ? '同步中…' : '从远端 fetch' }}
            </button>
          </div>
        </div>
        <p v-else-if="loading" class="hint">加载中…</p>
      </section>

      <!-- worktree 列表 -->
      <section class="card">
        <h2 class="card-title">工作树（worktree）</h2>
        <p class="card-sub">同一个仓库的多份并行检出：构建互不污染，你的本地目录平台只读。</p>
        <div v-if="status && !status.initialized" class="hint">接入后可用</div>
        <div v-else-if="status?.worktrees?.length" class="wt-list">
          <div v-for="wt in status.worktrees" :key="wt.path" class="wt-row">
            <span class="wt-name mono">{{ wt.name }}</span>
            <span class="wt-role" :class="wt.role.toLowerCase()">{{ ROLE_LABEL[wt.role] ?? wt.role }}</span>
            <span class="wt-head mono">{{ wt.head?.slice(0, 8) }}</span>
            <button
              v-if="wt.role === 'BUILD'"
              type="button"
              class="mini-btn danger"
              @click="cleanup(wt)"
            >
              清理
            </button>
          </div>
        </div>
        <p v-else class="hint">暂无 worktree</p>
      </section>

      <!-- 操作区 -->
      <section class="card">
        <h2 class="card-title">操作</h2>
        <div class="ops">
          <button type="button" class="primary-btn" @click="goVerify">
            运行 verify 构建
            <el-icon><CaretRight /></el-icon>
          </button>
        </div>
        <div class="danger-zone">
          <p class="dz-note">解除托管：本地目录与文件完全不动，只移除平台登记（后端接口 P1 提供）。</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* 未接入引导 */
.not-initialized {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 16px;
  background: #fdf6ec;
  border: 1px solid #f5d3b8;
  border-radius: 10px;
}
.ni-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: #9a4514;
}
.ni-sub {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #9a4514;
}
.detail-page {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.detail-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 54rem;
  width: 100%;
  padding: 16px 24px 24px;
  margin: 0 auto;
  overflow-y: auto;
}
.card {
  padding: 16px 18px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
}
.card-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.card-sub {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--ink-faint);
}
.status-lines,
.wt-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.s-line,
.wt-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
  color: var(--ink);
}
.s-label {
  flex: none;
  width: 76px;
  color: var(--ink-muted);
}
.upstream {
  margin-left: 6px;
  font-size: 11px;
  color: var(--ink-faint);
}
.remotes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.remote-chip {
  padding: 1px 8px;
  font-size: 11px;
  color: var(--accent-ink);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  border-radius: var(--radius-pill);
}
.url {
  overflow: hidden;
  font-size: 11.5px;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ab {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
}
.ab-item.ahead {
  color: var(--warn);
}
.ab-item.behind {
  color: var(--accent-ink);
}
.ab-item.synced {
  color: var(--ok);
}
.warn-text {
  color: var(--warn);
  font-size: 12px;
}
.muted {
  font-size: 12px;
  color: var(--ink-faint);
}
.ok-dot {
  width: 6px;
  height: 6px;
  margin-right: 4px;
  background: var(--ok);
  border-radius: 50%;
}
.wt-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wt-role {
  flex: none;
  font-size: 11.5px;
  color: var(--ink-muted);
}
.wt-role.user {
  color: var(--accent-ink);
}
.wt-head {
  flex: none;
  font-size: 11px;
  color: var(--ink-faint);
}
.mini-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.mini-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.mini-btn.danger {
  color: var(--danger);
  border-color: color-mix(in srgb, var(--danger) 30%, transparent);
}
.mini-btn.danger:hover {
  background: color-mix(in srgb, var(--danger) 8%, transparent);
}
.ops {
  display: flex;
  gap: 10px;
}
.primary-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 16px;
  font-family: inherit;
  font-size: 13px;
  color: #fff;
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-md);
}
.danger-zone {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed color-mix(in srgb, var(--danger) 30%, transparent);
}
.dz-note {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.hint {
  font-size: 12px;
  color: var(--ink-faint);
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.hdr-btn {
  height: 26px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
</style>
