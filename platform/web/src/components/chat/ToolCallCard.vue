<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { Loading, Select } from '@element-plus/icons-vue'
import type { ToolActivity } from '../../types'
import { toolDisplayName } from '../../state/agentEventReducer'

/**
 * 工具调用折叠卡（参考 ruoyi-copilot 的 ToolCallCard.vue，皮肤用 Element Plus 重做）：
 * `>_` 前缀 + 深色命令胶囊 + 状态标；点击展开看 toolCallId / arguments。
 */
interface Props {
  tool: ToolActivity
  running?: boolean
}

const props = defineProps<Props>()
const expanded = shallowRef(false)

const statusText = computed(() => {
  if (props.running || props.tool.status === 'RUNNING') return '执行中'
  return props.tool.status === 'FAILED' ? '失败' : '已完成'
})
const statusClass = computed(() => {
  if (props.running || props.tool.status === 'RUNNING') return 'running'
  return props.tool.status === 'FAILED' ? 'failed' : 'done'
})
const statusDetail = computed(() =>
  props.tool.code ? `${statusText.value} · ${props.tool.code}` : statusText.value)

const argumentsPreview = computed(() => {
  const raw = props.tool.arguments
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})
</script>

<template>
  <article class="tool-call-card" :class="`tool-call-card--${statusClass}`">
    <button class="tool-call-header" type="button" :aria-expanded="expanded" @click="expanded = !expanded">
      <span class="tool-caret" aria-hidden="true">&gt;_</span>
      <span class="tool-name">{{ toolDisplayName(tool.toolName) }}</span>
      <code class="tool-cmd">{{ tool.toolCallId }}</code>
      <span class="tool-state" :class="statusClass">
        <el-icon v-if="statusClass === 'running'" class="is-loading"><Loading /></el-icon>
        <el-icon v-else-if="statusClass === 'done'"><Select /></el-icon>
        <span v-else class="tool-x">✕</span>
        {{ statusDetail }}
      </span>
    </button>
    <div v-if="expanded" class="tool-call-detail">
      <pre v-if="argumentsPreview">{{ argumentsPreview }}</pre>
      <p v-else class="tool-empty">无参数</p>
    </div>
  </article>
</template>

<style scoped>
.tool-call-card {
  margin: 2px 0;
  font-size: 12px;
  color: var(--ink-muted);
}
.tool-call-header {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 3px 0;
  font-family: inherit;
  font-size: 12px;
  color: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
}
.tool-caret {
  flex: none;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--accent-ink);
  opacity: 0.85;
}
.tool-name {
  flex: none;
  font-weight: 500;
  color: var(--ink-soft);
}
.tool-cmd {
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
  padding: 2px 8px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: nowrap;
  text-overflow: ellipsis;
  background: var(--surface-hover);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.tool-state {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 3px;
  font-size: 11px;
}
.tool-state.done { color: var(--ok); }
.tool-state.running { color: var(--accent-ink); }
.tool-state.failed { color: var(--danger); }
.tool-x { font-size: 12px; font-weight: 600; }
.tool-call-detail {
  padding: 6px 0 4px 26px;
}
.tool-call-detail pre {
  margin: 0;
  padding: 8px 10px;
  overflow-x: auto;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--surface-hover);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.tool-empty {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
</style>
