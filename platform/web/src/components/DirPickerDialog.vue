<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Folder, House, Monitor, Search, Document, Download, Star, ArrowUp, Check } from '@element-plus/icons-vue'
import { fsLs, fsRoots, type FsEntry, type FsListing, type FsShortcut } from '../api/project'

/**
 * 现代目录选择器（VS Code / GitHub Desktop 风格）：
 * 左侧快捷位置 + 顶部面包屑与搜索 + 主区列表（单击选中 / 双击进入 / 键盘 ↑↓ Enter）。
 */
const props = defineProps<{
  modelValue: boolean
  /** 初始定位目录。 */
  initialPath?: string
  /** 对话框标题。 */
  title?: string
  /** 是否显示 git 仓库标记。 */
  showGit?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  pick: [payload: { path: string; git: boolean }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

const shortcuts = ref<FsShortcut[]>([])
const listing = ref<FsListing | null>(null)
const loading = ref(false)
const filter = ref('')
const selected = ref<FsEntry | null>(null)
const listRef = ref<HTMLElement | null>(null)

const ICONS: Record<string, unknown> = {
  workspace: Star,
  home: House,
  desktop: Monitor,
  documents: Document,
  downloads: Download,
  drive: Monitor,
  folder: Folder
}

/** 面包屑：把当前路径拆成可点击的段。 */
const crumbs = computed(() => {
  const p = listing.value?.path
  if (!p) return []
  const normalized = p.replace(/\\/g, '/')
  const parts = normalized.split('/').filter(Boolean)
  const out: { label: string; path: string }[] = []
  // Windows: "E:" 是根
  if (/^[A-Za-z]:$/.test(parts[0] ?? '')) {
    out.push({ label: parts[0].toUpperCase(), path: parts[0] + '/' })
    let acc = parts[0]
    for (let i = 1; i < parts.length; i++) {
      acc += '/' + parts[i]
      out.push({ label: parts[i], path: acc })
    }
  } else {
    out.push({ label: '/', path: '/' })
    let acc = ''
    for (const part of parts) {
      acc += '/' + part
      out.push({ label: part, path: acc })
    }
  }
  return out
})

const entries = computed(() => {
  const all = listing.value?.entries ?? []
  const f = filter.value.trim().toLowerCase()
  const filtered = f ? all.filter(e => e.name.toLowerCase().includes(f)) : all
  // 目录优先
  return [...filtered].sort((a, b) => {
    if (a.directory !== b.directory) return a.directory ? -1 : 1
    return a.name.localeCompare(b.name, 'zh-CN')
  })
})

const dirCount = computed(() => entries.value.filter(e => e.directory).length)

async function loadShortcuts() {
  try {
    shortcuts.value = (await fsRoots()).shortcuts
  } catch {
    shortcuts.value = []
  }
}

async function load(path?: string) {
  loading.value = true
  try {
    listing.value = await fsLs(path || undefined)
    selected.value = null
    filter.value = ''
    await nextTick()
    listRef.value?.focus()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '读取目录失败')
  } finally {
    loading.value = false
  }
}

function openShortcut(s: FsShortcut) {
  void load(s.path)
}

function enter(e: FsEntry) {
  if (e.directory) void load(e.path)
}

function select(e: FsEntry) {
  selected.value = e
}

function confirm() {
  const target = selected.value?.directory ? selected.value : null
  if (!target) {
    // 未选中子项时，允许"选择当前目录"
    if (listing.value?.path) {
      emit('pick', { path: listing.value.path, git: isGitHere() })
      visible.value = false
      return
    }
    ElMessage.warning('请选择一个目录')
    return
  }
  emit('pick', { path: target.path, git: target.gitRepo })
  visible.value = false
}

function isGitHere(): boolean {
  // 当前目录本身是否 git 仓库：由后端 ls 时对子项标记；当前目录用父级列表无法判断，
  // 这里保守返回 false，接入侧还有 probe 兜底。
  return false
}

function goUp() {
  if (listing.value?.parent) void load(listing.value.parent)
}

/** 键盘导航：↑↓ 移动、Enter 进入或确认、Backspace 上一级。 */
function onKey(e: KeyboardEvent) {
  const items = entries.value.filter(x => x.directory)
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    if (!items.length) return
    const idx = selected.value ? items.findIndex(i => i.path === selected.value!.path) : -1
    const next = e.key === 'ArrowDown'
      ? (idx + 1) % items.length
      : (idx - 1 + items.length) % items.length
    selected.value = items[next]
    void scrollIntoView()
  } else if (e.key === 'Enter') {
    e.preventDefault()
    if (selected.value?.directory) confirm()
  } else if (e.key === 'Backspace') {
    e.preventDefault()
    goUp()
  } else if (e.key === 'Escape') {
    visible.value = false
  }
}

async function scrollIntoView() {
  await nextTick()
  const el = listRef.value?.querySelector('.row.active') as HTMLElement | null
  el?.scrollIntoView({ block: 'nearest' })
}

watch(visible, (open) => {
  if (open) {
    void loadShortcuts()
    void load(props.initialPath)
  }
})
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title ?? '选择目录'"
    width="760px"
    class="dir-picker"
    :close-on-click-modal="false"
  >
    <div class="picker-shell">
      <!-- 左侧：快捷位置 -->
      <aside class="picker-side">
        <button
          v-for="s in shortcuts"
          :key="s.key"
          type="button"
          class="side-item"
          :class="{ active: listing?.path && s.path === listing.path }"
          @click="openShortcut(s)"
        >
          <el-icon><component :is="ICONS[s.kind] ?? Folder" /></el-icon>
          <span class="side-label">{{ s.label }}</span>
        </button>
      </aside>

      <!-- 右侧：面包屑 + 搜索 + 列表 -->
      <section class="picker-main">
        <div class="bar">
          <button type="button" class="icon-btn" :disabled="!listing?.parent" title="上一级" @click="goUp">
            <el-icon><ArrowUp /></el-icon>
          </button>
          <div class="crumbs">
            <template v-for="(c, i) in crumbs" :key="c.path">
              <span v-if="i > 0" class="crumb-sep">›</span>
              <button type="button" class="crumb" @click="load(c.path)">{{ c.label }}</button>
            </template>
          </div>
          <el-input v-model="filter" size="small" placeholder="搜索文件夹" class="filter">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>

        <div
          ref="listRef"
          class="list"
          tabindex="0"
          @keydown="onKey"
          v-loading="loading"
        >
          <div
            v-for="e in entries"
            :key="e.path"
            class="row"
            :class="{ active: selected?.path === e.path, file: !e.directory }"
            @click="select(e)"
            @dblclick="enter(e)"
          >
            <el-icon class="row-icon"><Folder v-if="e.directory" /><Document v-else /></el-icon>
            <span class="row-name">{{ e.name }}</span>
            <span v-if="showGit !== false && e.gitRepo" class="git-badge">git</span>
            <el-icon v-if="selected?.path === e.path" class="row-check"><Check /></el-icon>
          </div>
          <div v-if="!entries.length && !loading" class="empty">（空目录）</div>
        </div>

        <p class="hint">
          {{ dirCount }} 个文件夹 · 单击选中、双击进入 · ↑↓ 移动、Enter 确认、Backspace 上一级
        </p>
      </section>
    </div>

    <template #footer>
      <div class="footer">
        <span class="picked mono">{{ selected?.path ?? listing?.path ?? '—' }}</span>
        <div class="footer-actions">
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" @click="confirm">
            使用此目录
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.picker-shell {
  display: flex;
  gap: 0;
  height: 420px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.picker-side {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 2px;
  width: 168px;
  padding: 8px 6px;
  overflow-y: auto;
  background: var(--surface-sunken);
  border-right: 1px solid var(--line-soft);
}
.side-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-soft);
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
}
.side-item:hover {
  background: var(--surface-hover);
  color: var(--ink);
}
.side-item.active {
  color: var(--accent-ink);
  background: color-mix(in srgb, var(--accent) 9%, transparent);
}
.side-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.picker-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  padding: 8px 10px 4px;
}
.bar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.icon-btn {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.icon-btn:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}
.crumbs {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 2px;
  min-width: 0;
  overflow: hidden;
  padding: 3px 8px;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.crumb {
  flex: none;
  padding: 0 4px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 3px;
}
.crumb:hover {
  color: var(--accent-ink);
  background: var(--surface-hover);
}
.crumb-sep {
  flex: none;
  font-size: 10px;
  color: var(--ink-faint);
}
.filter {
  flex: none;
  width: 150px;
}
.list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  outline: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  font-size: 13px;
  color: var(--ink);
  cursor: default;
  border-bottom: 1px solid var(--line-soft);
}
.row:last-child {
  border-bottom: 0;
}
.row:hover {
  background: var(--surface-hover);
}
.row.active {
  background: color-mix(in srgb, var(--accent) 10%, transparent);
  color: var(--accent-ink);
}
.row.file {
  color: var(--ink-faint);
}
.row-icon {
  flex: none;
  font-size: 15px;
  color: var(--warn);
}
.row.file .row-icon {
  color: var(--ink-faint);
}
.row-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.git-badge {
  flex: none;
  padding: 0 6px;
  font-size: 10px;
  color: var(--ok);
  background: color-mix(in srgb, var(--ok) 12%, transparent);
  border-radius: var(--radius-pill);
}
.row-check {
  flex: none;
  font-size: 13px;
  color: var(--accent);
}
.empty {
  padding: 40px 0;
  font-size: 12px;
  color: var(--ink-faint);
  text-align: center;
}
.hint {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--ink-faint);
}
.footer {
  display: flex;
  align-items: center;
  gap: 12px;
}
.picked {
  flex: 1;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--ink-muted);
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.footer-actions {
  display: flex;
  gap: 8px;
}
.mono {
  font-family: var(--font-mono);
}
</style>
