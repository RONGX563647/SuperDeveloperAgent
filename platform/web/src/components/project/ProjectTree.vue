<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { Plus, Search } from '@element-plus/icons-vue'
import type { ProjectRow } from '../../api/project'
import { useProjectTree } from './useProjectTree'
import TreeNodeRow from './TreeNodeRow.vue'
import type { TreeNode } from './useProjectTree'

/**
 * 项目文档树面板（左栏）：
 * 顶部搜索 + 接入按钮；树根 = 托管项目；目录懒加载；空状态引导。
 */
interface Props {
  projects: ProjectRow[]
  selectedId: string | null
  /** 项目列表请求中：显示骨架而不是「还没有托管项目」。 */
  loading?: boolean
  /** 项目列表请求失败：显示失败原因与重试。 */
  error?: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'select', node: TreeNode): void
  (e: 'action', action: 'refresh' | 'rename' | 'delete', node: TreeNode): void
  (e: 'intake'): void
  (e: 'retry'): void
}>()

const {
  nodes, keyword, visibleRoots,
  buildRoots, retryDir, toggle, isExpanded,
} = useProjectTree(() => props.projects)

onMounted(buildRoots)
watch(() => props.projects, buildRoots)
</script>

<template>
  <aside class="tree-panel">
    <div class="tree-toolbar">
      <div class="search-wrap">
        <el-icon class="search-icon"><Search /></el-icon>
        <input
          v-model="keyword"
          type="text"
          class="tree-search"
          placeholder="搜索项目 / 路径…"
        >
      </div>
      <button type="button" class="tree-add" title="接入项目" @click="emit('intake')">
        <el-icon><Plus /></el-icon>
      </button>
    </div>

    <div class="tree-body" :aria-busy="loading ? 'true' : undefined">
      <template v-if="visibleRoots.length">
        <TreeNodeRow
          v-for="root in visibleRoots"
          :key="root.id"
          :node="root"
          :depth="0"
          :selected-id="selectedId"
          :expanded="isExpanded"
          @select="emit('select', $event)"
          @toggle="toggle"
          @retry="retryDir"
          @action="(a, n) => emit('action', a, n)"
        />
        <p v-if="loading" class="tree-hint loading-all">刷新中…</p>
      </template>

      <!-- 首次加载：骨架行，避免先闪一下「还没有托管项目」 -->
      <div v-else-if="loading" class="tree-skeleton" aria-hidden="true">
        <i
          v-for="(w, i) in [78, 62, 88, 54, 70]" :key="i"
          class="skeleton tree-sk-row" :style="{ width: `${w}%` }"
        ></i>
      </div>

      <!-- 加载失败：给出原因与重试，而不是伪装成空清单 -->
      <div v-else-if="error" class="tree-empty">
        <p class="te-title">项目加载失败</p>
        <p class="te-sub">{{ error }}</p>
        <button type="button" class="te-btn" @click="emit('retry')">重试</button>
      </div>

      <!-- 空状态：无项目 -->
      <div v-else-if="nodes.length === 0" class="tree-empty">
        <div class="te-illus" aria-hidden="true">
          <svg width="72" height="48" viewBox="0 0 72 48" fill="none">
            <rect x="4" y="6" width="40" height="36" rx="5" fill="var(--surface-sunken)" />
            <rect x="10" y="14" width="20" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.5" />
            <rect x="10" y="22" width="28" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.35" />
            <rect x="10" y="30" width="16" height="3" rx="1.5" fill="var(--ink-faint)" opacity="0.25" />
            <circle cx="54" cy="30" r="12" fill="var(--accent-soft)" />
            <path d="M54 24v12M48 30h12" stroke="var(--accent)" stroke-width="2" stroke-linecap="round" />
          </svg>
        </div>
        <p class="te-title">还没有托管项目</p>
        <p class="te-sub">接入本地目录或 Git 仓库开始使用</p>
        <button type="button" class="te-btn" @click="emit('intake')">接入第一个项目</button>
      </div>

      <!-- 空状态：搜索无结果 -->
      <div v-else class="tree-empty">
        <p class="te-title">没有匹配「{{ keyword }}」的项目</p>
        <p class="te-sub">换个关键词试试</p>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.tree-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  background: var(--panel);
}
.tree-toolbar {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  padding: 12px 12px 10px;
  border-bottom: 1px solid var(--line-soft);
}
.search-wrap {
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
}
.search-icon {
  position: absolute;
  left: 9px;
  font-size: 13px;
  color: var(--ink-faint);
  pointer-events: none;
}
.tree-search {
  width: 100%;
  height: 28px;
  padding: 0 10px 0 28px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink);
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
  transition: border-color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.tree-search::placeholder {
  color: var(--ink-faint);
}
.tree-search:focus {
  background: var(--panel);
  border-color: var(--accent-ring);
}
.tree-add {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  font-size: 14px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease,
    border-color var(--dur-fast) ease;
}
.tree-add:hover {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: rgb(77 130 247 / 35%);
}
.tree-body {
  flex: 1 1 0;
  min-height: 0;
  padding: 8px 8px 16px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* 空状态 */
.tree-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 56px 16px;
  text-align: center;
}
.te-illus {
  margin-bottom: 8px;
}
.te-title {
  margin: 0;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-muted);
}
.te-sub {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.te-btn {
  height: 30px;
  padding: 0 14px;
  margin-top: 8px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-inverse);
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease;
}
.te-btn:hover {
  background: var(--accent-hover);
}
.loading-all {
  padding: 32px 0;
  font-size: 12px;
  color: var(--ink-faint);
  text-align: center;
}

/* 首次加载骨架：与真实行同高，列表出现时不跳版 */
.tree-skeleton {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 6px 4px;
}

.tree-sk-row {
  height: 26px;
  border-radius: var(--radius-sm);
}
</style>
