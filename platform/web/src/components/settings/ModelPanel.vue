<script setup lang="ts">
import { nextTick, ref, useTemplateRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheck, CircleClose, Plus, Star } from '@element-plus/icons-vue'
import {
  createModel, deleteModel, listModels, setDefaultModel, testModelDraft, updateModel,
  type ModelConnection, type ModelConnectionInput, type TestResult
} from '../../api/settings'

/**
 * 模型连接面板：列表 + **内联表单**（不弹浮层）+ 测试连接 + 设默认（运行时热切换）。
 */
const models = ref<ModelConnection[]>([])
const loading = ref(false)
const formOpen = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref<TestResult | null>(null)
const editing = ref<ModelConnection | null>(null)
const formRef = useTemplateRef<HTMLElement>('formRef')

// PRESETS 必须在 emptyForm() 首次调用之前初始化（const 有 TDZ）。
const PRESETS = [
  { key: 'dashscope', label: '通义千问（DashScope）', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { key: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { key: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { key: 'ollama', label: '本地 Ollama', baseUrl: 'http://127.0.0.1:11434/v1', model: 'llama3' },
  { key: 'custom', label: '自定义（OpenAI 兼容）', baseUrl: '', model: '' }
]

function emptyForm(): ModelConnectionInput {
  return {
    name: '',
    provider: 'dashscope',
    baseUrl: PRESETS[0].baseUrl,
    modelName: PRESETS[0].model,
    temperature: 0.2,
    maxIters: 8,
    enabled: true,
    isDefault: false,
    apiKey: ''
  }
}

const form = ref<ModelConnectionInput>(emptyForm())

function applyPreset(key: string): void {
  const preset = PRESETS.find((p) => p.key === key)
  if (!preset) return
  form.value.provider = preset.key
  if (preset.baseUrl) form.value.baseUrl = preset.baseUrl
  if (preset.model) form.value.modelName = preset.model
}

async function load(): Promise<void> {
  loading.value = true
  try {
    models.value = await listModels()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载模型失败')
  } finally {
    loading.value = false
  }
}

async function openCreate(): Promise<void> {
  editing.value = null
  form.value = emptyForm()
  testResult.value = null
  formOpen.value = true
  await nextTick()
  formRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

async function openEdit(model: ModelConnection): Promise<void> {
  editing.value = model
  form.value = {
    name: model.name,
    provider: model.provider,
    baseUrl: model.baseUrl,
    modelName: model.modelName,
    temperature: model.temperature,
    maxIters: model.maxIters,
    enabled: model.enabled,
    isDefault: model.isDefault,
    apiKey: ''
  }
  testResult.value = null
  formOpen.value = true
  await nextTick()
  formRef.value?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
}

function closeForm(): void {
  formOpen.value = false
  testResult.value = null
  editing.value = null
}

/** 编辑态也测当前表单草稿：否则改了地址/模型再测，结果仍是第一次保存的配置。 */
async function testCurrent(): Promise<void> {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testModelDraft({
      baseUrl: form.value.baseUrl,
      modelName: form.value.modelName,
      apiKey: form.value.apiKey,
      connectionId: editing.value?.id
    })
    await load()
  } catch (e) {
    testResult.value = {
      ok: false,
      latencyMs: 0,
      message: e instanceof Error ? e.message : '测试失败'
    }
  } finally {
    testing.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    if (editing.value) {
      await updateModel(editing.value.id, form.value)
    } else {
      await createModel(form.value)
    }
    ElMessage.success('已保存，新对话即刻生效')
    closeForm()
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(model: ModelConnection): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `删除模型连接「${model.name}」？`, '删除确认',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteModel(model.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function makeDefault(model: ModelConnection): Promise<void> {
  try {
    await setDefaultModel(model.id)
    ElMessage.success(`默认模型：${model.name}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '设置失败')
  }
}

function testLabel(model: ModelConnection): string {
  if (model.lastTestAt == null) return '未测试'
  const latency = model.lastTestLatencyMs ? ` · ${model.lastTestLatencyMs} ms` : ''
  return (model.lastTestOk ? '可用' : '不可用') + latency
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">模型连接</div>
        <div class="panel-desc">保存后无需重启；默认模型用于对话与 Agent</div>
      </div>
      <el-button size="small" :icon="Plus" type="primary" @click="openCreate">添加模型</el-button>
    </div>

    <section v-if="formOpen" ref="formRef" class="inline-form">
      <div class="form-head">
        <span class="form-title">{{ editing ? '编辑模型连接' : '添加模型连接' }}</span>
        <span class="form-hint">API Key 加密保存，只回显掩码</span>
      </div>

      <div class="form-grid">
        <label class="field">
          <span class="field-label">厂商</span>
          <el-select v-model="form.provider" class="w-full" @change="applyPreset">
            <el-option v-for="p in PRESETS" :key="p.key" :label="p.label" :value="p.key" />
          </el-select>
        </label>
        <label class="field">
          <span class="field-label">名称</span>
          <el-input v-model="form.name" placeholder="通义-生产（留空用模型名）" />
        </label>

        <label class="field">
          <span class="field-label">Base URL</span>
          <el-input v-model="form.baseUrl" placeholder="https://…/v1" />
        </label>
        <label class="field">
          <span class="field-label">模型名</span>
          <el-input v-model="form.modelName" placeholder="qwen-plus" />
        </label>

        <label class="field span-2">
          <span class="field-label">API Key</span>
          <el-input
            v-model="form.apiKey" type="password" show-password
            :placeholder="editing?.hasApiKey ? '已保存，留空表示不修改' : 'sk-…（加密保存）'"
          />
        </label>

        <label class="field">
          <span class="field-label">温度</span>
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" controls-position="right" class="w-full" />
        </label>
        <label class="field">
          <span class="field-label">最大迭代</span>
          <el-input-number v-model="form.maxIters" :min="1" :max="30" controls-position="right" class="w-full" />
        </label>
      </div>

      <div class="form-row">
        <el-checkbox v-model="form.enabled">启用</el-checkbox>
        <el-checkbox v-model="form.isDefault" :disabled="editing?.isDefault">设为默认</el-checkbox>
        <span class="row-hint">测试会发送一次 1 token 的请求，用于验证连通性</span>
      </div>

      <el-alert
        v-if="testResult" :type="testResult.ok ? 'success' : 'error'" :closable="false" show-icon
        :title="testResult.ok ? `连接正常（${testResult.latencyMs} ms）` : '连接失败'"
        :description="testResult.ok
          ? (testResult.reply ? `模型回复：${testResult.reply}` : '模型已响应')
          : testResult.message"
      />

      <div class="form-actions">
        <el-button class="cancel" @click="closeForm">取消</el-button>
        <el-button :loading="testing" @click="testCurrent">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="save">连接并保存</el-button>
      </div>
    </section>

    <div v-loading="loading" class="list">
      <div v-if="!models.length && !loading && !formOpen" class="empty">
        <p class="empty-title">还没有模型连接</p>
        <p class="empty-sub">不配置时使用环境变量里的模型；配置后以设置页为准</p>
        <el-button size="small" @click="openCreate">添加第一个</el-button>
      </div>

      <div v-for="m in models" :key="m.id" class="model-row">
        <div class="row-main">
          <div class="row-title">
            <span class="row-name">{{ m.name }}</span>
            <el-tag v-if="m.isDefault" size="small" type="warning" round effect="light">默认</el-tag>
            <el-tag v-if="!m.enabled" size="small" type="info" round>已停用</el-tag>
            <span class="row-tag">{{ m.provider }}</span>
          </div>
          <div class="row-meta mono">
            {{ m.modelName }} · {{ m.baseUrl }}
            · 密钥 {{ m.hasApiKey ? (m.apiKeyMasked ?? '已保存') : '未设置' }}
          </div>
        </div>

        <div class="row-test" :class="{ ok: m.lastTestOk === true, bad: m.lastTestOk === false }">
          <el-icon v-if="m.lastTestOk === true"><CircleCheck /></el-icon>
          <el-icon v-else-if="m.lastTestOk === false"><CircleClose /></el-icon>
          <span :title="m.lastTestMsg ?? ''">{{ testLabel(m) }}</span>
        </div>

        <div class="row-actions">
          <el-button size="small" text @click="openEdit(m)">编辑</el-button>
          <el-button size="small" text :disabled="m.isDefault" @click="makeDefault(m)">
            <el-icon><Star /></el-icon> 设默认
          </el-button>
          <el-button size="small" text type="danger" :disabled="m.isDefault" @click="remove(m)">
            删除
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.panel-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.panel-desc {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.panel-head .el-button {
  margin-left: auto;
}

.inline-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 18px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.form-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.form-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}
.form-hint {
  font-size: 11px;
  color: var(--ink-faint);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.field.span-2 {
  grid-column: span 2;
}
.field-label {
  font-size: 11.5px;
  color: var(--ink-muted);
}
.w-full {
  width: 100%;
}
.form-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.row-hint {
  margin-left: auto;
  font-size: 11px;
  color: var(--ink-faint);
}
.form-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 2px;
}
.form-actions .cancel {
  margin-right: auto;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 100px;
}
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 36px 0;
}
.empty-title {
  margin: 0;
  font-size: 13px;
  color: var(--ink-muted);
}
.empty-sub {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.model-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.row-main {
  flex: 1 1 320px;
  min-width: 0;
}
.row-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.row-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.row-tag {
  padding: 1px 7px;
  font-size: 10px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.row-meta {
  margin-top: 3px;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-test {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  color: var(--ink-muted);
}
.row-test.ok { color: var(--ok); }
.row-test.bad { color: var(--danger); }
.row-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 2px;
}

@media (max-width: 720px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .field.span-2 {
    grid-column: span 1;
  }
}
</style>
