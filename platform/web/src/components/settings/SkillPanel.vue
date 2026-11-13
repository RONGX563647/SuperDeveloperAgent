<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Upload } from '@element-plus/icons-vue'
import {
  deleteSkill, importSkill, listSkills, readSkill, saveSkill, type SkillEntry
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
    const r = await readSkill(skill.name)
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
    await deleteSkill(skill.name)
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
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
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
</style>
