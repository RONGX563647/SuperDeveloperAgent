<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Connection, MagicStick, Refresh, Search } from '@element-plus/icons-vue'
import type { CapabilityOption } from '../../composables/useChatCapabilities'
import { readSkill } from '../../api/settings'
import MarkdownView from './MarkdownView.vue'

/**
 * 会话能力页（顶部 Tab「能力」）。
 *
 * <p>左侧是带搜索的能力列表，右侧是该能力的完整信息：Skill 直接读取并渲染
 * SKILL.md 正文，MCP 展示启动命令 / 服务地址；顶部开关只影响当前会话。
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

const keyword = ref('')
const activeKey = ref('')
const active = computed(() => items.value.find((i) => keyOf(i) === activeKey.value) ?? null)

function keyOf(item: Item): string {
  return `${item.kind}:${item.key}`
}

function matches(o: CapabilityOption): boolean {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return true
  return `${o.label} ${o.key} ${o.description}`.toLowerCase().includes(q)
}

const filteredSkills = computed(() => props.skillOptions.filter(matches))
const filteredMcp = computed(() => props.mcpOptions.filter(matches))

watch(items, (list) => {
  if (!list.some((i) => keyOf(i) === activeKey.value)) {
    activeKey.value = list.length ? keyOf(list[0]) : ''
  }
}, { immediate: true })

// ---------------------------------------------------------------- SKILL.md 正文

const skillContent = ref('')
const detailLoading = ref(false)
const detailError = ref('')

function stripFrontmatter(md: string): string {
  if (!md.startsWith('---')) return md
  const end = md.indexOf('\n---', 3)
  if (end < 0) return md
  return md.slice(end + 4).replace(/^\s*\n/, '')
}

const skillBody = computed(() => stripFrontmatter(skillContent.value))

const mcpTarget = computed(() => {
  const item = active.value
  if (!item) return ''
  if (item.transport === 'http') return item.url || '未配置服务地址'
  return [item.command, ...(item.args ?? [])].filter(Boolean).join(' ') || '未配置启动命令'
})

watch(active, async (item) => {
  skillContent.value = ''
  detailError.value = ''
  detailLoading.value = false
  if (!item || item.kind !== 'skill') return
  const requested = keyOf(item)
  detailLoading.value = true
  try {
    const r = await readSkill(item.key)
    if (active.value && keyOf(active.value) === requested) skillContent.value = r.content
  } catch (e) {
    if (active.value && keyOf(active.value) === requested) {
      detailError.value = e instanceof Error ? e.message : '读取 SKILL.md 失败'
    }
  } finally {
    detailLoading.value = false
  }
}, { immediate: true })

// ---------------------------------------------------------------- 选择与展示

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

function formatTime(ts?: number): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  })
}
</script>

<template>
  <div class="cap-view">
    <div class="cap-shell">
      <header class="cap-head">
        <div class="cap-head-main">
          <h1 class="cap-title">本会话能力</h1>
          <p class="cap-desc">
            左侧选择 Skill / MCP，右侧查看完整说明；只有勾选的能力会进入本轮对话。
          </p>
        </div>
        <div class="cap-head-side">
          <span class="cap-stat">
            <b class="mono">{{ ready ? count : '…' }}</b>
            <span>项已启用</span>
          </span>
          <el-button size="small" :loading="loading" @click="emit('refresh')">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </header>

      <div class="cap-body">
        <!-- 左栏：搜索 + 分组列表 -->
        <aside class="cap-list">
          <div class="cap-search">
            <el-input v-model="keyword" size="small" clearable placeholder="搜索 Skill / MCP">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </div>

          <div class="cap-scroll">
            <section class="cap-group">
              <div class="group-head">
                <span class="group-icon skill"><el-icon><MagicStick /></el-icon></span>
                <span class="group-title">Skill</span>
                <span class="group-count mono">{{ skills.length }}/{{ skillOptions.length }}</span>
                <span class="group-actions">
                  <button type="button" class="link" @click="toggleAll('skill', true)">全选</button>
                  <button type="button" class="link" @click="toggleAll('skill', false)">清空</button>
                </span>
              </div>
              <div v-if="!skillOptions.length" class="group-empty">可在 设置 → Skill 市场 接入</div>
              <div v-else-if="!filteredSkills.length" class="group-empty">没有匹配的 Skill</div>
              <button
                v-for="o in filteredSkills" :key="`skill-${o.key}`" type="button"
                class="cap-item" :class="{ active: activeKey === `skill:${o.key}` }"
                @click="activeKey = `skill:${o.key}`"
              >
                <span class="item-main">
                  <span class="item-name">{{ o.label }}</span>
                  <span class="item-desc">{{ o.description || '（无描述）' }}</span>
                </span>
                <span v-if="skills.includes(o.key)" class="item-on" title="本会话已启用" />
              </button>
            </section>

            <section class="cap-group">
              <div class="group-head">
                <span class="group-icon mcp"><el-icon><Connection /></el-icon></span>
                <span class="group-title">MCP</span>
                <span class="group-count mono">{{ mcp.length }}/{{ mcpOptions.length }}</span>
                <span class="group-actions">
                  <button type="button" class="link" @click="toggleAll('mcp', true)">全选</button>
                  <button type="button" class="link" @click="toggleAll('mcp', false)">清空</button>
                </span>
              </div>
              <div v-if="!mcpOptions.length" class="group-empty">可在 设置 → MCP 市场 接入</div>
              <div v-else-if="!filteredMcp.length" class="group-empty">没有匹配的 MCP</div>
              <button
                v-for="o in filteredMcp" :key="`mcp-${o.key}`" type="button"
                class="cap-item" :class="{ active: activeKey === `mcp:${o.key}` }"
                @click="activeKey = `mcp:${o.key}`"
              >
                <span class="item-main">
                  <span class="item-name">{{ o.label }}</span>
                  <span class="item-desc">{{ o.description || '（未配置地址）' }}</span>
                </span>
                <span v-if="mcp.includes(o.key)" class="item-on" title="本会话已启用" />
              </button>
            </section>
          </div>
        </aside>

        <!-- 右栏：选中能力的完整信息 -->
        <section class="cap-detail">
          <template v-if="active">
            <div class="hero">
              <span class="hero-icon" :class="active.kind">
                <el-icon><component :is="active.kind === 'skill' ? MagicStick : Connection" /></el-icon>
              </span>
              <div class="hero-main">
                <div class="hero-eyebrow">
                  <span class="type-pill" :class="active.kind">{{ kindLabel(active.kind) }}</span>
                  <span class="hero-key mono">{{ active.key }}</span>
                </div>
                <h2 class="hero-title">{{ active.label }}</h2>
                <p class="hero-desc">{{ active.description || '暂无描述' }}</p>
                <div class="chips">
                  <span v-if="active.kind === 'skill'" class="chip">{{ active.fileCount ?? 0 }} 个文件</span>
                  <span v-if="active.kind === 'skill' && active.updatedAt" class="chip">
                    更新 {{ formatTime(active.updatedAt) }}
                  </span>
                  <span v-if="active.kind === 'mcp'" class="chip">
                    {{ active.transport === 'http' ? 'HTTP 服务' : 'stdio 进程' }}
                  </span>
                  <span class="chip" :class="{ on: active.enabled }">
                    {{ active.enabled ? '设置页默认启用' : '设置页默认停用' }}
                  </span>
                </div>
              </div>
              <div class="hero-switch">
                <el-switch
                  :model-value="isOn(active)" size="large"
                  @change="(v: string | number | boolean) => toggleActive(Boolean(v))"
                />
                <span class="switch-label" :class="{ on: isOn(active) }">
                  {{ isOn(active) ? '本会话已启用' : '本会话未启用' }}
                </span>
              </div>
            </div>

            <div class="detail-block" :class="{ compact: active.kind === 'mcp' }">
              <div class="block-head">
                <span class="block-title">{{ active.kind === 'skill' ? 'SKILL.md' : '连接方式' }}</span>
                <span class="block-sub mono">
                  {{ active.kind === 'skill' ? `${active.key}/SKILL.md` : (active.transport ?? 'stdio') }}
                </span>
              </div>

              <template v-if="active.kind === 'skill'">
                <div v-if="detailLoading" class="block-empty">读取中…</div>
                <div v-else-if="detailError" class="block-empty error">{{ detailError }}</div>
                <div v-else-if="!skillBody" class="block-empty">SKILL.md 为空</div>
                <div v-else class="skill-content">
                  <MarkdownView :content="skillBody" />
                </div>
              </template>

              <pre v-else class="conn mono">{{ mcpTarget }}</pre>
            </div>

            <div v-if="active.kind === 'mcp'" class="meta-grid">
              <div class="meta-item">
                <span>类型</span><b>MCP</b>
              </div>
              <div class="meta-item">
                <span>标识</span><b class="mono">{{ active.key }}</b>
              </div>
              <div class="meta-item">
                <span>传输</span><b>{{ active.transport === 'http' ? 'HTTP' : 'stdio' }}</b>
              </div>
              <div class="meta-item">
                <span>设置页默认</span><b>{{ active.enabled ? '启用' : '停用' }}</b>
              </div>
              <div v-if="active.headerNames?.length" class="meta-item">
                <span>请求头</span><b class="mono">{{ active.headerNames.join(', ') }}</b>
              </div>
            </div>

            <p class="detail-note">
              {{ active.kind === 'mcp'
                ? '每轮对话开始时初始化 MCP；连接失败会自动降级跳过，不影响其他工具。'
                : '勾选后按 SKILL.md 的指令注入本轮系统提示；未勾选的技能不会加载。' }}
            </p>
          </template>

          <div v-else class="detail-empty">
            <span class="empty-icon"><el-icon><MagicStick /></el-icon></span>
            <span>{{ ready ? '从左侧选择一个能力' : '正在加载能力…' }}</span>
          </div>
        </section>
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
  gap: 14px;
  width: 100%;
  max-width: 1120px;
  min-height: 0;
  margin: 0 auto;
}

/* 页头 */
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
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.cap-desc {
  margin: 4px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.cap-head-side {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
}
.cap-stat {
  display: flex;
  gap: 4px;
  align-items: baseline;
  color: var(--ink-faint);
}
.cap-stat b {
  font-size: 18px;
  font-weight: 650;
  color: var(--ink);
}
.cap-stat span {
  font-size: 10.5px;
}

/* 左右两栏 */
.cap-body {
  display: grid;
  flex: 1;
  grid-template-columns: 286px minmax(0, 1fr);
  gap: 14px;
  min-height: 0;
}
.cap-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}
.cap-search {
  flex: none;
  padding: 9px 10px;
  border-bottom: 1px solid var(--line-soft);
}
.cap-scroll {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 8px 8px 10px;
  overflow-y: auto;
}
.cap-group {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.group-head {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 5px 4px 6px;
}
.group-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 17px;
  height: 17px;
  font-size: 10px;
  border-radius: 5px;
}
.group-icon.skill {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.group-icon.mcp {
  color: var(--ink-soft);
  background: var(--surface-sunken);
}
.group-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.group-count {
  flex: 1;
  font-size: 10px;
  color: var(--ink-faint);
}
.group-actions {
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
.group-empty {
  padding: 7px 6px;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.cap-item {
  position: relative;
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 7px 8px 7px 10px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 7px;
  transition: background-color 0.12s ease, box-shadow 0.12s ease;
}
.cap-item:hover {
  background: var(--surface-hover);
}
.cap-item.active {
  background: var(--accent-soft);
  box-shadow: inset 2px 0 var(--accent);
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
.item-on {
  flex: none;
  width: 6px;
  height: 6px;
  background: var(--accent);
  border-radius: 50%;
}

/* 右栏详情 */
.cap-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  min-height: 0;
  padding: 18px 20px;
  overflow-y: auto;
  background: linear-gradient(180deg, var(--surface-raised), var(--bg) 160px);
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}
.hero {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.hero-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  font-size: 19px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: 12px;
}
.hero-icon.mcp {
  color: var(--ink-soft);
  background: var(--surface-sunken);
}
.hero-main {
  flex: 1;
  min-width: 0;
}
.hero-eyebrow {
  display: flex;
  gap: 7px;
  align-items: center;
}
.type-pill {
  padding: 1px 7px;
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.type-pill.mcp {
  color: var(--ink-soft);
  background: var(--surface-sunken);
}
.hero-key {
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hero-title {
  margin: 3px 0 0;
  overflow: hidden;
  font-size: 20px;
  font-weight: 650;
  letter-spacing: -0.015em;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hero-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 9px;
}
.chip {
  padding: 2px 8px;
  font-size: 10px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.chip.on {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.hero-switch {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 4px;
  align-items: flex-end;
}
.switch-label {
  font-size: 10px;
  color: var(--ink-faint);
}
.switch-label.on {
  color: var(--accent-ink);
}

/* SKILL.md / 连接方式 */
.detail-block {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 140px;
  overflow: hidden;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: 9px;
}
/* MCP 连接信息通常只有一行，不再撑满整栏 */
.detail-block.compact {
  flex: 0 0 auto;
  min-height: 0;
}
.block-head {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: baseline;
  padding: 9px 13px;
  background: var(--surface-sunken);
  border-bottom: 1px solid var(--line-soft);
}
.block-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.block-sub {
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.block-empty {
  padding: 16px 13px;
  font-size: 11px;
  color: var(--ink-faint);
}
.block-empty.error {
  color: var(--danger, #c45656);
}
.skill-content {
  flex: 1;
  max-height: 46vh;
  padding: 14px 16px;
  overflow-y: auto;
}
.skill-content :deep(.markdown-view) {
  font-size: 12.5px;
  line-height: 1.75;
}
.skill-content :deep(.markdown-view h1) {
  font-size: 16px;
}
.skill-content :deep(.markdown-view h2) {
  font-size: 14.5px;
}
.skill-content :deep(.markdown-view h3) {
  font-size: 13px;
}
.skill-content :deep(.markdown-view pre) {
  font-size: 11.5px;
}
.conn {
  flex: 1;
  padding: 14px 16px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.meta-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px;
}
.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 7px 10px;
  background: var(--surface-sunken);
  border-radius: 7px;
}
.meta-item span {
  font-size: 10px;
  color: var(--ink-faint);
}
.meta-item b {
  overflow: hidden;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail-note {
  margin: 0;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.detail-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  font-size: 17px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 12px;
}

/* 窄屏：列表在上，详情在下 */
@media (max-width: 900px) {
  .cap-body {
    grid-template-columns: 1fr;
    overflow-y: auto;
  }
  .cap-list {
    max-height: 300px;
  }
  .cap-detail {
    overflow: visible;
  }
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
