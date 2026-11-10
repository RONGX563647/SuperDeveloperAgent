<script setup lang="ts">
import { nextTick, ref, useTemplateRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheck, CircleClose, Plus, Star } from '@element-plus/icons-vue'
import {
  createServer, deleteServer, listServers, setDefaultServer, testServerDraft,
  updateServer, type DeployServer, type DeployServerInput, type TestResult
} from '../../api/settings'

/**
 * 服务器配置面板：列表 + **内联表单**（不弹浮层）+ 测试连通性 + 设默认。
 * 表单直接嵌在卡片里，编辑态与列表同屏，符合"工作台式设置"。
 */
const servers = ref<DeployServer[]>([])
const loading = ref(false)

const formOpen = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref<TestResult | null>(null)
const editing = ref<DeployServer | null>(null)
const form = ref<DeployServerInput>(emptyForm())
const formRef = useTemplateRef<HTMLElement>('formRef')

function emptyForm(): DeployServerInput {
  return {
    name: '',
    host: '',
    port: 22,
    sshUser: 'deploy',
    authType: 'KEY',
    keyPath: '~/.ssh/id_ed25519',
    remoteDir: '/opt/agent/deploy',
    enabled: true,
    isDefault: false,
    secret: ''
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    servers.value = await listServers()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载服务器失败')
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

async function openEdit(server: DeployServer): Promise<void> {
  editing.value = server
  form.value = {
    name: server.name,
    host: server.host,
    port: server.port,
    sshUser: server.sshUser,
    authType: server.authType,
    keyPath: server.keyPath ?? '',
    remoteDir: server.remoteDir,
    enabled: server.enabled,
    isDefault: server.isDefault,
    secret: ''
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

/**
 * 测试连接一律用「当前表单草稿」：
 * 编辑态若走 /servers/{id}/test，测的是数据库里第一次保存的旧配置，
 * 改了主机/端口/密码再点测试，结果仍是第一次的，看起来像结果被缓存。
 * 带上 id 后，留空的密钥由后端沿用已保存值，本次结果也会写回列表状态。
 */
async function testCurrent(): Promise<void> {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testServerDraft({
      ...form.value,
      id: editing.value?.id
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
      await updateServer(editing.value.id, form.value)
    } else {
      await createServer(form.value)
    }
    ElMessage.success('已保存')
    closeForm()
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(server: DeployServer): Promise<void> {
  const tip = server.isDefault
    ? `删除默认服务器「${server.name}」？删除后会自动把另一台启用服务器设为默认。`
    : `删除服务器「${server.name}」？`
  const ok = await ElMessageBox.confirm(
    tip, '删除确认',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteServer(server.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function makeDefault(server: DeployServer): Promise<void> {
  try {
    await setDefaultServer(server.id)
    ElMessage.success(`默认服务器：${server.name}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '设置失败')
  }
}

function testLabel(server: DeployServer): string {
  if (server.lastTestAt == null) return '未测试'
  return server.lastTestOk ? '可连接' : '连接失败'
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">服务器</div>
        <div class="panel-desc">连接与运行环境；流水线选择「远程服务器」时使用默认服务器</div>
      </div>
      <el-button size="small" :icon="Plus" type="primary" @click="openCreate">添加服务器</el-button>
    </div>

    <!-- 内联表单：不弹浮层 -->
    <section v-if="formOpen" ref="formRef" class="inline-form">
      <div class="form-head">
        <span class="form-title">{{ editing ? '编辑服务器' : '添加服务器' }}</span>
        <span class="form-hint">密钥/密码加密保存，接口不回显明文</span>
      </div>

      <div class="form-grid">
        <label class="field span-2">
          <span class="field-label">名称</span>
          <el-input v-model="form.name" placeholder="开发测试服务器（留空用主机名）" />
        </label>

        <label class="field">
          <span class="field-label">主机地址</span>
          <el-input v-model="form.host" placeholder="IP 或主机名" />
        </label>
        <label class="field short">
          <span class="field-label">SSH 端口</span>
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" class="w-full" />
        </label>

        <label class="field">
          <span class="field-label">用户名</span>
          <el-input v-model="form.sshUser" placeholder="root / deploy" />
        </label>
        <label class="field short">
          <span class="field-label">认证方式</span>
          <el-select v-model="form.authType" class="w-full">
            <el-option label="密钥" value="KEY" />
            <el-option label="密码" value="PASSWORD" />
          </el-select>
        </label>

        <label v-if="form.authType === 'KEY'" class="field span-2">
          <span class="field-label">私钥路径</span>
          <el-input v-model="form.keyPath" placeholder="~/.ssh/id_ed25519" />
        </label>
        <label v-else class="field span-2">
          <span class="field-label">密码</span>
          <el-input
            v-model="form.secret" type="password" show-password
            :placeholder="editing?.hasSecret ? '已保存，留空表示不修改' : '服务器密码（加密保存）'"
          />
        </label>

        <label class="field span-2">
          <span class="field-label">远端部署目录</span>
          <el-input v-model="form.remoteDir" placeholder="/opt/agent/deploy" />
        </label>
      </div>

      <div class="form-row">
        <el-checkbox v-model="form.enabled">启用</el-checkbox>
        <el-checkbox v-model="form.isDefault" :disabled="editing?.isDefault">设为默认</el-checkbox>
        <span class="row-hint">首次连接会保存主机指纹；建议先用「测试连接」验证</span>
      </div>

      <el-alert
        v-if="testResult" :type="testResult.ok ? 'success' : 'error'" :closable="false" show-icon
        :title="testResult.ok ? `连接正常（${testResult.latencyMs} ms）` : '连接失败'"
        :description="testResult.ok ? (testResult.dockerVersion ?? '') : testResult.message"
      />

      <div class="form-actions">
        <el-button class="cancel" @click="closeForm">取消</el-button>
        <el-button :loading="testing" @click="testCurrent">测试连接</el-button>
        <el-button type="primary" :loading="saving" @click="save">连接并保存</el-button>
      </div>
    </section>

    <div v-loading="loading" class="list">
      <div v-if="!servers.length && !loading && !formOpen" class="empty">
        <p class="empty-title">还没有服务器</p>
        <p class="empty-sub">添加一台服务器后，流水线可以部署到远端 docker</p>
        <el-button size="small" @click="openCreate">添加第一台</el-button>
      </div>

      <div v-for="s in servers" :key="s.id" class="server-row">
        <div class="row-main">
          <div class="row-title">
            <span class="row-name">{{ s.name }}</span>
            <el-tag v-if="s.isDefault" size="small" type="warning" round effect="light">默认</el-tag>
            <el-tag v-if="!s.enabled" size="small" type="info" round>已停用</el-tag>
          </div>
          <div class="row-meta mono">
            {{ s.sshUser }}@{{ s.host }}:{{ s.port }} · {{ s.remoteDir }}
            · {{ s.authType === 'KEY' ? (s.keyPath || '未配置私钥') : '密码认证' }}
          </div>
        </div>

        <div class="row-test" :class="{ ok: s.lastTestOk === true, bad: s.lastTestOk === false }">
          <el-icon v-if="s.lastTestOk === true"><CircleCheck /></el-icon>
          <el-icon v-else-if="s.lastTestOk === false"><CircleClose /></el-icon>
          <span :title="s.lastTestMsg ?? ''">{{ testLabel(s) }}</span>
        </div>

        <div class="row-actions">
          <el-button size="small" text @click="openEdit(s)">编辑</el-button>
          <el-button size="small" text :disabled="s.isDefault" @click="makeDefault(s)">
            <el-icon><Star /></el-icon> 设默认
          </el-button>
          <el-button size="small" text type="danger" @click="remove(s)">
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

/* 内联表单 */
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
.field.short {
  max-width: 220px;
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
.server-row {
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
  flex: 1 1 300px;
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
  .field.short {
    max-width: none;
  }
}
</style>
