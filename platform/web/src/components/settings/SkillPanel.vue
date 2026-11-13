<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import {
  deleteSkill, importSkill, installCapability, listCapabilityMarket, listSkills,
  readSkill, saveSkill, setSkillEnabled,
  type CapabilityEntry, type SkillEntry
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
const marketQuery = ref('')
const installing = ref('')

const marketFiltered = computed(() => {
  const q = marketQuery.value.trim().toLowerCase()
  if (!q) return market.value
  return market.value.filter((m) =>
    `${m.title} ${m.name} ${m.description} ${m.tags.join(' ')}`.toLowerCase().includes(q))
})

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

async function loadMarket(): Promise<void> {
  marketLoading.value = true
  try {
    market.value = await listCapabilityMarket('SKILL')
  } catch {
    market.value = []
  } finally {
    marketLoading.value = false
  }
}

function switchTab(next: 'installed' | 'market'): void {
  tab.value = next
  if (next === 'market' && !market.value.length) {
    void loadMarket()
  }
}

async function install(entry: CapabilityEntry): Promise<void> {
  installing.value = entry.id
  try {
    const r = await installCapability(entry.id)
    ElMessage.success(r.message)
    await Promise.all([load(), loadMarket()])
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

function formatTime(ts: number): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', { hour12: false })
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
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!skills.length" class="empty">
      还没有技能。新建一个 SKILL.md，或从已有目录导入。
    </div>
    <div v-for="s in skills" :key="s.dir" class="skill-card">
      <div class="skill-main">
        <div class="skill-title">
          <span class="skill-name mono">{{ s.name }}</span>
          <span class="skill-meta">{{ s.fileCount }} 个文件</span>
          <span v-if="s.updatedAt" class="skill-meta">{{ formatTime(s.updatedAt) }}</span>
        </div>
        <div class="skill-desc">{{ s.description || '（SKILL.md 未填写 description）' }}</div>
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
    </template>

    <!-- 市场：内置公开技能，一键写入 _platform/skills -->
    <template v-else>
      <div class="market-toolbar">
        <el-input
          v-model="marketQuery" size="default" clearable
          placeholder="搜索技能，例如 review / 测试 / 发布"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button size="default" :loading="marketLoading" @click="loadMarket">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
      <div v-if="marketLoading" class="empty">加载市场…</div>
      <div v-else-if="!marketFiltered.length" class="empty">没有匹配的技能。</div>
      <div v-for="m in marketFiltered" :key="m.id" class="market-card">
        <div class="market-main">
          <div class="market-title">{{ m.title }}</div>
          <div class="market-desc">{{ m.description }}</div>
          <div class="market-meta mono">
            {{ m.name }} · {{ m.tags.join(' / ') }} · {{ m.source }}
          </div>
        </div>
        <el-button
          size="small" :type="m.installed ? 'info' : 'primary'" :disabled="m.installed"
          :loading="installing === m.id" @click="install(m)"
        >
          {{ m.installed ? '已接入' : '接入' }}
        </el-button>
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
  flex-direction: column;
  gap: 14px;
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
