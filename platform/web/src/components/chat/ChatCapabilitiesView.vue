<script setup lang="ts">
import { computed } from 'vue'
import { Refresh, SetUp } from '@element-plus/icons-vue'
import type { CapabilityOption } from '../../composables/useChatCapabilities'

/**
 * 会话能力页（顶部 Tab「能力」）。
 *
 * <p>与「轨迹」同级：占满整页、可滚动，按会话勾选要调用的 Skill / MCP。
 * 默认值来自设置页的启用开关；这里改动只影响当前会话，并持久化到 localStorage。
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
  <div class="cap-view">
    <div class="cap-shell">
      <header class="cap-head">
        <div class="cap-head-main">
          <div class="cap-title">
            <el-icon><SetUp /></el-icon>
            本会话能力
          </div>
          <div class="cap-desc">
            勾选本次对话允许调用的 Skill / MCP。默认值来自设置页的启用开关，这里只影响当前会话。
          </div>
        </div>
        <div class="cap-head-side">
          <span class="cap-count mono">{{ ready ? count : '…' }}</span>
          <span class="cap-count-label">项已启用</span>
          <el-button size="small" :loading="loading" @click="emit('refresh')">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </header>

      <div class="cap-grid">
        <section class="cap-block">
          <div class="cap-block-head">
            <span class="cap-block-title">Skill</span>
            <span class="cap-sub mono">{{ skills.length }} / {{ skillOptions.length }}</span>
            <span class="sec-actions">
              <button type="button" class="link" @click="toggleAll('skill', true)">全选</button>
              <button type="button" class="link" @click="toggleAll('skill', false)">清空</button>
            </span>
          </div>
          <div v-if="!skillOptions.length" class="cap-empty">
            还没有 Skill，可在 设置 → Skill 市场 接入
          </div>
          <label v-for="o in skillOptions" :key="o.key" class="cap-row">
            <span class="cap-row-main">
              <span class="cap-name mono">{{ o.label }}</span>
              <span class="cap-desc-text">{{ o.description || '（无描述）' }}</span>
            </span>
            <el-switch
              size="small"
              :model-value="skills.includes(o.key)"
              @change="(v: string | number | boolean) => toggle('skill', o.key, Boolean(v))"
            />
          </label>
        </section>

        <section class="cap-block">
          <div class="cap-block-head">
            <span class="cap-block-title">MCP</span>
            <span class="cap-sub mono">{{ mcp.length }} / {{ mcpOptions.length }}</span>
            <span class="sec-actions">
              <button type="button" class="link" @click="toggleAll('mcp', true)">全选</button>
              <button type="button" class="link" @click="toggleAll('mcp', false)">清空</button>
            </span>
          </div>
          <div v-if="!mcpOptions.length" class="cap-empty">
            还没有 MCP 服务，可在 设置 → MCP 市场 接入
          </div>
          <label v-for="o in mcpOptions" :key="o.key" class="cap-row">
            <span class="cap-row-main">
              <span class="cap-name mono">{{ o.label }}</span>
              <span class="cap-desc-text">{{ o.description || '（未配置地址）' }}</span>
            </span>
            <el-switch
              size="small"
              :model-value="mcp.includes(o.key)"
              @change="(v: string | number | boolean) => toggle('mcp', o.key, Boolean(v))"
            />
          </label>
        </section>
      </div>

      <div class="cap-foot">
        发送消息时，只有这里勾选的 Skill / MCP 会进入本轮运行时；单个 MCP 初始化失败会自动降级跳过。
      </div>
    </div>
  </div>
</template>

<style scoped>
.cap-view {
  flex: 1 1 0;
  min-height: 0;
  padding: 22px 24px 28px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.cap-shell {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 960px;
  margin: 0 auto;
}
.cap-head {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-soft);
}
.cap-head-main {
  flex: 1;
  min-width: 0;
}
.cap-title {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--ink);
}
.cap-desc {
  max-width: 42rem;
  margin-top: 5px;
  font-size: var(--text-xs);
  line-height: var(--leading-base);
  color: var(--ink-muted);
}
.cap-head-side {
  display: flex;
  flex: none;
  gap: 6px;
  align-items: baseline;
}
.cap-count {
  font-size: 20px;
  font-weight: 600;
  line-height: 1;
  color: var(--ink);
}
.cap-count-label {
  margin-right: 8px;
  font-size: 11px;
  color: var(--ink-faint);
}
.cap-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 14px;
  align-items: start;
}
.cap-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 12px 14px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.cap-block-head {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-bottom: 7px;
  margin-bottom: 2px;
  border-bottom: 1px solid var(--line-soft);
}
.cap-block-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.cap-sub {
  flex: 1;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.sec-actions {
  display: flex;
  gap: 8px;
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
  padding: 12px 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.cap-row {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 7px 0;
  cursor: pointer;
  border-bottom: 1px dashed var(--line-soft);
}
.cap-row:last-child {
  border-bottom: 0;
}
.cap-row-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}
.cap-name {
  font-size: 12px;
  color: var(--ink);
}
.cap-desc-text {
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.cap-foot {
  padding-top: 4px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
