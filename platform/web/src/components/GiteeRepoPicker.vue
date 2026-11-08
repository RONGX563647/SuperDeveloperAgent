<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowRight, Lock, RefreshRight, Search, User } from '@element-plus/icons-vue'
import { giteeRepos, type GiteeRepo } from '../api/project'

/**
 * Gitee 仓库选择器（现代卡片列表）：
 * 顶部账号 + 范围切换 + 搜索；列表行含图标/描述/私有标/更新时间；
 * 支持 ↑↓ + Enter 键盘选择，hover 反馈，内部独立滚动。
 */
const props = defineProps<{
  modelValue: boolean
  account: string
}>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  pick: [repo: GiteeRepo]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const scope = ref<'personal' | 'all'>('personal')
const repos = ref<GiteeRepo[]>([])
const loading = ref(false)
const filter = ref('')
const activeIndex = ref(-1)
const listRef = ref<HTMLElement | null>(null)

const filtered = computed(() => {
  const f = filter.value.trim().toLowerCase()
  if (!f) return repos.value
  return repos.value.filter(r =>
    r.fullName.toLowerCase().includes(f) || (r.description ?? '').toLowerCase().includes(f))
})

async function load() {
  loading.value = true
  activeIndex.value = -1
  try {
    repos.value = await giteeRepos(scope.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '获取仓库列表失败')
    repos.value = []
  } finally {
    loading.value = false
  }
}

/** 拆 owner / repo 名，owner 淡显、repo 加粗。 */
function splitName(fullName: string): { owner: string; name: string } {
  const idx = fullName.indexOf('/')
  return idx < 0
    ? { owner: '', name: fullName }
    : { owner: fullName.slice(0, idx), name: fullName.slice(idx + 1) }
}

/** 相对更新时间（Gitee 返回 ISO；解析失败则原样）。 */
function relTime(iso: string): string {
  if (!iso) return ''
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return iso
  const diff = Date.now() - t
  const day = 86_400_000
  if (diff < 3_600_000) return '刚刚'
  if (diff < day) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < day * 30) return `${Math.floor(diff / day)} 天前`
  if (diff < day * 365) return `${Math.floor(diff / (day * 30))} 个月前`
  return `${Math.floor(diff / (day * 365))} 年前`
}

function choose(r: GiteeRepo) {
  emit('pick', r)
  visible.value = false
}

function onKey(e: KeyboardEvent) {
  const items = filtered.value
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!items.length) return
    activeIndex.value = (activeIndex.value + 1) % items.length
    void scrollActive()
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (!items.length) return
    activeIndex.value = (activeIndex.value - 1 + items.length) % items.length
    void scrollActive()
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const target = items[activeIndex.value] ?? items[0]
    if (target) choose(target)
  }
}

async function scrollActive() {
  await nextTick()
  const el = listRef.value?.querySelector('.repo-row.active') as HTMLElement | null
  el?.scrollIntoView({ block: 'nearest' })
}

watch(visible, (open) => {
  if (open) {
    filter.value = ''
    void load()
  }
})
</script>

<template>
  <el-dialog
    v-model="visible"
    width="720px"
    class="gitee-picker"
    :close-on-click-modal="false"
  >
    <template #header>
      <div class="gp-header">
        <div class="gp-title-block">
          <h3 class="gp-title">选择 Gitee 仓库</h3>
          <span class="gp-account">
            <el-icon><User /></el-icon>
            {{ account || '未绑定账号' }}
          </span>
        </div>
        <div class="gp-tools">
          <div class="gp-seg">
            <button
              type="button"
              class="seg-btn"
              :class="{ active: scope === 'personal' }"
              @click="scope = 'personal'; load()"
            >我的</button>
            <button
              type="button"
              class="seg-btn"
              :class="{ active: scope === 'all' }"
              @click="scope = 'all'; load()"
            >全部</button>
          </div>
          <el-input
            v-model="filter"
            size="small"
            placeholder="搜索仓库"
            class="gp-search"
            clearable
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <button type="button" class="gp-icon-btn" :disabled="loading" title="刷新" @click="load">
            <el-icon :class="{ spin: loading }"><RefreshRight /></el-icon>
          </button>
        </div>
      </div>
    </template>

    <div
      ref="listRef"
      class="gp-list"
      tabindex="0"
      @keydown="onKey"
    >
      <div v-if="loading && !repos.length" class="gp-skeleton">
        <div v-for="i in 6" :key="i" class="sk-row">
          <span class="sk-avatar" />
          <span class="sk-body">
            <span class="sk-line w60" />
            <span class="sk-line w85" />
          </span>
        </div>
      </div>

      <button
        v-for="(r, i) in filtered"
        :key="r.fullName"
        type="button"
        class="repo-row"
        :class="{ active: activeIndex === i }"
        @mouseenter="activeIndex = i"
        @click="choose(r)"
      >
        <span class="repo-icon" :class="{ private: r.privateRepo }">
          <el-icon><Lock v-if="r.privateRepo" /><User v-else /></el-icon>
        </span>
        <span class="repo-body">
          <span class="repo-title">
            <span class="repo-owner">{{ splitName(r.fullName).owner }}/</span>
            <span class="repo-name">{{ splitName(r.fullName).name }}</span>
          </span>
          <span class="repo-desc">{{ r.description || '（无描述）' }}</span>
        </span>
        <span class="repo-meta">
          <span v-if="r.privateRepo" class="tag-private">私有</span>
          <span class="repo-time">{{ relTime(r.updatedAt) }}</span>
        </span>
        <el-icon class="repo-go"><ArrowRight /></el-icon>
      </button>

      <div v-if="!filtered.length && !loading" class="gp-empty">
        <p class="empty-title">{{ filter ? '没有匹配的仓库' : '这个账号下还没有仓库' }}</p>
        <p class="empty-sub">{{ filter ? '换个关键词试试' : '在 Gitee 创建仓库后刷新即可看到' }}</p>
      </div>
    </div>

    <template #footer>
      <div class="gp-footer">
        <span class="gp-count">{{ filtered.length }} 个仓库 · ↑↓ 选择、Enter 确认</span>
        <el-button @click="visible = false">取消</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
/* ---------- 头部 ---------- */
.gp-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.gp-title-block {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.gp-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}
.gp-account {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 9px;
  font-size: 11.5px;
  color: var(--ink-soft);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.gp-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 分段控件 */
.gp-seg {
  display: inline-flex;
  padding: 2px;
  background: var(--surface-sunken);
  border-radius: var(--radius-md);
}
.seg-btn {
  height: 24px;
  padding: 0 12px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.seg-btn:hover {
  color: var(--ink);
}
.seg-btn.active {
  color: var(--ink);
  background: var(--panel);
  box-shadow: 0 1px 2px rgb(0 0 0 / 8%);
}
.gp-search {
  width: 168px;
}
.gp-icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.gp-icon-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.gp-icon-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---------- 列表 ---------- */
.gp-list {
  max-height: 420px;
  overflow-y: auto;
  outline: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  scrollbar-width: thin;
  scrollbar-color: var(--line) transparent;
}
.gp-list::-webkit-scrollbar {
  width: 8px;
}
.gp-list::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--ink-faint) 35%, transparent);
  border: 2px solid transparent;
  border-radius: 4px;
  background-clip: padding-box;
}
.gp-list::-webkit-scrollbar-track {
  background: transparent;
}

.repo-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 11px 14px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 1px solid var(--line-soft);
  transition: background-color var(--dur-fast) ease;
}
.repo-row:last-child {
  border-bottom: 0;
}
.repo-row:hover,
.repo-row.active {
  background: var(--surface-hover);
}
/* 左侧强调条：hover/键盘选中时出现 */
.repo-row::before {
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 0;
  width: 2px;
  content: '';
  background: var(--accent);
  border-radius: 0 2px 2px 0;
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.repo-row:hover::before,
.repo-row.active::before {
  opacity: 1;
}

.repo-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  font-size: 14px;
  color: var(--accent-ink);
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  border-radius: var(--radius-sm);
}
.repo-icon.private {
  color: var(--warn);
  background: color-mix(in srgb, var(--warn) 12%, transparent);
}
.repo-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.repo-title {
  display: flex;
  align-items: baseline;
  gap: 2px;
  min-width: 0;
  font-size: 13.5px;
}
.repo-owner {
  flex: none;
  color: var(--ink-faint);
}
.repo-name {
  overflow: hidden;
  font-weight: 600;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.repo-desc {
  overflow: hidden;
  font-size: 11.5px;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.repo-meta {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}
.tag-private {
  padding: 1px 7px;
  font-size: 10.5px;
  color: var(--warn);
  background: color-mix(in srgb, var(--warn) 12%, transparent);
  border-radius: var(--radius-pill);
}
.repo-time {
  font-size: 11px;
  color: var(--ink-faint);
  white-space: nowrap;
}
.repo-go {
  flex: none;
  font-size: 13px;
  color: var(--ink-faint);
  opacity: 0;
  transform: translateX(-4px);
  transition: opacity var(--dur-fast) ease, transform var(--dur-fast) ease;
}
.repo-row:hover .repo-go,
.repo-row.active .repo-go {
  opacity: 1;
  transform: translateX(0);
}

/* ---------- 空态 / 骨架 ---------- */
.gp-empty {
  padding: 48px 0;
  text-align: center;
}
.empty-title {
  margin: 0 0 4px;
  font-size: 13px;
  color: var(--ink-soft);
}
.empty-sub {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.gp-skeleton {
  padding: 6px 0;
}
.sk-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
}
.sk-avatar {
  flex: none;
  width: 30px;
  height: 30px;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.sk-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
}
.sk-line {
  height: 9px;
  background: var(--surface-sunken);
  border-radius: 3px;
}
.w60 {
  width: 60%;
}
.w85 {
  width: 85%;
  height: 7px;
}

/* ---------- 底部 ---------- */
.gp-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.gp-count {
  font-size: 11.5px;
  color: var(--ink-faint);
}
</style>
