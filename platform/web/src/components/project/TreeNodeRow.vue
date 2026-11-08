<script lang="ts">
// 递归组件：需显式声明自引用名
export default { name: 'TreeNodeRow' }
</script>

<script setup lang="ts">
import { computed } from 'vue'
import {
  ArrowRight, CopyDocument, Delete, Document, Folder,
  FolderOpened, Loading, RefreshRight, WarningFilled,
} from '@element-plus/icons-vue'
import type { TreeNode } from './useProjectTree'

/**
 * 树节点行（递归组件，self-reference）：目录/文件/项目根复用同一行样式；
 * 悬停浮现快捷操作（刷新/重命名/删除）；Active 高亮；目录懒加载转圈与错误重试。
 */
interface Props {
  node: TreeNode
  depth: number
  selectedId: string | null
  expanded: (node: TreeNode) => boolean
}

const props = withDefaults(defineProps<Props>(), { depth: 0 })

const emit = defineEmits<{
  (e: 'select', node: TreeNode): void
  (e: 'toggle', node: TreeNode): void
  (e: 'action', action: 'refresh' | 'rename' | 'delete', node: TreeNode): void
}>()

const isDir = computed(() => props.node.kind === 'dir')
const isFile = computed(() => props.node.kind === 'file')
const isOpen = computed(() => isDir.value && props.expanded(props.node))

function onClick(): void {
  if (isDir.value) emit('toggle', props.node)
  emit('select', props.node)
}
</script>

<template>
  <div class="tree-node">
    <div
      class="tree-row"
      :class="{ active: selectedId === node.id, dir: isDir, file: isFile }"
      :style="{ paddingLeft: `${depth * 16 + 10}px` }"
      @click="onClick"
    >
      <span class="tree-caret">
        <el-icon v-if="isDir" class="caret-btn" :class="{ open: isOpen }">
          <ArrowRight />
        </el-icon>
        <span v-else class="caret-spacer" />
      </span>

      <el-icon v-if="node.loading" class="tree-icon loading is-loading"><Loading /></el-icon>
      <el-icon v-else-if="node.error" class="tree-icon error"><WarningFilled /></el-icon>
      <el-icon v-else-if="isDir" class="tree-icon dir">
        <FolderOpened v-if="isOpen" />
        <Folder v-else />
      </el-icon>
      <el-icon v-else-if="node.gitRepo" class="tree-icon repo"><svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor"><path d="M11.75 2.5a.75.75 0 1 0 0 1.5.75.75 0 0 0 0-1.5Zm-2.25.75a2.25 2.25 0 1 1 3 2.122V6A2.5 2.5 0 0 1 10 8.5H6a1 1 0 0 0-1 1v1.128a2.251 2.251 0 1 1-1.5 0V5.372a2.25 2.25 0 1 1 1.5 0v1.836A2.492 2.492 0 0 1 6 7h4a1 1 0 0 0 1-1v-.628A2.25 2.25 0 0 1 9.5 3.25Z"/></svg></el-icon>
      <el-icon v-else class="tree-icon file"><Document /></el-icon>

      <span class="tree-name" :title="node.path">{{ node.name }}</span>

      <span class="tree-actions">
        <button type="button" class="ta-btn" title="刷新" @click.stop="emit('action', 'refresh', node)">
          <el-icon><RefreshRight /></el-icon>
        </button>
        <button v-if="isFile" type="button" class="ta-btn" title="重命名" @click.stop="emit('action', 'rename', node)">
          <el-icon><CopyDocument /></el-icon>
        </button>
        <button type="button" class="ta-btn danger" title="删除" @click.stop="emit('action', 'delete', node)">
          <el-icon><Delete /></el-icon>
        </button>
      </span>

      <button v-if="node.error" type="button" class="ta-retry" @click.stop="emit('toggle', node)">
        重试
      </button>
    </div>

    <!-- 子级：目录展开时渲染；懒加载完成前显示占位 -->
    <div v-if="isOpen" class="tree-children">
      <p v-if="!node.loaded && !node.error" class="tree-hint">加载中…</p>
      <p v-else-if="!node.children.length" class="tree-hint">（空目录）</p>
      <TreeNodeRow
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        :selected-id="selectedId"
        :expanded="expanded"
        @select="emit('select', $event)"
        @toggle="emit('toggle', $event)"
        @action="(a, n) => emit('action', a, n)"
      />
    </div>
  </div>
</template>

<style scoped>
.tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 10px;
  padding-right: 8px;
  font-size: 13px;
  color: var(--ink-soft);
  cursor: pointer;
  user-select: none;
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease, color var(--dur-fast) ease;
}
.tree-row:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.tree-row.active {
  color: var(--accent-ink);
  font-weight: 500;
  background: var(--accent-soft);
}
.tree-row.file .tree-name {
  font-family: var(--font-mono);
  font-size: 12.5px;
}
.tree-caret {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 14px;
}
.caret-btn {
  color: var(--ink-faint);
  transition: transform var(--dur-fast) ease;
}
.caret-btn.open {
  transform: rotate(90deg);
}
.caret-spacer {
  width: 12px;
}
.tree-icon {
  flex: none;
  font-size: 14px;
}
.tree-icon.dir { color: #e8a33d; }
.tree-icon.repo { color: var(--accent-ink); }
.tree-icon.file { color: var(--ink-muted); }
.tree-icon.error { color: var(--danger); }
.tree-icon.loading { color: var(--accent-ink); }
.tree-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 2px;
  opacity: 0;
  transition: opacity var(--dur-fast) ease;
}
.tree-row:hover .tree-actions {
  opacity: 1;
}
.ta-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.ta-btn:hover {
  color: var(--ink);
  background: var(--surface-active);
}
.ta-btn.danger:hover {
  color: var(--danger);
  background: var(--danger-soft);
}
.ta-retry {
  flex: none;
  padding: 1px 6px;
  font-size: 10.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: var(--accent-soft);
  border: 0;
  border-radius: var(--radius-xs);
}
.tree-children {
  margin: 1px 0;
}
.tree-hint {
  margin: 0;
  padding: 4px 0 4px 42px;
  font-size: 11.5px;
  color: var(--ink-faint);
}
</style>
