<script setup lang="ts">
import { onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { MagicStick, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { formatTime } from '../../utils/status'
import {
  deleteSkill, importSkill, installCapability, listCapabilityMarket, listCapabilitySources,
  listSkills, probeSkills, readSkill, saveSkill, setSkillEnabled,
  type CapabilityEntry, type CapabilitySource, type SkillEntry, type SkillProbeMatch
} from '../../api/settings'

/**
 * Skill 接入（设置页）。
 *
 * 遵循 Agent Skills 目录约定：一个技能 = 一个目录 + SKILL.md（YAML frontmatter 的
 * name / description 用于发现，正文是给 Agent 的指令）。技能放在平台侧
 * `<workspace>/_platform/skills`，可从已有目录导入，也可直接新建。
 */
const skills = ref<SkillEntry[]>([])
const root = ref('')
const loading = ref(false)
const saving = ref(false)
const formOpen = ref(false)
const previewOpen = ref(false)
const previewName = ref('')
const previewContent = ref('')

const form = reactive({ name: '', description: '', content: '' })

/** 分区：已接入 / Skill 市场。 */
const tab = ref<'installed' | 'market'>('installed')
const market = ref<CapabilityEntry[]>([])
const marketLoading = ref(false)
const marketLoadingMore = ref(false)
const marketQuery = ref('')
const installing = ref('')
/** 外部市场不可达时的提示（不再静默显示为空列表）。 */
const marketError = ref('')
/** 懒加载分页状态：每次打开市场都 refresh=true 重新同步两个仓库。 */
const MARKET_PAGE = 24
const marketTotal = ref(0)
const marketHasMore = ref(false)
const marketOffset = ref(0)
/** 已接入的市场来源；当前选中的来源为空 = 全部。 */
const sources = ref<CapabilitySource[]>([])
const selectedSource = ref('')
let searchTimer: ReturnType<typeof setTimeout> | undefined
/** 请求序号：搜索/刷新会作废还在路上的旧分页响应。 */
let loadSeq = 0

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await listSkills()
    skills.value = res.skills
    root.value = res.root
  } catch {
    skills.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 市场加载。
 *
 * @param reset true = 从第一页开始
 * @param sync  true = 让后端清缓存并重新同步两个技能仓库（打开市场 / 点刷新时）
 */
async function loadMarket({ reset = false, sync = false } = {}): Promise<void> {
  if (!reset && (!marketHasMore.value || marketLoadingMore.value)) {
    return
  }
  if (reset) {
    marketLoading.value = true
    marketOffset.value = 0
  } else {
    marketLoadingMore.value = true
  }
  marketError.value = ''
  const offset = reset ? 0 : marketOffset.value
  const seq = ++loadSeq
  try {
    const page = await listCapabilityMarket('SKILL', marketQuery.value, offset,
      MARKET_PAGE, sync, selectedSource.value)
    if (seq !== loadSeq) return
    market.value = reset ? page.items : [...market.value, ...page.items]
    marketTotal.value = page.total
    marketHasMore.value = page.hasMore
    marketOffset.value = offset + MARKET_PAGE
  } catch (e) {
    if (seq === loadSeq && reset) {
      market.value = []
      marketHasMore.value = false
      marketTotal.value = 0
      marketError.value = e instanceof Error ? e.message : '技能市场加载失败'
    }
  } finally {
    if (seq === loadSeq) {
      marketLoading.value = false
      marketLoadingMore.value = false
    }
  }
}

/** 滚动到接近底部时加载下一页。 */
function onMarketScroll(event: Event): void {
  const el = event.target as HTMLElement
  if (el.scrollHeight - el.scrollTop - el.clientHeight < 120) {
    void loadMarket()
  }
}

async function loadSources(): Promise<void> {
  try {
    sources.value = (await listCapabilitySources('SKILL')).items
  } catch {
    sources.value = []
  }
}

/** 切换来源：重置分页并重新加载。 */
function selectSource(id: string): void {
  if (selectedSource.value === id) return
  selectedSource.value = id
  void loadMarket({ reset: true })
}

function switchTab(next: 'installed' | 'market'): void {
  tab.value = next
  // 每次进入市场都重新同步远端，避免看到 10 分钟前的旧目录
  if (next === 'market') {
    void Promise.all([loadSources(), loadMarket({ reset: true, sync: true })])
  }
}

watch(marketQuery, () => {
  if (tab.value !== 'market') return
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void loadMarket({ reset: true }), 300)
})
onUnmounted(() => {
  if (searchTimer) clearTimeout(searchTimer)
})

async function install(entry: CapabilityEntry): Promise<void> {
  installing.value = entry.id
  try {
    const r = await installCapability(entry.id)
    ElMessage.success(r.message)
    const target = market.value.find((item) => item.id === entry.id)
    if (target) target.installed = true
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '接入失败')
  } finally {
    installing.value = ''
  }
}

/** 默认启停：对话未显式勾选时按这里的状态加载技能。 */
async function toggleEnabled(skill: SkillEntry, enabled: boolean): Promise<void> {
  try {
    await setSkillEnabled(skill.dirName, enabled)
    skill.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

function openForm(): void {
  form.name = ''
  form.description = ''
  form.content = ''
  formOpen.value = true
}

async function save(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写技能目录名，例如 java-review')
    return
  }
  if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(form.name.trim())) {
    ElMessage.warning('目录名只能是小写字母、数字与短横线')
    return
  }
  saving.value = true
  try {
    await saveSkill({
      name: form.name.trim(),
      description: form.description.trim(),
      content: form.content
    })
    ElMessage.success('技能已保存')
    formOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function importFromDir(): Promise<void> {
  const path = await ElMessageBox.prompt(
    '输入包含 SKILL.md 的本地目录，例如 /opt/skills/java-review',
    '导入技能目录',
    {
      confirmButtonText: '导入',
      cancelButtonText: '取消',
      inputPlaceholder: '/path/to/skill-dir',
      inputValidator: (v) => Boolean(v && v.trim()) || '目录不能为空'
    }
  ).then((r) => r.value.trim()).catch(() => '')
  if (!path) return
  try {
    const skill = await importSkill(path)
    ElMessage.success(`已导入：${skill.name}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导入失败')
  }
}

async function preview(skill: SkillEntry): Promise<void> {
  try {
    const r = await readSkill(skill.dirName)
    previewName.value = r.name
    previewContent.value = r.content
    previewOpen.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '读取失败')
  }
}

async function remove(skill: SkillEntry): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `删除技能「${skill.name}」及其全部文件？`, '删除技能',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteSkill(skill.dirName)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

// ---------------------------------------------------------------- 质量检查 / 命中试跑

/** 体积按 KB / MB 展示，避免一长串字节。 */
function sizeText(bytes: number): string {
  if (!bytes) return '0 KB'
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 返回可操作的描述/体积问题；空数组表示没有明显问题。 */
function issuesOf(skill: SkillEntry): string[] {
  const issues: string[] = []
  const description = skill.description.trim()
  if (!description) {
    issues.push('缺少 description，模型只能靠目录名猜用途')
  } else if (description.length < 12) {
    issues.push('description 过短，建议写清「什么时候用」')
  } else if (description.length > 160) {
    issues.push('description 过长，会稀释其他技能的匹配度')
  }
  if (skill.sizeBytes > 300 * 1024) {
    issues.push(`目录 ${sizeText(skill.sizeBytes)}，建议把大文件放到 references 按需读取`)
  }
  return issues
}

const probeOpen = ref(false)
const probeQuery = ref('')
const probing = ref(false)
const probeDone = ref(false)
const probeResults = ref<SkillProbeMatch[]>([])

function openProbe(): void {
  probeOpen.value = true
  if (!probeQuery.value) {
    probeQuery.value = ''
    probeDone.value = false
  }
}

async function runProbe(): Promise<void> {
  const query = probeQuery.value.trim()
  if (!query) {
    ElMessage.warning('输入一个示例问题，例如「帮我排查部署失败」')
    return
  }
  probing.value = true
  try {
    const result = await probeSkills(query)
    probeResults.value = result.matches
    probeDone.value = true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '命中测试失败')
  } finally {
    probing.value = false
  }
}

function closeProbe(): void {
  probeOpen.value = false
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">Skill</div>
        <div class="panel-desc">
          一个技能 = 一个目录 + SKILL.md（YAML frontmatter 的 name / description 用于发现）
          <span v-if="root" class="root mono">{{ root }}</span>
        </div>
      </div>
      <div class="head-actions">
        <el-button v-if="tab === 'installed'" size="small" @click="probeOpen ? closeProbe() : openProbe()">
          {{ probeOpen ? '收起测试' : '命中测试' }}
        </el-button>
        <el-button size="small" @click="importFromDir">
          <el-icon><Upload /></el-icon> 导入目录
        </el-button>
        <el-button size="small" @click="formOpen ? (formOpen = false) : openForm()">
          <el-icon><Plus /></el-icon> {{ formOpen ? '收起' : '新建技能' }}
        </el-button>
      </div>
    </div>

    <div class="panel-tabs">
      <button
        type="button" class="panel-tab" :class="{ active: tab === 'installed' }"
        @click="switchTab('installed')"
      >
        已接入 <span class="tab-count mono">{{ skills.length }}</span>
      </button>
      <button
        type="button" class="panel-tab" :class="{ active: tab === 'market' }"
        @click="switchTab('market')"
      >
        Skill 市场
      </button>
    </div>

    <template v-if="tab === 'installed'">
    <div class="panel-scroll">
    <div v-if="probeOpen" class="probe-card">
      <div class="probe-head">
        <span class="probe-title">示例问题命中测试</span>
        <span class="probe-hint">按名称与描述关键词打分，不调用模型</span>
      </div>
      <div class="probe-row">
        <el-input
          v-model="probeQuery" size="default" clearable
          placeholder="例如：帮我排查测试环境部署失败"
          @keyup.enter="runProbe"
        />
        <el-button size="default" type="primary" :loading="probing" @click="runProbe">测试</el-button>
      </div>
      <div v-if="probeDone" class="probe-results">
        <div v-if="!probeResults.length" class="probe-empty">没有已启用的技能。</div>
        <template v-else>
          <div
            v-for="item in probeResults.slice(0, 5)" :key="item.dirName"
            class="probe-item" :class="{ hit: item.score > 0 }"
          >
            <span class="probe-name mono">{{ item.name }}</span>
            <span class="probe-score mono">{{ item.score > 0 ? `命中 ${item.score}` : '未命中' }}</span>
            <span class="probe-desc">{{ item.description || '（无描述）' }}</span>
            <span class="probe-token mono">~{{ item.tokenEstimate }}t</span>
          </div>
          <div v-if="!probeResults.some((item) => item.score > 0)" class="probe-empty">
            所有技能都没命中。把示例问题里的关键词补进 description，模型才能选中它。
          </div>
        </template>
      </div>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!skills.length" class="empty-state">
      <span class="empty-icon"><el-icon><MagicStick /></el-icon></span>
      <p class="empty-title">还没有接入技能</p>
      <p class="empty-hint">
        技能是给 Agent 的指令包：一个目录 + SKILL.md。可以从公开技能市场一键接入，
        也可以导入本地目录或直接新建。
      </p>
      <div class="empty-actions">
        <el-button type="primary" size="small" @click="switchTab('market')">浏览 Skill 市场</el-button>
        <el-button size="small" @click="importFromDir">导入目录</el-button>
        <el-button size="small" @click="openForm">新建技能</el-button>
      </div>
    </div>
    <div v-for="s in skills" :key="s.dir" class="skill-card">
      <div class="skill-main">
        <div class="skill-title">
          <span class="skill-name mono">{{ s.name }}</span>
          <span class="skill-meta">{{ s.fileCount }} 个文件</span>
          <span class="skill-meta mono">{{ sizeText(s.sizeBytes) }}</span>
          <span v-if="s.updatedAt" class="skill-meta">{{ formatTime(s.updatedAt) }}</span>
          <span v-if="issuesOf(s).length" class="quality-badge">
            检查 {{ issuesOf(s).length }}
          </span>
        </div>
        <div class="skill-desc">{{ s.description || '（SKILL.md 未填写 description）' }}</div>
        <div v-if="issuesOf(s).length" class="skill-issues">
          <span v-for="issue in issuesOf(s)" :key="issue" class="skill-issue">{{ issue }}</span>
        </div>
      </div>
      <span class="switch-cell">
        <el-switch
          :model-value="s.enabled" size="small"
          :aria-label="`默认启用 ${s.name}`"
          @change="(v: string | number | boolean) => toggleEnabled(s, Boolean(v))"
        />
        <span class="switch-text">{{ s.enabled ? '已启用' : '已停用' }}</span>
      </span>
      <div class="skill-actions">
        <el-button size="small" text @click="preview(s)">查看</el-button>
        <el-button size="small" text type="danger" @click="remove(s)">删除</el-button>
      </div>
    </div>

    <div v-if="formOpen" class="skill-form">
      <div class="form-row">
        <label class="form-label">目录名</label>
        <el-input
          v-model="form.name" size="default" class="form-control mono"
          placeholder="java-review（小写字母/数字/短横线）"
        />
      </div>
      <div class="form-row">
        <label class="form-label">描述</label>
        <el-input
          v-model="form.description" size="default" class="form-control"
          placeholder="一句话说明何时使用该技能（Agent 靠它决定是否加载）"
        />
      </div>
      <div class="form-row top">
        <label class="form-label">正文</label>
        <el-input
          v-model="form.content" type="textarea" :rows="8" class="form-control mono"
          placeholder="Markdown 指令；留空会生成最小 SKILL.md"
        />
      </div>
      <div class="form-actions">
        <el-button size="default" @click="formOpen = false">取消</el-button>
        <el-button size="default" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>
    </div>
    </template>

    <!-- 市场：多来源开源技能仓库，可配置任意多个 GitHub 仓库 -->
    <template v-else>
      <div class="market-toolbar">
        <el-input
          v-model="marketQuery" size="default" clearable
          placeholder="搜索技能，例如 review / 测试 / 发布"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button
          size="default" :loading="marketLoading"
          title="重新同步所有来源"
          @click="loadMarket({ reset: true, sync: true })"
        >
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
      <!-- 来源筛选：每个 GitHub 仓库是一个独立来源，点选切换 -->
      <div v-if="sources.length" class="source-bar">
        <span class="source-label">来源</span>
        <button
          type="button" class="source-chip" :class="{ active: !selectedSource }"
          @click="selectSource('')"
        >全部</button>
        <button
          v-for="s in sources" :key="s.id" type="button" class="source-chip"
          :class="{ active: selectedSource === s.id }" :title="`${s.description}\n${s.url}`"
          @click="selectSource(s.id)"
        >
          {{ s.name }}<span v-if="!s.builtin" class="source-custom" title="自定义接入">+</span>
        </button>
        <a
          class="source-help" href="https://github.com/anthropics/skills"
          target="_blank" rel="noopener" title="如何接入开源 Skill 仓库"
        >如何接入？</a>
      </div>
      <div class="panel-scroll" @scroll="onMarketScroll">
      <div v-if="marketLoading" class="empty">正在同步技能市场…</div>
      <div v-else-if="marketError" class="market-error">
        <span>{{ marketError }}</span>
        <el-button size="small" text @click="loadMarket">重试</el-button>
      </div>
      <div v-else-if="!market.length" class="empty">没有匹配的技能。</div>
      <p v-else class="market-hint">
        {{ selectedSource ? `当前来源：${selectedSource}` : `共接入 ${sources.length} 个开源仓库` }}，
        本页 {{ marketTotal }} 个；安装会连同 scripts / references 一起下载。
      </p>
      <div v-for="m in market" :key="m.id" class="market-card">
        <div class="market-main">
          <div class="market-title">{{ m.title }}</div>
          <div class="market-desc">{{ m.description }}</div>
          <div class="market-meta mono">
            {{ m.name }} · {{ m.tags.join(' / ') }}
            <button
              type="button" class="market-source" :title="`来自来源：${m.source}（点击只看这个来源）`"
              @click="selectSource(m.source)"
            >{{ m.source }}</button>
          </div>
        </div>
        <el-button
          size="small" :type="m.installed ? 'info' : 'primary'" :disabled="m.installed"
          :loading="installing === m.id" @click="install(m)"
        >
          {{ m.installed ? '已接入' : '接入' }}
        </el-button>
      </div>
      <div v-if="marketLoadingMore" class="empty">加载更多…</div>
      <div v-else-if="!marketHasMore && market.length" class="market-hint end">
        已显示全部 {{ marketTotal }} 个技能
      </div>
      </div>
    </template>

    <el-dialog v-model="previewOpen" :title="`SKILL.md · ${previewName}`" width="720px">
      <pre class="preview mono">{{ previewContent }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  overflow: hidden;
}
/* 市场/已接入列表独立滚动：条目再多也不把整页撑长 */
.panel-scroll {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 8px;
  min-height: 240px;
  max-height: calc(100vh - 320px);
  padding-right: 4px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.panel-title {
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.panel-desc {
  max-width: 44rem;
  margin-top: 4px;
  font-size: var(--text-xs);
  line-height: var(--leading-base);
  color: var(--ink-muted);
}
.root {
  display: block;
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.head-actions {
  display: flex;
  flex: none;
  gap: 8px;
  margin-left: auto;
}
.empty {
  padding: 18px 0;
  font-size: 12px;
  color: var(--ink-faint);
}
/* 空状态：在撑满的卡片里居中，给出明确下一步，避免整页只剩一大片空白 */
.empty-state {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 260px;
  padding: 24px 0;
  text-align: center;
}
.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  font-size: 19px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-md);
}
.empty-title {
  margin: 4px 0 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}
.empty-hint {
  max-width: 34rem;
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-muted);
}
.empty-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-top: 6px;
}
.market-error {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
  padding: 12px 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--danger);
  background: rgb(220 78 78 / 8%);
  border: 1px solid rgb(220 78 78 / 22%);
  border-radius: var(--radius-md);
}
.market-hint {
  margin: 0;
  font-size: 11px;
  color: var(--ink-faint);
}
.market-hint.end {
  padding: 6px 0 2px;
  text-align: center;
}
/* 来源栏：多源接入，点选切换；自定义来源带 + 角标 */
.source-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 4px 0 2px;
}
.source-label {
  font-size: 11px;
  color: var(--ink-faint);
}
.source-chip {
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
.source-chip:hover { border-color: var(--line-strong); }
.source-chip.active {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: transparent;
}
.source-custom {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 13px;
  height: 13px;
  font-size: 10px;
  line-height: 1;
  color: var(--accent-ink);
  border: 1px solid currentColor;
  border-radius: 3px;
}
.source-help {
  margin-left: auto;
  font-size: 11px;
  color: var(--accent-ink);
  text-decoration: none;
}
.source-help:hover { text-decoration: underline; }
.market-source {
  padding: 1px 6px;
  font-family: inherit;
  font-size: 10px;
  color: var(--accent-ink);
  cursor: pointer;
  background: var(--accent-soft);
  border: 0;
  border-radius: 999px;
}
.market-source:hover { text-decoration: underline; }
.quality-badge {
  padding: 1px 6px;
  font-size: 10px;
  color: var(--warn);
  background: var(--warn-faint, #fdf6e3);
  border-radius: var(--radius-pill);
}
.skill-issues {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 10px;
  margin-top: 4px;
}
.skill-issue {
  font-size: 10.5px;
  color: var(--warn);
}
.skill-issue::before {
  content: '· ';
}
.probe-card {
  padding: 10px 12px;
  margin-bottom: 10px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.probe-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 8px;
}
.probe-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.probe-hint {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.probe-row {
  display: flex;
  gap: 8px;
}
.probe-results {
  padding-top: 8px;
  margin-top: 8px;
  border-top: 1px dashed var(--line-soft);
}
.probe-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 4px 2px;
  font-size: 11px;
  color: var(--ink-faint);
}
.probe-item.hit {
  color: var(--ink-muted);
}
.probe-name {
  flex: none;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.probe-score {
  flex: none;
  color: var(--ink-faint);
}
.probe-item.hit .probe-score {
  color: var(--ok);
}
.probe-desc {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.probe-token {
  flex: none;
  font-size: 10px;
}
.probe-empty {
  padding: 6px 2px;
  font-size: 11px;
  color: var(--ink-faint);
}
.skill-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 13px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  transition: border-color var(--dur-fast) var(--ease-out);
}
.skill-card:hover {
  border-color: var(--line);
}
.switch-cell {
  display: inline-flex;
  flex: none;
  gap: 7px;
  align-items: center;
}
.switch-cell .el-switch {
  --el-switch-on-color: var(--accent);
  --el-switch-off-color: var(--line-strong);
}
.switch-text {
  min-width: 2.8em;
  font-size: 11px;
  color: var(--ink-muted);
  white-space: nowrap;
}
.skill-main {
  flex: 1;
  min-width: 0;
}
.skill-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.skill-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.skill-meta {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.skill-desc {
  margin-top: 3px;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skill-actions {
  display: flex;
  flex: none;
  gap: 2px;
}
.skill-form {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.form-row {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
}
.form-row.top {
  align-items: start;
}
.form-label {
  font-size: 12px;
  color: var(--ink-muted);
}
.form-control {
  width: 100%;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.preview {
  max-height: 60vh;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  color: var(--ink-soft);
}

/* 分区切换 + 市场卡片（与 MCP 面板保持一致的视觉语言） */
.panel-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--line-soft);
}
.panel-tab {
  padding: 6px 2px 7px;
  margin-right: 14px;
  font-size: 12px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-bottom: 2px solid transparent;
}
.panel-tab:hover {
  color: var(--ink);
}
.panel-tab.active {
  color: var(--ink);
  border-bottom-color: var(--accent);
}
.tab-count {
  margin-left: 3px;
  color: var(--ink-faint);
}
.market-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.market-card {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 10px 12px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.market-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}
.market-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.market-desc {
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--ink-muted);
}
.market-meta {
  font-size: 10.5px;
  color: var(--ink-faint);
}
</style>
