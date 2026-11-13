<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Refresh, SetUp } from '@element-plus/icons-vue'
import type { CapabilityOption } from '../../composables/useChatCapabilities'

/**
 * 会话能力页（顶部 Tab「能力」）。
 *
 * <p>左右两栏：左侧按 Skill / MCP 分组列出能力，点击选中；右侧显示该能力的
 * 类型、标识、连接信息与当前会话开关。默认值来自设置页，改动只影响当前会话。
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

interface Item extends CapabilityOption {
  kind: 'skill' | 'mcp'
}

const count = computed(() => props.skills.length + props.mcp.length)
const items = computed<Item[]>(() => [
  ...props.skillOptions.map((o) => ({ ...o, kind: 'skill' as const })),
  ...props.mcpOptions.map((o) => ({ ...o, kind: 'mcp' as const })),
])

const activeKey = ref('')
const active = computed(() => items.value.find((i) => keyOf(i) === activeKey.value) ?? null)

function keyOf(item: Item): string {
  return `${item.kind}:${item.key}`
}

watch(items, (list) => {
  if (!list.some((i) => keyOf(i) === activeKey.value)) {
    activeKey.value = list.length ? keyOf(list[0]) : ''
  }
}, { immediate: true })

function isOn(item: Item): boolean {
  return (item.kind === 'skill' ? props.skills : props.mcp).includes(item.key)
}

function toggle(item: Item, on: boolean): void {
  const current = item.kind === 'skill' ? props.skills : props.mcp
  const next = on
    ? Array.from(new Set([...current, item.key]))
    : current.filter((key) => key !== item.key)
  if (item.kind === 'skill') emit('update:skills', next)
  else emit('update:mcp', next)
}

function toggleActive(on: boolean): void {
  if (active.value) toggle(active.value, on)
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

function kindLabel(kind: 'skill' | 'mcp'): string {
  return kind === 'skill' ? 'Skill' : 'MCP'
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
            左侧选择能力，右侧查看信息并决定是否在本会话调用；默认值来自设置页。
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

      <div class="cap-body">
        <!-- 左栏：能力列表 -->
        <aside class="cap-list">
          <div class="cap-group">
            <div class="cap-group-head">
              <span class="cap-group-title">Skill</span>
              <span class="cap-group-count mono">{{ skills.length }}/{{ skillOptions.length }}</span>
              <span class="sec-actions">
                <button type="button" class="link" @click="toggleAll('skill', true)">全选</button>
                <button type="button" class="link" @click="toggleAll('skill', false)">清空</button>
              </span>
            </div>
            <div v-if="!skillOptions.length" class="cap-empty">暂无，可在 设置 → Skill 市场 接入</div>
            <button
              v-for="o in skillOptions" :key="`skill-${o.key}`" type="button"
              class="cap-item" :class="{ active: activeKey === `skill:${o.key}` }"
              @click="activeKey = `skill:${o.key}`"
            >
              <span class="item-dot" :class="{ on: skills.includes(o.key) }" />
              <span class="item-main">
                <span class="item-name mono">{{ o.label }}</span>
                <span class="item-desc">{{ o.description || '（无描述）' }}</span>
              </span>
            </button>
          </div>

          <div class="cap-group">
            <div class="cap-group-head">
              <span class="cap-group-title">MCP</span>
              <span class="cap-group-count mono">{{ mcp.length }}/{{ mcpOptions.length }}</span>
              <span class="sec-actions">
                <button type="button" class="link" @click="toggleAll('mcp', true)">全选</button>
                <button type="button" class="link" @click="toggleAll('mcp', false)">清空</button>
              </span>
            </div>
            <div v-if="!mcpOptions.length" class="cap-empty">暂无，可在 设置 → MCP 市场 接入</div>
            <button
              v-for="o in mcpOptions" :key="`mcp-${o.key}`" type="button"
              class="cap-item" :class="{ active: activeKey === `mcp:${o.key}` }"
              @click="activeKey = `mcp:${o.key}`"
            >
              <span class="item-dot" :class="{ on: mcp.includes(o.key) }" />
              <span class="item-main">
                <span class="item-name mono">{{ o.label }}</span>
                <span class="item-desc">{{ o.description || '（未配置地址）' }}</span>
              </span>
            </button>
          </div>
        </aside>

        <!-- 右栏：选中能力详情 -->
        <section class="cap-detail">
          <template v-if="active">
            <div class="detail-head">
              <span class="detail-badge" :class="active.kind">{{ kindLabel(active.kind) }}</span>
              <span class="detail-name mono">{{ active.label }}</span>
              <el-switch
                :model-value="isOn(active)"
                :active-text="isOn(active) ? '本会话启用' : '本会话停用'"
                @change="(v: string | number | boolean) => toggleActive(Boolean(v))"
              />
            </div>

            <div class="detail-status" :class="{ on: isOn(active) }">
              <span class="status-dot" />
              {{ isOn(active) ? '本轮对话会加载该能力' : '本轮对话不会加载该能力' }}
            </div>

            <dl class="detail-rows">
              <div class="detail-row">
                <dt>类型</dt>
                <dd>{{ kindLabel(active.kind) }}</dd>
              </div>
              <div class="detail-row">
                <dt>标识</dt>
                <dd class="mono">{{ active.key }}</dd>
              </div>
              <div class="detail-row">
                <dt>{{ active.kind === 'mcp' ? '连接' : '说明' }}</dt>
                <dd class="mono">{{ active.description || (active.kind === 'mcp' ? '未配置连接地址' : 'SKILL.md 未填写 description') }}</dd>
              </div>
              <div class="detail-row">
                <dt>默认状态</dt>
                <dd>{{ active.enabled ? '设置页已启用' : '设置页已停用' }}</dd>
              </div>
            </dl>

            <div class="detail-note">
              {{ active.kind === 'mcp'
                ? 'MCP 在每轮对话开始时初始化；连接失败会自动降级跳过，不影响其他工具。'
                : 'Skill 会按你勾选的清单注入本轮系统提示；未勾选的技能不会加载。' }}
            </div>
          </template>

          <div v-else class="detail-empty">
            <el-icon><SetUp /></el-icon>
            <span>{{ ready ? '选择一个能力查看详情' : '加载中…' }}</span>
          </div>
        </section>
      </div>

      <div class="cap-foot">
        这里的选择只影响当前会话；默认启停在 设置 → MCP / Skill 调整。
      </div>
    </div>
  </div>
</template>

<style scoped>
.cap-view {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  padding: 20px 24px 24px;
}
.cap-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  max-width: 1080px;
  min-height: 0;
  margin: 0 auto;
}
.cap-head {
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 16px;
  padding-bottom: 12px;
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
  margin-top: 4px;
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

/* 左右两栏：左侧列表固定宽度，右侧详情自适应 */
.cap-body {
  display: grid;
  flex: 1;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 12px;
  min-height: 0;
}
.cap-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 10px 12px;
  overflow-y: auto;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.cap-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cap-group-head {
  position: sticky;
  top: -10px;
  z-index: 1;
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 0 5px;
  background: var(--bg);
  border-bottom: 1px solid var(--line-soft);
}
.cap-group-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.cap-group-count {
  flex: 1;
  font-size: 10px;
  color: var(--ink-faint);
}
.sec-actions {
  display: flex;
  gap: 7px;
}
.link {
  padding: 0;
  font-size: 10px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
}
.link:hover {
  color: var(--accent);
}
.cap-empty {
  padding: 10px 0;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.cap-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  width: 100%;
  padding: 7px 8px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
  transition: background-color var(--dur-fast) ease;
}
.cap-item:hover {
  background: var(--surface-hover);
}
.cap-item.active {
  background: var(--surface-sunken);
}
.item-dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-top: 5px;
  background: var(--line-strong);
  border-radius: 50%;
}
.item-dot.on {
  background: var(--accent);
}
.item-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}
.item-name {
  overflow: hidden;
  font-size: 11.5px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cap-item.active .item-name {
  font-weight: 600;
}
.item-desc {
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右栏详情 */
.cap-detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
  padding: 16px 18px;
  overflow-y: auto;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.detail-badge {
  flex: none;
  padding: 1px 7px;
  font-size: 10px;
  color: var(--ink-soft);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.detail-badge.mcp {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.detail-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail-status {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 7px 10px;
  font-size: 11.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.detail-status.on {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.status-dot {
  width: 6px;
  height: 6px;
  background: currentcolor;
  border-radius: 50%;
  opacity: 0.55;
}
.detail-status.on .status-dot {
  opacity: 1;
}
.detail-rows {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
}
.detail-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--line-soft);
}
.detail-row:last-child {
  border-bottom: 0;
}
.detail-row dt {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.detail-row dd {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink);
}
.detail-note {
  margin-top: auto;
  padding-top: 10px;
  font-size: 10.5px;
  line-height: 1.65;
  color: var(--ink-faint);
  border-top: 1px solid var(--line-soft);
}
.detail-empty {
  display: flex;
  flex: 1;
  gap: 8px;
  align-items: center;
  justify-content: center;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.cap-foot {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
}

/* 窄屏：改为上下堆叠，仍保持「列表 → 详情」的阅读顺序 */
@media (max-width: 900px) {
  .cap-body {
    grid-template-columns: 1fr;
    overflow-y: auto;
  }
  .cap-list {
    max-height: 260px;
  }
  .cap-detail {
    min-height: 220px;
    overflow: visible;
  }
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
