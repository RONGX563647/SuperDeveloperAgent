<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh, RefreshRight } from '@element-plus/icons-vue'
import { listProjects, type ProjectRow } from '../../api/project'
import {
  deleteProjectSecret, listProjectSecrets, rotateProjectSecret, saveProjectSecret,
  type ProjectSecret,
} from '../../api/settings'
import { appStore } from '../../stores/app'
import { formatTime } from '../../utils/status'
import { envLabel } from '../../utils/pipelineLabels'

/**
 * 项目密钥库（按环境隔离）。
 *
 * <p>密钥只加密落库，接口永远只回名称/版本/掩码；环境变量填
 * <code>secret://名称</code> 即可引用，部署前在服务端解析，Agent 看不到明文。
 * 轮换后版本 +1，dry-run 指纹随之变化，旧的部署预检报告会自动失效。
 */
const ENVS = ['dev', 'test', 'prod']
/** 与后端 SecretRefs 的命名规则一致。 */
const NAME_PATTERN = /^[A-Za-z0-9._-]{1,128}$/

const projects = ref<ProjectRow[]>([])
const projectId = ref('')
const env = ref(appStore.env)
const items = ref<ProjectSecret[]>([])
const loading = ref(false)
const busy = ref('')

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'rotate'>('create')
const saving = ref(false)
const form = reactive({ name: '', value: '' })

async function loadProjects(): Promise<void> {
  try {
    projects.value = await listProjects()
    if (!projectId.value) {
      const preferred = projects.value.find((p) => p.id === appStore.projectId)
      projectId.value = preferred?.id ?? projects.value[0]?.id ?? ''
    }
  } catch {
    projects.value = []
  }
}

async function load(): Promise<void> {
  if (!projectId.value) {
    items.value = []
    return
  }
  loading.value = true
  try {
    items.value = await listProjectSecrets(projectId.value, env.value)
  } catch (e) {
    items.value = []
    ElMessage.error(e instanceof Error ? e.message : '读取密钥失败')
  } finally {
    loading.value = false
  }
}

function startCreate(): void {
  dialogMode.value = 'create'
  form.name = ''
  form.value = ''
  dialogOpen.value = true
}

function startRotate(item: ProjectSecret): void {
  dialogMode.value = 'rotate'
  form.name = item.name
  form.value = ''
  dialogOpen.value = true
}

async function submit(): Promise<void> {
  const name = form.name.trim()
  if (dialogMode.value === 'create' && !NAME_PATTERN.test(name)) {
    ElMessage.warning('名称只能包含字母、数字、点、下划线、短横线（1-128 位）')
    return
  }
  if (!form.value) {
    ElMessage.warning('请填写密钥值')
    return
  }
  saving.value = true
  try {
    if (dialogMode.value === 'create') {
      await saveProjectSecret(projectId.value, { env: env.value, name, value: form.value })
      ElMessage.success(`已保存密钥 ${name}`)
    } else {
      await rotateProjectSecret(projectId.value, name, env.value, form.value)
      ElMessage.success(`已轮换密钥 ${name}，版本已更新`)
    }
    dialogOpen.value = false
    form.value = ''
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(item: ProjectSecret): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `删除密钥 ${item.name}？引用它的环境变量会变成空值，部署预检会以 E-704 拦截。`,
    '删除密钥',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  busy.value = item.name
  try {
    await deleteProjectSecret(projectId.value, item.name, env.value)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    busy.value = ''
  }
}

function onProjectChange(id: string): void {
  if (id) appStore.projectId = id
}

onMounted(async () => {
  await loadProjects()
  await load()
})

defineExpose({ load })
</script>

<template>
  <section class="secret-panel" aria-label="项目密钥库">
    <header class="sp-head">
      <div class="sp-title-row">
        <h2 class="sp-title">密钥库</h2>
        <el-button size="small" type="primary" :icon="Plus" @click="startCreate">新建密钥</el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
      <p class="sp-sub">
        密钥 AES-GCM 加密落库，接口只返回名称与版本。在「项目环境」里把变量值写成
        <code>secret://名称</code> 即可引用；部署时在服务端解析，Agent 与日志都看不到明文。
        轮换后版本 +1，旧的 dry-run 报告会因指纹变化自动失效。
      </p>
    </header>

    <div class="sp-context">
      <label class="sp-field">
        <span>项目</span>
        <el-select v-model="projectId" size="default" filterable placeholder="选择项目" @change="onProjectChange">
          <el-option
            v-for="p in projects" :key="p.id"
            :label="`${p.name}（${p.id}）`" :value="p.id"
          />
        </el-select>
      </label>
      <label class="sp-field">
        <span>环境</span>
        <el-select v-model="env" size="default" @change="load">
          <el-option v-for="e in ENVS" :key="e" :label="envLabel(e)" :value="e" />
        </el-select>
      </label>
      <span class="sp-count">{{ items.length }} 个密钥</span>
    </div>

    <div class="sp-table" v-loading="loading">
      <div class="sp-row sp-row-head">
        <span>名称</span>
        <span>版本</span>
        <span>引用写法</span>
        <span>更新</span>
        <span />
      </div>
      <div v-for="item in items" :key="item.name" class="sp-row">
        <code class="sp-key mono">{{ item.name }}</code>
        <span class="sp-version">v{{ item.version }}</span>
        <code class="sp-ref mono">secret://{{ item.name }}</code>
        <span class="sp-updated">{{ formatTime(item.updatedAt) }}</span>
        <span class="sp-actions">
          <button
            type="button" class="sp-icon" title="轮换密钥值"
            :disabled="busy === item.name" @click="startRotate(item)"
          >
            <el-icon><RefreshRight /></el-icon>
          </button>
          <button
            type="button" class="sp-icon danger" title="删除密钥"
            :disabled="busy === item.name" @click="remove(item)"
          >
            <el-icon><Delete /></el-icon>
          </button>
        </span>
      </div>
      <p v-if="!items.length && !loading" class="sp-empty">
        这个环境还没有密钥。新建后，把项目环境变量值写成 <code>secret://名称</code> 即可引用。
      </p>
    </div>

    <el-dialog
      v-model="dialogOpen"
      :title="dialogMode === 'create' ? '新建密钥' : `轮换密钥 ${form.name}`"
      width="480px" append-to-body
    >
      <div class="sp-form">
        <label class="sp-field">
          <span>名称</span>
          <el-input
            v-model="form.name" size="default" :disabled="dialogMode === 'rotate'"
            placeholder="例如 DB_PASSWORD" class="mono"
          />
        </label>
        <label class="sp-field">
          <span>值</span>
          <el-input
            v-model="form.value" size="default" type="password" show-password
            placeholder="密钥明文，只写不读" class="mono"
            @keyup.enter="submit"
          />
        </label>
        <p class="sp-form-hint">
          保存后接口只返回 <code>****</code> 与版本号；忘记只能轮换，无法查看原值。
        </p>
      </div>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ dialogMode === 'create' ? '保存' : '轮换' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.secret-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.sp-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.sp-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.sp-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}
.sp-sub {
  max-width: 720px;
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.sp-sub code,
.sp-empty code,
.sp-form-hint code {
  padding: 1px 4px;
  font-size: 11px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-xs);
}
.sp-context {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 14px;
}
.sp-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 180px;
  font-size: 11.5px;
  color: var(--ink-muted);
}
.sp-count {
  margin-left: auto;
  padding-bottom: 6px;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.sp-table {
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.sp-row {
  display: grid;
  grid-template-columns: minmax(120px, 1.2fr) 70px minmax(160px, 1.2fr) 150px 80px;
  gap: 10px;
  align-items: center;
  padding: 9px 12px;
  font-size: 12px;
  border-top: 1px solid var(--line-soft);
}
.sp-row-head {
  font-size: 11px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-top: 0;
}
.sp-key {
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.sp-version {
  font-size: 11px;
  color: var(--ink-muted);
}
.sp-ref {
  font-size: 11px;
  color: var(--accent-ink);
}
.sp-updated {
  font-size: 11px;
  color: var(--ink-faint);
}
.sp-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}
.sp-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  font-size: 13px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
}
.sp-icon:hover:not(:disabled) {
  color: var(--accent-ink);
  background: var(--surface-hover);
}
.sp-icon.danger:hover:not(:disabled) {
  color: var(--danger);
}
.sp-icon:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.sp-empty {
  padding: 18px 12px;
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--ink-faint);
  border-top: 1px solid var(--line-soft);
}
.sp-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sp-form-hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-faint);
}
@media (max-width: 900px) {
  .sp-row {
    grid-template-columns: minmax(100px, 1fr) 50px minmax(120px, 1fr) 70px;
  }
  .sp-row > :nth-child(4) {
    display: none;
  }
  .sp-context {
    align-items: stretch;
  }
  .sp-count {
    margin-left: 0;
  }
}
</style>
