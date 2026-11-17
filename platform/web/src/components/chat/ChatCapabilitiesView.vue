<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown, ArrowRight, Connection, MagicStick, Refresh, Search, Setting, Star, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { saveMcpServer, setSkillEnabled } from '../../api/settings'
import type { CapabilityOption } from '../../composables/useChatCapabilities'

/**
 * 会话能力页（顶部 Tab「能力」）。
 *
 * <p>只回答一个问题：**这一轮对话，Agent 能用哪些 Skill / MCP？**
 * 所以这里是一张「开关清单」而不是市场：每行一个能力（名称 + 什么时候会用 + 开关），
 * 按 Skill / MCP 分两张卡；不可用的连接态标出来，避免"开了但用不了"。
 * 会话选择与设置页默认互不影响，开关立即用于下一轮请求。
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

const router = useRouter()

const skillOn = computed(() => props.skills.length)
const mcpOn = computed(() => props.mcp.length)
const total = computed(() => props.skillOptions.length + props.mcpOptions.length)

/** 已启用项会整段进系统提示词；超过这个数量给一句成本提醒。 */
const noisy = computed(() => skillOn.value + mcpOn.value > 10)

// ---------------------------------------------------------------- 搜索 / 筛选 / 排序

const keyword = ref('')
const filter = ref<'all' | 'on' | 'off'>('all')
const sortBy = ref<'default' | 'name'>('default')

function isOn(kind: 'skill' | 'mcp', key: string): boolean {
  return (kind === 'skill' ? props.skills : props.mcp).includes(key)
}

function toggle(kind: 'skill' | 'mcp', key: string, on: boolean): void {
  const current = kind === 'skill' ? props.skills : props.mcp
  const next = on ? [...current, key] : current.filter((k) => k !== key)
  if (kind === 'skill') emit('update:skills', next)
  else emit('update:mcp', next)
}

function toggleAll(kind: 'skill' | 'mcp', on: boolean): void {
  const list = kind === 'skill' ? props.skillOptions : props.mcpOptions
  const next = on ? list.map((o) => o.key) : []
  if (kind === 'skill') emit('update:skills', next)
  else emit('update:mcp', next)
}

function visible(list: CapabilityOption[], kind: 'skill' | 'mcp'): CapabilityOption[] {
  const q = keyword.value.trim().toLowerCase()
  return list
    .filter((option) => {
      if (filter.value === 'on' && !isOn(kind, option.key)) return false
      if (filter.value === 'off' && isOn(kind, option.key)) return false
      if (!q) return true
      return `${option.label} ${option.description} ${option.key}`
        .toLowerCase()
        .includes(q)
    })
    .sort((a, b) => {
      if (sortBy.value === 'name') return a.label.localeCompare(b.label, 'zh-CN')
      // 默认排序：已启用的置顶，其余保持后端顺序，找「这轮开了什么」不用滚动
      const onA = isOn(kind, a.key) ? 0 : 1
      const onB = isOn(kind, b.key) ? 0 : 1
      return onA - onB
    })
}

const filteredSkills = computed(() => visible(props.skillOptions, 'skill'))
const filteredMcp = computed(() => visible(props.mcpOptions, 'mcp'))

/** 两张能力卡可折叠：能力多时先收起不关心的分组，避免一直滚动。 */
const collapsed = ref({ skill: false, mcp: false })

function toggleGroup(kind: 'skill' | 'mcp'): void {
  collapsed.value = { ...collapsed.value, [kind]: !collapsed.value[kind] }
}

// ---------------------------------------------------------------- 成本估算

/** 描述注入的粗略 token 成本（中文按 2.5 字符 ≈ 1 token）。 */
function tokenOf(option: CapabilityOption): number {
  const length = (option.description ?? '').length
  return length ? Math.max(1, Math.ceil(length / 2.5)) : 0
}

const skillTokens = computed(() => props.skillOptions
  .filter((option) => isOn('skill', option.key))
  .reduce((sum, option) => sum + tokenOf(option), 0))

// ---------------------------------------------------------------- 场景预设

interface Preset {
  name: string
  skills: string[]
  mcp: string[]
}

const PRESET_KEY = 'agent.capability-presets.v1'
const savedPresets = ref<Preset[]>(readPresets())

function readPresets(): Preset[] {
  try {
    const raw = localStorage.getItem(PRESET_KEY)
    const parsed = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function persistPresets(): void {
  try {
    localStorage.setItem(PRESET_KEY, JSON.stringify(savedPresets.value))
  } catch {
    // 本地存储不可用时只保留内存态
  }
}

const PRESET_RULES: { name: string; words: string[] }[] = [
  { name: '前端', words: ['前端', 'vue', 'react', 'ui', 'css', 'web', 'front'] },
  { name: '后端', words: ['后端', 'java', 'api', 'sql', '数据库', 'spring'] },
  { name: '文档', words: ['文档', '写作', 'doc', 'readme', 'markdown'] },
  { name: '发布', words: ['发布', '部署', 'pipeline', 'ci', 'deploy', 'docker'] },
]

function matchedKeys(list: CapabilityOption[], words: string[]): string[] {
  return list
    .filter((option) => {
      const text = `${option.label} ${option.description}`.toLowerCase()
      return words.some((word) => text.includes(word))
    })
    .map((option) => option.key)
}

/** 按当前已安装能力生成「前端 / 后端 / 文档 / 发布」推荐组合。 */
const recommendedPresets = computed(() => PRESET_RULES
  .map((rule) => ({
    name: rule.name,
    skills: matchedKeys(props.skillOptions, rule.words),
    mcp: matchedKeys(props.mcpOptions, rule.words),
  }))
  .filter((preset) => preset.skills.length || preset.mcp.length))

function applyPreset(preset: Preset): void {
  const skillKeys = new Set(props.skillOptions.map((option) => option.key))
  const mcpKeys = new Set(props.mcpOptions.map((option) => option.key))
  emit('update:skills', preset.skills.filter((key) => skillKeys.has(key)))
  emit('update:mcp', preset.mcp.filter((key) => mcpKeys.has(key)))
  ElMessage.success(`已应用「${preset.name}」`)
}

function savePreset(): void {
  savedPresets.value = [
    ...savedPresets.value,
    {
      name: `组合 ${savedPresets.value.length + 1}`,
      skills: [...props.skills],
      mcp: [...props.mcp],
    },
  ]
  persistPresets()
  ElMessage.success('已保存当前组合')
}

function removePreset(index: number): void {
  savedPresets.value = savedPresets.value.filter((_, i) => i !== index)
  persistPresets()
}

// ---------------------------------------------------------------- 设为默认

const savingDefault = ref(false)

/** 把当前会话选择写回设置页默认值（Skill 直接改启用位，MCP 保留已有 headers）。 */
async function saveAsDefault(): Promise<void> {
  savingDefault.value = true
  try {
    const calls: Promise<unknown>[] = []
    for (const option of props.skillOptions) {
      if (option.enabled !== isOn('skill', option.key)) {
        calls.push(setSkillEnabled(option.key, isOn('skill', option.key)))
      }
    }
    for (const option of props.mcpOptions) {
      if (option.id && option.enabled !== isOn('mcp', option.key)) {
        calls.push(saveMcpServer({
          id: option.id,
          name: option.label,
          transport: option.transport ?? 'stdio',
          command: option.command,
          args: (option.args ?? []).join('\n'),
          url: option.url,
          enabled: isOn('mcp', option.key),
        }))
      }
    }
    await Promise.all(calls)
    ElMessage.success('已保存为默认能力')
    emit('refresh')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存默认能力失败')
  } finally {
    savingDefault.value = false
  }
}

/** MCP 的"它是什么"：stdin 命令或 HTTP 地址，一行说清。 */
function mcpTarget(option: CapabilityOption): string {
  if (option.transport === 'http') return option.url ?? 'http'
  return [option.command, ...(option.args ?? [])].filter(Boolean).join(' ')
}

function goSettings(): void {
  void router.push('/settings?section=skills')
}
</script>

<template>
  <div class="cap-view">
    <div class="cap-page">
      <header class="cap-head">
        <div class="cap-head-text">
          <h1>本会话能力</h1>
          <p>
            开关只影响当前会话，下一轮对话立即生效；设置页的默认值不会被改动。
          </p>
        </div>
        <button type="button" class="icon-btn" title="重新读取 Skill / MCP 列表" @click="emit('refresh')">
          <el-icon :class="{ spin: loading }"><Refresh /></el-icon>
        </button>
      </header>

      <div class="cap-summary">
        <span class="sum-item">
          <el-icon><MagicStick /></el-icon>
          Skill <b class="mono">{{ ready ? `${skillOn}/${skillOptions.length}` : '…' }}</b>
        </span>
        <span class="sum-item">
          <el-icon><Connection /></el-icon>
          MCP <b class="mono">{{ ready ? `${mcpOn}/${mcpOptions.length}` : '…' }}</b>
        </span>
        <span class="sum-note">
          已启用 {{ ready ? skillOn + mcpOn : '…' }} 项
          <template v-if="ready">· Skill 描述约 <b class="mono">{{ skillTokens }}</b> tokens</template>
          <template v-if="noisy">· 偏多，首字延迟会变长</template>
        </span>
      </div>

      <div class="cap-toolbar">
        <label class="cap-search">
          <el-icon><Search /></el-icon>
          <input v-model="keyword" type="search" placeholder="搜索 Skill / MCP" aria-label="搜索能力">
        </label>
        <div class="cap-seg" role="group" aria-label="启用状态筛选">
          <button type="button" :class="{ active: filter === 'all' }" @click="filter = 'all'">全部</button>
          <button type="button" :class="{ active: filter === 'on' }" @click="filter = 'on'">已启用</button>
          <button type="button" :class="{ active: filter === 'off' }" @click="filter = 'off'">未启用</button>
        </div>
        <select v-model="sortBy" class="cap-sort" aria-label="排序方式">
          <option value="default">已启用优先</option>
          <option value="name">按名称</option>
        </select>
        <button type="button" class="cap-default" :disabled="savingDefault || !ready" @click="saveAsDefault">
          <el-icon><Star /></el-icon>{{ savingDefault ? '保存中…' : '设为默认' }}
        </button>
      </div>

      <div v-if="ready" class="cap-presets">
        <span class="preset-label">场景</span>
        <button
          v-for="preset in recommendedPresets" :key="`rec-${preset.name}`"
          type="button" class="preset" @click="applyPreset(preset)"
        >{{ preset.name }}</button>
        <button
          v-for="(preset, index) in savedPresets" :key="`saved-${index}`"
          type="button" class="preset saved" @click="applyPreset(preset)"
        >
          {{ preset.name }}
          <span class="preset-x" title="删除组合" @click.stop="removePreset(index)">×</span>
        </button>
        <button type="button" class="preset add" @click="savePreset">+ 存为组合</button>
      </div>

      <p v-if="ready && noisy" class="cap-warn">
        <el-icon><Warning /></el-icon>
        启用越多，系统提示词越长、首字延迟越高。只保留这轮真正需要的就好。
      </p>

      <div v-if="ready && !total" class="cap-empty">
        <p>还没有可用的能力。</p>
        <button type="button" class="link" @click="goSettings">
          <el-icon><Setting /></el-icon>去设置页添加 Skill / MCP
        </button>
      </div>

      <template v-else>
        <!-- Skill：每个目录一份说明，按需加载 -->
        <section v-if="skillOptions.length" class="cap-card">
          <header class="card-head">
            <button
              type="button" class="card-fold" :aria-expanded="!collapsed.skill"
              :title="collapsed.skill ? '展开技能列表' : '收起技能列表'"
              @click="toggleGroup('skill')"
            >
              <el-icon><ArrowRight v-if="collapsed.skill" /><ArrowDown v-else /></el-icon>
            </button>
            <span class="card-icon skill"><el-icon><MagicStick /></el-icon></span>
            <span class="card-title">
              <b>Skill <span class="card-on mono">{{ skillOn }}</span></b>
              <em>按需读取的知识包，命中相关任务时才会展开</em>
            </span>
            <span class="card-actions">
              <span class="mono">{{ skillOn }}/{{ skillOptions.length }}</span>
              <button
                type="button" class="link"
                :disabled="skillOn === skillOptions.length"
                @click="toggleAll('skill', true)"
              >启用全部</button>
              <button
                type="button" class="link"
                :disabled="!skillOn" @click="toggleAll('skill', false)"
              >全部停用</button>
            </span>
          </header>
          <ul v-show="!collapsed.skill" class="cap-rows">
            <li
              v-for="option in filteredSkills" :key="`skill-${option.key}`"
              class="cap-row" :class="{ on: isOn('skill', option.key) }"
            >
              <span class="row-main">
                <span class="row-name">
                  {{ option.label }}
                  <span v-if="option.fileCount" class="row-tag mono">{{ option.fileCount }} 文件</span>
                  <span class="row-tag mono" title="描述注入的粗略成本">~{{ tokenOf(option) }}t</span>
                </span>
                <span class="row-desc" :title="option.description">{{ option.description || '（Skill 未写描述，模型只能靠名称判断用途）' }}</span>
              </span>
              <el-switch
                :model-value="isOn('skill', option.key)"
                size="small"
                :aria-label="`本次对话启用 ${option.label}`"
                @change="(v: string | number | boolean) => toggle('skill', option.key, Boolean(v))"
              />
            </li>
            <li v-if="!filteredSkills.length" class="row-empty">没有匹配的技能</li>
          </ul>
        </section>

        <!-- MCP：外部工具服务，启用后会多出一批工具 -->
        <section v-if="mcpOptions.length" class="cap-card">
          <header class="card-head">
            <button
              type="button" class="card-fold" :aria-expanded="!collapsed.mcp"
              :title="collapsed.mcp ? '展开 MCP 列表' : '收起 MCP 列表'"
              @click="toggleGroup('mcp')"
            >
              <el-icon><ArrowRight v-if="collapsed.mcp" /><ArrowDown v-else /></el-icon>
            </button>
            <span class="card-icon mcp"><el-icon><Connection /></el-icon></span>
            <span class="card-title">
              <b>MCP <span class="card-on mono">{{ mcpOn }}</span></b>
              <em>外部工具服务，启用后工具会出现在本轮可调用列表里</em>
            </span>
            <span class="card-actions">
              <span class="mono">{{ mcpOn }}/{{ mcpOptions.length }}</span>
              <button
                type="button" class="link"
                :disabled="mcpOn === mcpOptions.length"
                @click="toggleAll('mcp', true)"
              >启用全部</button>
              <button
                type="button" class="link" :disabled="!mcpOn" @click="toggleAll('mcp', false)"
              >全部停用</button>
            </span>
          </header>
          <ul v-show="!collapsed.mcp" class="cap-rows">
            <li
              v-for="option in filteredMcp" :key="`mcp-${option.key}`"
              class="cap-row" :class="{ on: isOn('mcp', option.key) }"
            >
              <span class="row-main">
                <span class="row-name">
                  {{ option.label }}
                  <span class="row-tag mono">{{ option.transport === 'http' ? 'HTTP' : 'stdio' }}</span>
                </span>
                <span class="row-desc" :title="option.description">{{ mcpTarget(option) || option.description }}</span>
              </span>
              <el-switch
                :model-value="isOn('mcp', option.key)"
                size="small"
                :aria-label="`本次对话启用 ${option.label}`"
                @change="(v: string | number | boolean) => toggle('mcp', option.key, Boolean(v))"
              />
            </li>
            <li v-if="!filteredMcp.length" class="row-empty">没有匹配的 MCP 服务</li>
          </ul>
        </section>
      </template>

      <p class="cap-foot">
        想改默认值或新增能力，去
        <button type="button" class="link" @click="goSettings">设置 · Skill / MCP</button>
        ；这里的选择按会话单独保存。
      </p>
    </div>
  </div>
</template>

<style scoped>
.cap-view {
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  background: var(--surface-sunken);
}

.cap-page {
  width: min(100%, 48rem);
  padding: var(--sp-6) var(--page-gutter) var(--sp-10);
  margin: 0 auto;
}

/* ---- 页头 ---- */
.cap-head {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-4);
  margin-bottom: var(--sp-4);
}

.cap-head-text h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--ink);
}

.cap-head-text p {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink-muted);
}

.cap-head .icon-btn {
  margin-left: auto;
}

.spin {
  animation: cap-spin 1s linear infinite;
}

@keyframes cap-spin {
  to {
    transform: rotate(360deg);
  }
}

/* ---- 计数条 ---- */
.cap-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2) var(--sp-4);
  padding: 8px 12px;
  margin-bottom: var(--sp-4);
  font-size: 12px;
  color: var(--ink-muted);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}

.sum-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--ink-soft);
}

.sum-item b {
  font-weight: 600;
  color: var(--ink);
}

.sum-note {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--ink-faint);
}

.cap-warn {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 8px 12px;
  margin: 0 0 var(--sp-4);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-muted);
  background: var(--warn-faint, var(--surface-raised));
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}

/* ---- 搜索 / 筛选 ---- */
.cap-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}

.cap-search {
  display: flex;
  flex: 1 1 180px;
  align-items: center;
  gap: 6px;
  min-width: 150px;
  padding: 0 10px;
  color: var(--ink-faint);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}

.cap-search input {
  flex: 1;
  min-width: 0;
  height: 30px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: none;
  border: 0;
  outline: none;
}

.cap-seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  background: var(--surface-hover);
  border-radius: var(--radius-md);
}

.cap-seg button {
  padding: 4px 9px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 5px;
}

.cap-seg button.active {
  color: var(--ink);
  background: var(--surface-raised);
  box-shadow: 0 1px 2px rgb(15 23 42 / 8%);
}

.cap-sort {
  height: 30px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}

.cap-default {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  height: 30px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: var(--accent-soft);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
}

.cap-default:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

/* ---- 场景预设 ---- */
.cap-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-bottom: var(--sp-4);
}

.preset-label {
  font-size: 11px;
  color: var(--ink-faint);
}

.preset {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 3px 9px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 999px;
}

.preset:hover {
  color: var(--accent-ink);
  border-color: var(--accent-soft);
}

.preset.saved {
  color: var(--ink-soft);
  background: var(--surface-hover);
}

.preset.add {
  color: var(--ink-faint);
  border-style: dashed;
}

.preset-x {
  padding: 0 2px;
  font-size: 12px;
  color: var(--ink-faint);
}

.preset-x:hover {
  color: var(--danger);
}

.row-empty {
  padding: 14px;
  font-size: 11.5px;
  color: var(--ink-faint);
  text-align: center;
}

/* ---- 能力卡 ---- */
.cap-card {
  margin-bottom: var(--sp-4);
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  font-size: 14px;
  border-radius: var(--radius-sm);
}

/* 分组折叠：只影响视觉，开关与筛选状态不变 */
.card-fold {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  font-size: 11px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}

.card-fold:hover {
  color: var(--ink);
  background: var(--surface-hover);
}

.card-on {
  padding: 0 5px;
  font-size: 10.5px;
  font-weight: 600;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: 999px;
}

.card-icon.skill {
  color: var(--accent-ink);
  background: var(--accent-soft);
}

.card-icon.mcp {
  color: var(--ink-soft);
  background: var(--surface-hover);
}

.card-title {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.card-title b {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}

.card-title em {
  font-size: 11.5px;
  font-style: normal;
  color: var(--ink-faint);
}

.card-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  font-size: 11.5px;
  color: var(--ink-faint);
}

.link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
}

.link:hover {
  text-decoration: underline;
}

.link:disabled {
  color: var(--ink-faint);
  cursor: not-allowed;
  text-decoration: none;
}

.cap-rows {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
}

.cap-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-top: 1px solid var(--line-soft);
}

.cap-row:first-child {
  border-top: 0;
}

.cap-row:hover {
  background: var(--surface-hover);
}

.row-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.row-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-soft);
}

.cap-row.on .row-name {
  color: var(--ink);
}

.row-tag {
  padding: 1px 5px;
  font-size: 10px;
  font-weight: 400;
  color: var(--ink-faint);
  background: var(--surface-hover);
  border-radius: 4px;
}

.row-desc {
  overflow: hidden;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.cap-foot {
  margin: var(--sp-4) 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}

/* ---- 空态 ---- */
.cap-empty {
  padding: 32px 16px;
  text-align: center;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
}

.cap-empty p {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--ink-muted);
}

@media (max-width: 640px) {
  .cap-page {
    padding: var(--sp-4) var(--page-gutter-tight) var(--sp-8);
  }

  .sum-note {
    margin-left: 0;
  }

  .row-desc {
    white-space: normal;
  }
}
</style>
