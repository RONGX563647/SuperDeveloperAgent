<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import {
  deleteMcpServer, installCapability, listCapabilityMarket, listMcpServers,
  saveMcpServer, testMcpDraft, testMcpServer,
  type CapabilityEntry, type McpServer, type McpTransport
} from '../../api/settings'

/**
 * MCP 接入（设置页）。
 *
 * 配置形状对齐 Claude Desktop / Claude Code 的 `mcpServers`：
 * - stdio：启动命令 + 每行一个参数（如 npx / -y / @playwright/mcp@latest）；
 * - http：URL + 一行一个请求头（如 Authorization: Bearer ...，值加密保存）。
 *
 * 「测试连接」对 http 发送 MCP initialize 握手；stdio 检查本机命令是否存在。
 */
const servers = ref<McpServer[]>([])
const loading = ref(false)
const saving = ref(false)
const testing = ref('')
const formOpen = ref(false)

/** 页面分区：已接入 / MCP 市场。 */
const tab = ref<'installed' | 'market'>('installed')
const market = ref<CapabilityEntry[]>([])
const marketLoading = ref(false)
const marketQuery = ref('')
const installing = ref('')

// 目录很小，前端过滤即可，避免每次输入都打后端
const marketFiltered = computed(() => {
  const q = marketQuery.value.trim().toLowerCase()
  if (!q) return market.value
  return market.value.filter((m) =>
    `${m.title} ${m.name} ${m.description} ${m.tags.join(' ')}`.toLowerCase().includes(q))
})

const form = reactive({
  id: '',
  name: '',
  transport: 'stdio' as McpTransport,
  command: 'npx',
  args: '',
  url: '',
  headers: '',
  enabled: true
})

async function load(): Promise<void> {
  loading.value = true
  try {
    servers.value = await listMcpServers()
  } catch {
    servers.value = []
  } finally {
    loading.value = false
  }
}

async function loadMarket(): Promise<void> {
  marketLoading.value = true
  try {
    market.value = await listCapabilityMarket('MCP')
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

/** 一键接入市场里的 MCP 服务（装完默认启用）。 */
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

/** 列表里直接启停：不用进编辑表单，headers 留空即沿用原值。 */
async function toggleEnabled(server: McpServer, enabled: boolean): Promise<void> {
  try {
    await saveMcpServer({
      id: server.id,
      name: server.name,
      transport: server.transport,
      command: server.command ?? '',
      args: server.args.join('\n'),
      url: server.url ?? '',
      enabled
    })
    server.enabled = enabled
    ElMessage.success(enabled ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  }
}

function openForm(): void {
  form.id = ''
  form.name = ''
  form.transport = 'stdio'
  form.command = 'npx'
  form.args = ''
  form.url = ''
  form.headers = ''
  form.enabled = true
  formOpen.value = true
}

function editForm(server: McpServer): void {
  form.id = server.id
  form.name = server.name
  form.transport = server.transport
  form.command = server.command ?? ''
  form.args = server.args.join('\n')
  form.url = server.url ?? ''
  form.headers = ''
  form.enabled = server.enabled
  formOpen.value = true
}

function payload() {
  return {
    id: form.id || undefined,
    name: form.name.trim(),
    transport: form.transport,
    command: form.transport === 'stdio' ? form.command.trim() : undefined,
    args: form.transport === 'stdio' ? form.args : undefined,
    url: form.transport === 'http' ? form.url.trim() : undefined,
    headers: form.transport === 'http' && form.headers.trim() ? form.headers : undefined,
    enabled: form.enabled
  }
}

async function save(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('请填写服务名称，例如 playwright')
    return
  }
  if (form.transport === 'stdio' && !form.command.trim()) {
    ElMessage.warning('stdio 服务需要启动命令')
    return
  }
  if (form.transport === 'http' && !form.url.trim()) {
    ElMessage.warning('http 服务需要地址')
    return
  }
  saving.value = true
  try {
    await saveMcpServer(payload())
    ElMessage.success('MCP 服务已保存')
    formOpen.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function testDraft(): Promise<void> {
  saving.value = true
  try {
    const r = await testMcpDraft(payload())
    if (r.ok) ElMessage.success(r.message)
    else ElMessage.error(r.message)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '测试失败')
  } finally {
    saving.value = false
  }
}

async function test(server: McpServer): Promise<void> {
  testing.value = server.id
  try {
    const r = await testMcpServer(server.id)
    if (r.ok) ElMessage.success(r.message)
    else ElMessage.error(r.message)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '测试失败')
  } finally {
    testing.value = ''
  }
}

async function remove(server: McpServer): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `删除 MCP 服务「${server.name}」？`, '删除 MCP 服务',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteMcpServer(server.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

load()
defineExpose({ load })
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <div>
        <div class="panel-title">MCP 接入</div>
        <div class="panel-desc">
          按 mcpServers 约定配置 stdio / http 服务；请求头与令牌加密保存，只回显字段名
        </div>
      </div>
      <el-button size="small" @click="formOpen ? (formOpen = false) : openForm()">
        <el-icon><Plus /></el-icon> {{ formOpen ? '收起' : '新增服务' }}
      </el-button>
    </div>

    <div class="panel-tabs">
      <button
        type="button" class="panel-tab" :class="{ active: tab === 'installed' }"
        @click="switchTab('installed')"
      >
        已接入 <span class="tab-count mono">{{ servers.length }}</span>
      </button>
      <button
        type="button" class="panel-tab" :class="{ active: tab === 'market' }"
        @click="switchTab('market')"
      >
        MCP 市场
      </button>
    </div>

    <template v-if="tab === 'installed'">
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!servers.length" class="empty">
      还没有 MCP 服务。可以从「新增服务」开始，例如 npx -y @playwright/mcp@latest。
    </div>
    <div v-for="s in servers" :key="s.id" class="mcp-row">
      <span class="mcp-name mono">{{ s.name }}</span>
      <span class="badge" :class="s.transport">{{ s.transport }}</span>
      <span class="mcp-target mono">
        {{ s.transport === 'stdio' ? [s.command, ...s.args].filter(Boolean).join(' ') : s.url }}
      </span>
      <span v-if="s.headerNames.length" class="mcp-meta">headers: {{ s.headerNames.join(', ') }}</span>
      <button
        type="button"
        class="toggle-btn"
        :class="{ on: s.enabled }"
        :title="s.enabled ? '默认启用：对话未单独勾选时调用' : '已停用：对话里需手动勾选'"
        @click="toggleEnabled(s, !s.enabled)"
      >
        {{ s.enabled ? '已启用' : '已停用' }}
      </button>
      <span class="mcp-actions">
        <el-button size="small" text :loading="testing === s.id" @click="test(s)">测试</el-button>
        <el-button size="small" text @click="editForm(s)">编辑</el-button>
        <el-button size="small" text type="danger" @click="remove(s)">删除</el-button>
      </span>
    </div>

    <div v-if="formOpen" class="mcp-form">
      <div class="form-row">
        <label class="form-label">名称</label>
        <el-input
          v-model="form.name" size="default" class="form-control mono"
          placeholder="playwright / filesystem / notion"
        />
      </div>
      <div class="form-row">
        <label class="form-label">传输</label>
        <el-radio-group v-model="form.transport" size="default">
          <el-radio-button value="stdio">stdio</el-radio-button>
          <el-radio-button value="http">http</el-radio-button>
        </el-radio-group>
      </div>
      <template v-if="form.transport === 'stdio'">
        <div class="form-row">
          <label class="form-label">启动命令</label>
          <el-input
            v-model="form.command" size="default" class="form-control mono"
            placeholder="npx / uvx / node"
          />
        </div>
        <div class="form-row">
          <label class="form-label">参数</label>
          <el-input
            v-model="form.args" type="textarea" :rows="3" class="form-control mono"
            placeholder="每行一个参数，例如：&#10;-y&#10;@playwright/mcp@latest"
          />
        </div>
      </template>
      <template v-else>
        <div class="form-row">
          <label class="form-label">服务 URL</label>
          <el-input
            v-model="form.url" size="default" class="form-control mono"
            placeholder="https://mcp.example.com/mcp"
          />
        </div>
        <div class="form-row">
          <label class="form-label">请求头</label>
          <el-input
            v-model="form.headers" type="textarea" :rows="3" class="form-control mono"
            :placeholder="form.id ? '留空 = 不修改已保存的请求头' : '每行一个，例如：Authorization: Bearer xxx'"
          />
        </div>
      </template>
      <div class="form-row">
        <label class="form-label">状态</label>
        <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
      </div>
      <div class="form-actions">
        <el-button size="default" @click="formOpen = false">取消</el-button>
        <el-button size="default" :loading="saving" @click="testDraft">测试连接</el-button>
        <el-button size="default" type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>
    </template>

    <!-- 市场：内置目录，一键接入后可在这里启停 -->
    <template v-else>
      <div class="market-toolbar">
        <el-input
          v-model="marketQuery" size="default" clearable
          placeholder="搜索 MCP，例如 browser / 文档 / 时间"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button size="default" :loading="marketLoading" @click="loadMarket">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
      <div v-if="marketLoading" class="empty">加载市场…</div>
      <div v-else-if="!marketFiltered.length" class="empty">没有匹配的 MCP 服务。</div>
      <div v-for="m in marketFiltered" :key="m.id" class="market-card">
        <div class="market-main">
          <div class="market-title">
            {{ m.title }}
            <span class="badge" :class="m.transport">{{ m.transport }}</span>
          </div>
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
  max-width: 46rem;
  margin-top: 4px;
  font-size: var(--text-xs);
  line-height: var(--leading-base);
  color: var(--ink-muted);
}
.panel-head .el-button {
  margin-left: auto;
  flex: none;
}
.empty {
  padding: 18px 0;
  font-size: 12px;
  color: var(--ink-faint);
}
.mcp-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  transition: border-color var(--dur-fast) var(--ease-out);
}
.mcp-row:hover {
  border-color: var(--line);
}
.toggle-btn {
  flex: none;
  height: 24px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  white-space: nowrap;
  cursor: pointer;
  background: transparent;
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  transition: color var(--dur-fast) var(--ease-out),
    background-color var(--dur-fast) var(--ease-out),
    border-color var(--dur-fast) var(--ease-out);
}
.toggle-btn:hover {
  color: var(--ink);
  border-color: var(--line-strong);
}
.toggle-btn.on {
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-color: transparent;
}
.mcp-name {
  flex: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.badge {
  flex: none;
  padding: 1px 7px;
  font-size: 10px;
  color: var(--ink-soft);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.badge.http {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.mcp-target {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 11px;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mcp-meta {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.mcp-actions {
  display: flex;
  flex: none;
  gap: 2px;
}
.mcp-form {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  background: var(--bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.form-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
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

/* 分区切换：轻量 tab，避免和设置页左侧 nav 抢层级 */
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
  display: flex;
  gap: 6px;
  align-items: center;
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
