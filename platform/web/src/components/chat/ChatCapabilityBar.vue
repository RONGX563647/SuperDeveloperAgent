<script setup lang="ts">
import { computed, ref } from 'vue'
import { Close, Refresh, SetUp } from '@element-plus/icons-vue'
import type { CapabilityOption } from '../../composables/useChatCapabilities'

/**
 * 对话工具栏（轨迹旁）：按会话选择调用哪些 Skill / MCP。
 *
 * <p>默认值来自设置页的启用开关；这里改动只影响当前会话，并持久化到 localStorage。
 * 发送消息时由 ChatView 把勾选结果放进请求（skills / mcpServers）。
 */
const props = defineProps<{
  skillOptions: CapabilityOption[]
  mcpOptions: CapabilityOption[]
  skills: string[]
  mcp: string[]
  ready: boolean
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:skills', value: string[]): void
  (e: 'update:mcp', value: string[]): void
  (e: 'refresh'): void
}>()

const open = ref(false)
const count = computed(() => props.skills.length + props.mcp.length)

function toggle(kind: 'skill' | 'mcp', key: string, on: boolean): void {
  const current = kind === 'skill' ? props.skills : props.mcp
  const next = on
    ? Array.from(new Set([...current, key]))
    : current.filter((item) => item !== key)
  if (kind === 'skill') emit('update:skills', next)
  else emit('update:mcp', next)
}

function toggleAll(kind: 'skill' | 'mcp', on: boolean): void {
  const options = kind === 'skill' ? props.skillOptions : props.mcpOptions
  const current = kind === 'skill' ? props.skills : props.mcp
  const next = on
    ? Array.from(new Set([...current, ...options.map((o) => o.key)]))
    : current.filter((key) => !options.some((o) => o.key === key))
  if (kind === 'skill') emit('update:skills', next)
  else emit('update:mcp', next)
}
</script>

<template>
  <div class="cap-bar">
    <button
      type="button"
      class="cap-toggle"
      :class="{ active: open }"
      title="本会话可调用的 Skill / MCP"
      @click="open = !open"
    >
      <el-icon><SetUp /></el-icon>
      <span v-if="count" class="cap-count mono">{{ count }}</span>
    </button>

    <div v-if="open" class="cap-panel">
      <div class="cap-head">
        <span class="cap-title">本会话能力</span>
        <span class="cap-sub mono">
          {{ ready ? `${skills.length} Skill · ${mcp.length} MCP` : '加载中…' }}
        </span>
        <button type="button" class="icon-btn" title="刷新" :disabled="loading" @click="emit('refresh')">
          <el-icon><Refresh /></el-icon>
        </button>
        <button type="button" class="icon-btn" title="关闭" @click="open = false">
          <el-icon><Close /></el-icon>
        </button>
      </div>

      <div class="cap-section">
        <div class="cap-sec-head">
          <span>Skill</span>
          <span class="sec-actions">
            <button type="button" class="link" @click="toggleAll('skill', true)">全选</button>
            <button type="button" class="link" @click="toggleAll('skill', false)">清空</button>
          </span>
        </div>
        <div v-if="!skillOptions.length" class="cap-empty">还没有 Skill，可在 设置 → Skill 市场 接入</div>
        <label v-for="o in skillOptions" :key="o.key" class="cap-row">
          <span class="cap-row-main">
            <span class="cap-name mono">{{ o.label }}</span>
            <span class="cap-desc">{{ o.description || '（无描述）' }}</span>
          </span>
          <el-switch
            size="small"
            :model-value="skills.includes(o.key)"
            @change="(v: string | number | boolean) => toggle('skill', o.key, Boolean(v))"
          />
        </label>
      </div>

      <div class="cap-section">
        <div class="cap-sec-head">
          <span>MCP</span>
          <span class="sec-actions">
            <button type="button" class="link" @click="toggleAll('mcp', true)">全选</button>
            <button type="button" class="link" @click="toggleAll('mcp', false)">清空</button>
          </span>
        </div>
        <div v-if="!mcpOptions.length" class="cap-empty">还没有 MCP 服务，可在 设置 → MCP 市场 接入</div>
        <label v-for="o in mcpOptions" :key="o.key" class="cap-row">
          <span class="cap-row-main">
            <span class="cap-name mono">{{ o.label }}</span>
            <span class="cap-desc">{{ o.description || '（未配置地址）' }}</span>
          </span>
          <el-switch
            size="small"
            :model-value="mcp.includes(o.key)"
            @change="(v: string | number | boolean) => toggle('mcp', o.key, Boolean(v))"
          />
        </label>
      </div>

      <div class="cap-foot">默认启停可在 设置 → MCP / Skill 调整；这里只影响当前会话。</div>
    </div>
  </div>
</template>

<style scoped>
.cap-bar {
  position: relative;
  display: flex;
  align-items: center;
}
.cap-toggle {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  font-size: 12px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  opacity: 0.55;
  transition: opacity var(--dur-fast) ease, color var(--dur-fast) ease,
    background-color var(--dur-fast) ease;
}
.cap-toggle:hover,
.cap-toggle.active {
  color: var(--ink);
  background: var(--surface-hover);
  opacity: 1;
}
.cap-count {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 13px;
  height: 13px;
  padding: 0 3px;
  font-size: 9px;
  line-height: 13px;
  color: #fff;
  text-align: center;
  background: var(--accent);
  border-radius: 7px;
}
.cap-panel {
  position: absolute;
  top: 0;
  right: calc(100% + 10px);
  z-index: 8;
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 300px;
  max-height: 360px;
  padding: 10px 12px;
  overflow: auto;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  box-shadow: 0 10px 30px rgb(16 24 40 / 12%);
}
.cap-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cap-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.cap-sub {
  flex: 1;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
}
.icon-btn:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.cap-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cap-sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 3px;
  font-size: 10.5px;
  color: var(--ink-muted);
  border-bottom: 1px solid var(--line-soft);
}
.sec-actions {
  display: flex;
  gap: 6px;
}
.link {
  padding: 0;
  font-size: 10.5px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
}
.link:hover {
  color: var(--accent);
}
.cap-empty {
  padding: 6px 0;
  font-size: 11px;
  color: var(--ink-faint);
}
.cap-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  cursor: pointer;
}
.cap-row-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}
.cap-name {
  font-size: 11.5px;
  color: var(--ink);
}
.cap-desc {
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.cap-foot {
  padding-top: 4px;
  font-size: 10px;
  color: var(--ink-faint);
  border-top: 1px solid var(--line-soft);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
