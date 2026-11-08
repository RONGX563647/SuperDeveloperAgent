<script setup lang="ts">
import { shallowRef } from 'vue'

/**
 * 简易 Tab 容器（零依赖，替代 el-tabs 的重样式）：
 * openTab(path,name) 由父视图调用；关闭逻辑内置（至少保留一个）。
 */
export interface TabItem {
  path: string
  name: string
}

const tabs = shallowRef<TabItem[]>([])
const active = shallowRef<string | null>(null)

function openTab(path: string, name: string): void {
  const next = [...tabs.value]
  if (!next.some((t) => t.path === path)) next.push({ path, name })
  tabs.value = next
  active.value = path
}

function closeTab(path: string): void {
  const idx = tabs.value.findIndex((t) => t.path === path)
  if (idx < 0) return
  const next = tabs.value.filter((t) => t.path !== path)
  tabs.value = next
  if (active.value === path) {
    active.value = next.length ? (next[idx - 1]?.path ?? next[0]?.path ?? null) : null
  }
}

defineExpose({ openTab })
</script>

<template>
  <div class="tabbed-panel">
    <div v-if="tabs.length" class="tab-bar">
      <button
        v-for="t in tabs"
        :key="t.path"
        type="button"
        class="tab"
        :class="{ active: active === t.path }"
        :title="t.path"
        @click="active = t.path"
      >
        <span class="tab-name">{{ t.name }}</span>
        <span
          class="tab-close"
          role="button"
          aria-label="关闭标签"
          @click.stop="closeTab(t.path)"
        >×</span>
      </button>
    </div>

    <div class="tab-content">
      <template v-for="t in tabs" :key="t.path">
        <div v-show="active === t.path" class="tab-pane">
          <slot :tab="t" />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tabbed-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}
.tab-bar {
  display: flex;
  flex: none;
  align-items: stretch;
  gap: 2px;
  padding: 6px 10px 0;
  background: var(--panel);
  border-bottom: 1px solid var(--line-soft);
  overflow-x: auto;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 200px;
  padding: 6px 8px 6px 12px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
  transition: color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.tab:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.tab.active {
  color: var(--accent-ink);
  font-weight: 500;
  background: var(--bg);
  border-bottom-color: var(--accent);
}
.tab-name {
  overflow: hidden;
  font-family: var(--font-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tab-close {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  font-size: 13px;
  color: var(--ink-faint);
  border-radius: var(--radius-xs);
}
.tab-close:hover {
  color: var(--ink);
  background: var(--surface-active);
}
.tab-content {
  flex: 1 1 0;
  min-height: 0;
  overflow: hidden;
}
.tab-pane {
  height: 100%;
  overflow: hidden;
}
</style>
