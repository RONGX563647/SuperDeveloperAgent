<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowDown, ArrowRight, Box, Connection, MagicStick, Refresh, Search,
  Setting, Star, Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { saveMcpServer, setSkillEnabled } from '../../api/settings'
import type { BuiltinToolOption, CapabilityOption } from '../../composables/useChatCapabilities'
import { orderCapabilities } from './capabilityOrder'

/**
 * 会话能力页（顶部 Tab「能力」）。
 *
 * 一页回答三个问题：
 * 1. 本 Agent 自带哪些工具（内置工具，只读，随版本发布）——最上面、永远有内容；
 * 2. 这轮对话开了哪些 Skill（按需读取的知识包，可开关）；
 * 3. 这轮对话开了哪些 MCP（外部工具服务，可开关）。
 *
 * 内置工具来自真实 Toolkit 投影（AgentToolCatalog），不是另写的清单；
 * 会话选择与设置页默认互不影响，开关立即用于下一轮请求。
 */
const props = defineProps<{
  toolOptions: BuiltinToolOption[]
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
const toolCount = computed(() => props.toolOptions.length)
const readOnlyCount = computed(() => props.toolOptions.filter((t) => t.readOnly).length)

/** 已启用项会整段进系统提示词；超过这个数量给一句成本提醒。 */
const noisy = computed(() => skillOn.value + mcpOn.value > 10)

// ---------------------------------------------------------------- 全局搜索

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
  const filtered = list.filter((option) => {
    if (filter.value === 'on' && !isOn(kind, option.key)) return false
    if (filter.value === 'off' && isOn(kind, option.key)) return false
    if (!q) return true
    return `${option.label} ${option.description} ${option.key}`
      .toLowerCase()
      .includes(q)
  })
  return orderCapabilities(filtered, (key) => isOn(kind, key), sortBy.value)
}

const filteredSkills = computed(() => visible(props.skillOptions, 'skill'))
const filteredMcp = computed(() => visible(props.mcpOptions, 'mcp'))

/** 两张能力卡可折叠：能力多时先收起不关心的分组。 */
const collapsed = ref({ tool: false, skill: false, mcp: false })

function toggleGroup(kind: 'tool' | 'skill' | 'mcp'): void {
  collapsed.value = { ...collapsed.value, [kind]: !collapsed.value[kind] }
}

// ---------------------------------------------------------------- 内置工具筛选

/** 从真实数据里提取工具面及数量，避免硬编码不存在的分组。 */
const toolFaces = computed(() => {
  const counts = new Map<string, number>()
  for (const tool of props.toolOptions) {
    for (const face of tool.toolkits) {
      counts.set(face, (counts.get(face) ?? 0) + 1)
    }
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1])
})

const toolFace = ref('all')
const toolExpanded = ref(false)

const filteredTools = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return props.toolOptions.filter((tool) => {
    if (toolFace.value !== 'all' && !tool.toolkits.includes(toolFace.value)) return false
    if (!q) return true
    return `${tool.name} ${tool.description}`.toLowerCase().includes(q)
  })
})

/** 默认只显示前 24 个，避免 90 个工具一屏铺满；搜索/筛选时也先收起。 */
const TOOL_PREVIEW = 24
const visibleTools = computed(() =>
  (toolExpanded.value || keyword.value.trim()) ? filteredTools.value : filteredTools.value.slice(0, TOOL_PREVIEW))

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
          <p>内置工具随 Agent 发布；Skill / MCP 的开关只影响当前会话，下一轮对话立即生效。</p>
        </div>
        <button type="button" class="icon-btn" title="重新读取能力列表" @click="emit('refresh')">
          <el-icon :class="{ spin: loading }"><Refresh /></el-icon>
        </button>
      </header>

      <!-- 概览：三类能力一眼看清，点击卡片滚动到对应分区 -->
      <div class="cap-overview">
        <button type="button" class="ov-card tools" @click="collapsed.tool && toggleGroup('tool')">
          <span class="ov-icon"><el-icon><Box /></el-icon></span>
          <span class="ov-body">
            <span class="ov-num mono">{{ ready ? toolCount : '…' }}</span>
            <span class="ov-label">内置工具</span>
          </span>
          <span class="ov-sub">{{ ready ? `${readOnlyCount} 个只读` : '读取中' }}</span>
        </button>
        <button type="button" class="ov-card skills" @click="collapsed.skill && toggleGroup('skill')">
          <span class="ov-icon"><el-icon><MagicStick /></el-icon></span>
          <span class="ov-body">
            <span class="ov-num mono">{{ ready ? `${skillOn}/${skillOptions.length}` : '…' }}</span>
            <span class="ov-label">Skill 已启用</span>
          </span>
          <span class="ov-sub">按需加载的知识包</span>
        </button>
        <button type="button" class="ov-card mcp" @click="collapsed.mcp && toggleGroup('mcp')">
          <span class="ov-icon"><el-icon><Connection /></el-icon></span>
          <span class="ov-body">
            <span class="ov-num mono">{{ ready ? `${mcpOn}/${mcpOptions.length}` : '…' }}</span>
            <span class="ov-label">MCP 已启用</span>
          </span>
          <span class="ov-sub">外部工具服务</span>
        </button>
      </div>

      <!-- 工具栏：搜索同时作用于三类；筛选只作用于 Skill/MCP -->
      <div class="cap-toolbar">
        <label class="cap-search">
          <el-icon><Search /></el-icon>
          <input v-model="keyword" type="search" placeholder="搜索工具 / Skill / MCP" aria-label="搜索能力">
        </label>
        <div class="cap-seg" role="group" aria-label="启用状态筛选（Skill/MCP）">
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

      <div v-if="ready && (recommendedPresets.length || savedPresets.length)" class="cap-presets">
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

      <!-- 内置工具：随 Agent 发布，只读展示 -->
      <section class="cap-card tool-card" :class="{ 'is-folded': collapsed.tool }">
        <header class="card-head">
          <button
            type="button" class="card-fold" :aria-expanded="!collapsed.tool"
            :title="collapsed.tool ? '展开工具列表' : '收起工具列表'"
            @click="toggleGroup('tool')"
          >
            <el-icon><ArrowRight v-if="collapsed.tool" /><ArrowDown v-else /></el-icon>
          </button>
          <span class="card-icon tool"><el-icon><Box /></el-icon></span>
          <span class="card-title">
            <b>内置工具 <span class="card-on mono">{{ toolCount }}</span></b>
            <em>随 Agent 发布，每轮对话自动可调用；与 Skill/MCP 不同，无需开关</em>
          </span>
          <span class="card-actions">
            <span class="mono">{{ readOnlyCount }} 只读</span>
          </span>
        </header>
        <div v-show="!collapsed.tool" class="tool-body">
          <div class="tool-faces" role="group" aria-label="按工具面筛选">
            <button
              type="button" class="face" :class="{ active: toolFace === 'all' }"
              @click="toolFace = 'all'; toolExpanded = false"
            >全部 <span class="mono">{{ toolCount }}</span></button>
            <button
              v-for="[face, count] in toolFaces" :key="face"
              type="button" class="face" :class="{ active: toolFace === face }"
              @click="toolFace = face; toolExpanded = false"
            >{{ face }} <span class="mono">{{ count }}</span></button>
          </div>
          <div v-if="filteredTools.length" class="tool-grid">
            <div
              v-for="tool in visibleTools" :key="tool.name"
              class="tool-chip" :title="tool.description"
            >
              <span class="tc-name mono">{{ tool.name }}</span>
              <span v-if="tool.readOnly" class="tc-ro" title="只读工具，不改动环境">R</span>
              <span class="tc-desc">{{ tool.description }}</span>
            </div>
          </div>
          <div v-else class="row-empty">没有匹配的工具</div>
          <button
            v-if="filteredTools.length > TOOL_PREVIEW && !keyword.trim()" type="button"
            class="tool-more" @click="toolExpanded = !toolExpanded"
          >
            {{ toolExpanded ? '收起' : `展开全部 ${filteredTools.length} 个工具` }}
            <el-icon><ArrowDown v-if="!toolExpanded" /><ArrowRight v-else /></el-icon>
          </button>
        </div>
      </section>

      <!-- Skill：每个目录一份说明，按需加载 -->
      <section v-if="skillOptions.length" class="cap-card" :class="{ 'is-folded': collapsed.skill }">
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
      <section v-if="mcpOptions.length" class="cap-card" :class="{ 'is-folded': collapsed.mcp }">
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

      <!-- Skill / MCP 都为空时给出去设置的引导（内置工具始终在上面展示） -->
      <div v-if="ready && !skillOptions.length && !mcpOptions.length" class="cap-empty">
        <p>还没有添加 Skill 或 MCP。内置工具已可直接使用；扩展能力请到设置页添加。</p>
        <button type="button" class="link" @click="goSettings">
          <el-icon><Setting /></el-icon>去设置页添加 Skill / MCP
        </button>
      </div>

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
  width: min(100%, 54rem);
  padding: var(--sp-6) var(--page-gutter) var(--sp-10);
  margin: 0 auto;
}

/* ---- 页头 ---- */
.cap-head {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}

.cap-head-text h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
  color: var(--ink);
}

.cap-head-text p {
  margin: 5px 0 0;
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
  to { transform: rotate(360deg); }
}

/* ---- 概览卡 ---- */
.cap-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: var(--sp-4);
}

.ov-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  grid-template-rows: auto auto;
  column-gap: 10px;
  align-items: center;
  padding: 12px 14px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
  transition: border-color var(--dur-fast) var(--ease-out), transform var(--dur-fast) var(--ease-out);
}

.ov-card:hover {
  border-color: var(--line-strong);
  transform: translateY(-1px);
}

.ov-icon {
  display: flex;
  grid-row: 1 / 3;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  font-size: 17px;
  border-radius: var(--radius-md);
}

.ov-card.tools .ov-icon { color: #2563eb; background: rgb(37 99 235 / 10%); }
.ov-card.skills .ov-icon { color: var(--accent-ink); background: var(--accent-soft); }
.ov-card.mcp .ov-icon { color: #0891b2; background: rgb(8 145 178 / 10%); }

.ov-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.ov-num {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--ink);
}

.ov-label {
  font-size: 11.5px;
  color: var(--ink-soft);
}

.ov-sub {
  grid-column: 2;
  font-size: 10.5px;
  color: var(--ink-faint);
}

/* ---- 提示条 ---- */
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
  flex: 1 1 200px;
  align-items: center;
  gap: 6px;
  min-width: 160px;
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

.preset-x:hover { color: var(--danger); }

/* ---- 能力卡 ---- */
.cap-card {
  margin-bottom: var(--sp-4);
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg);
}

.cap-card.is-folded .card-head {
  border-bottom: 0;
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  font-size: 15px;
  border-radius: var(--radius-sm);
}

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

.card-icon.tool { color: #2563eb; background: rgb(37 99 235 / 10%); }
.card-icon.skill { color: var(--accent-ink); background: var(--accent-soft); }
.card-icon.mcp { color: #0891b2; background: rgb(8 145 178 / 10%); }

.card-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
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
  white-space: nowrap;
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

.link:hover { text-decoration: underline; }

.link:disabled {
  color: var(--ink-faint);
  cursor: not-allowed;
  text-decoration: none;
}

/* ---- 内置工具网格 ---- */
.tool-body {
  padding: 12px 14px 14px;
}

.tool-faces {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.face {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  padding: 4px 10px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: 999px;
}

.face .mono {
  padding: 0 4px;
  font-size: 10px;
  color: var(--ink-faint);
  background: var(--surface-hover);
  border-radius: 999px;
}

.face:hover { border-color: var(--line-strong); }

.face.active {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: transparent;
}

.face.active .mono {
  color: var(--accent-ink);
  background: rgb(255 255 255 / 55%);
}

.tool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(228px, 1fr));
  gap: 8px;
}

.tool-chip {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 3px 6px;
  align-items: center;
  padding: 8px 10px;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  transition: border-color var(--dur-fast) var(--ease-out);
}

.tool-chip:hover { border-color: var(--line-strong); }

.tc-name {
  overflow: hidden;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tc-ro {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  font-size: 9px;
  font-weight: 700;
  color: #0891b2;
  background: rgb(8 145 178 / 12%);
  border-radius: 4px;
}

.tc-desc {
  grid-column: 1 / 3;
  overflow: hidden;
  font-size: 10.5px;
  line-height: 1.45;
  color: var(--ink-faint);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.tool-more {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  margin-top: 10px;
  padding: 5px 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px dashed var(--line-soft);
  border-radius: var(--radius-md);
}

.tool-more:hover { border-color: var(--accent-soft); }

.row-empty {
  padding: 14px;
  font-size: 11.5px;
  color: var(--ink-faint);
  text-align: center;
}

/* ---- Skill / MCP 列表 ---- */
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

.cap-row:first-child { border-top: 0; }
.cap-row:hover { background: var(--surface-hover); }

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

.cap-row.on .row-name { color: var(--ink); }

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
  padding: 24px 16px;
  text-align: center;
  background: var(--surface-raised);
  border: 1px dashed var(--line-soft);
  border-radius: var(--radius-lg);
}

.cap-empty p {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--ink-muted);
}

@media (max-width: 720px) {
  .cap-overview { grid-template-columns: 1fr; }
  .ov-card { grid-template-columns: auto minmax(0, 1fr) auto; }
  .ov-sub { grid-column: 3; grid-row: 1 / 3; }
}

@media (max-width: 640px) {
  .cap-page { padding: var(--sp-4) var(--page-gutter-tight) var(--sp-8); }
  .row-desc { white-space: normal; }
  .card-actions .link { display: none; }
}
</style>
