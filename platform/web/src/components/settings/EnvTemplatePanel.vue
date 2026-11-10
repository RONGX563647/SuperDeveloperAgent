<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, DocumentCopy, Hide, Plus, Refresh, Search, VideoPause, VideoPlay, View } from '@element-plus/icons-vue'
import { ApiError } from '../../api/http'
import {
  checkEnvResource, deleteEnvResource, deleteEnvTemplateVar, discoverEnvResources,
  getEnvDetail, importEnvResources, listEnvTemplates, listServers, revealEnvVar,
  saveEnvResource, saveEnvTemplate, saveEnvTemplateVar, startEnvResource, stopEnvResource,
  type DeployServer, type EnvResourceView, type EnvTemplate, type EnvTemplateRow,
  type EnvVariableView, type RuntimeMode,
} from '../../api/settings'
import { appStore } from '../../stores/app'
import { formatTime } from '../../utils/status'
import { envLabel } from '../../utils/pipelineLabels'

/**
 * 环境模板（dev / test / prod）：
 *
 * 变量与中间件在这里配一次，全项目共用；项目级差异在「项目环境」里覆盖。
 * 敏感值加密落库，列表默认掩码，可点眼睛查看（写审计日志）。
 * 容器"没启动"不算异常；只有缺连接信息才算异常，部署时会自动启动被引用的中间件。
 */
const ENVS = ['dev', 'test', 'prod']
const rows = ref<EnvTemplateRow[]>([])
const env = ref('test')
const template = ref<EnvTemplate | null>(null)
const resources = ref<EnvResourceView[]>([])
const vars = ref<EnvVariableView[]>([])
const servers = ref<DeployServer[]>([])
const loading = ref(false)
const savingTemplate = ref(false)
const checkingAll = ref(false)
const checkingId = ref('')
const runtimeBusyId = ref('')
/** 已点眼睛查看的变量明文（键 → 明文）。 */
const revealed = ref<Record<string, string>>({})
const revealingKey = ref('')

/** 查看敏感值明文需要 settings:write 或 settings:reveal；管理员为 * 通配。 */
const canReveal = computed(() => {
  const permissions = appStore.identity.permissions
  return permissions.includes('*')
    || permissions.includes('settings:write')
    || permissions.includes('settings:reveal')
})

const settingForm = reactive({
  network: '',
  defaultTarget: 'docker',
  defaultPort: 8080,
  healthPath: '/healthz',
  runtimeMode: 'LOCAL_DEV',
  namespace: 'agent-dev',
  serverId: '',
})

/** 形态说明：决定中间件怎么寻址、前后端跑在哪。 */
const MODE_LABEL: Record<string, string> = {
  LOCAL_DEV: '本机进程 + 本机容器',
  LOCAL_TEST: '本机容器（前后端各一个容器）',
  SERVER: '服务器容器',
}

const runningCount = computed(() => resources.value.filter((r) => r.status === 'RUNNING').length)
const stoppedCount = computed(() =>
  resources.value.filter((r) => r.status === 'STOPPED' || r.status === 'UNKNOWN').length)
const missingCount = computed(() => resources.value.filter((r) => r.status === 'MISSING').length)

async function load(): Promise<void> {
  // 先详情（会把中间件账号密码同步成模板变量），再刷新卡片统计。
  await loadDetail()
  await loadSummary()
}

async function loadSummary(): Promise<void> {
  try {
    rows.value = await listEnvTemplates()
  } catch (e) {
    rows.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载环境模板失败')
  }
}

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    const detail = await getEnvDetail(env.value)
    template.value = detail.template
    resources.value = detail.resources
    vars.value = detail.vars
    revealed.value = {}
    settingForm.network = detail.template.network ?? ''
    settingForm.defaultTarget = detail.template.defaultTarget || 'docker'
    settingForm.defaultPort = detail.template.defaultPort || 8080
    settingForm.healthPath = detail.template.healthPath || '/healthz'
    settingForm.runtimeMode = detail.template.runtimeMode || 'LOCAL_DEV'
    settingForm.namespace = detail.template.namespace || `agent-${env.value}`
    settingForm.serverId = detail.template.serverId || ''
  } catch (e) {
    template.value = null
    resources.value = []
    vars.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载环境详情失败')
  } finally {
    loading.value = false
  }
}

function switchEnv(next: string): void {
  if (env.value === next) return
  env.value = next
  void loadDetail()
}

async function saveSetting(): Promise<void> {
  savingTemplate.value = true
  try {
    template.value = await saveEnvTemplate(env.value, {
      network: settingForm.network.trim() || undefined,
      defaultTarget: settingForm.defaultTarget,
      defaultPort: Number(settingForm.defaultPort) || 0,
      healthPath: settingForm.healthPath.trim() || undefined,
      runtimeMode: settingForm.runtimeMode as RuntimeMode,
      namespace: settingForm.namespace.trim() || undefined,
      serverId: settingForm.serverId || null,
    })
    ElMessage.success(`${envLabel(env.value)} 模板已保存`)
    await loadDetail()
    await loadSummary()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingTemplate.value = false
  }
}

// ---------------------------------------------------------------- 中间件

async function checkOne(r: EnvResourceView): Promise<void> {
  if (checkingId.value || checkingAll.value) return
  checkingId.value = r.id
  try {
    const updated = await checkEnvResource(env.value, r.id)
    Object.assign(r, updated)
    if (updated.status === 'MISSING') {
      ElMessage.warning(`${r.name} 缺少连接信息：${updated.missingFields.join('、')}`)
    } else if (updated.status === 'RUNNING') {
      ElMessage.success(`${r.name} 运行中`)
    } else {
      // 未启动不是异常，部署时会自动启动
      ElMessage.info(`${r.name} 已停止（未启动不算异常，部署时会自动启动）`)
    }
    await loadSummary()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '检查失败')
  } finally {
    checkingId.value = ''
  }
}

/** 批量检查：静默逐条更新 + 末尾一次汇总，避免刷屏，也不会在空环境下点了没反应。 */
async function checkAll(): Promise<void> {
  if (!resources.value.length) {
    ElMessage.info('当前环境还没有登记中间件，请先「发现 / 导入」或「手工添加」')
    return
  }
  checkingAll.value = true
  const failed: string[] = []
  let stopped = 0
  try {
    for (const r of resources.value) {
      try {
        const updated = await checkEnvResource(env.value, r.id)
        Object.assign(r, updated)
        if (updated.status === 'STOPPED' || updated.status === 'UNKNOWN') stopped += 1
        if (updated.status === 'MISSING' || (updated.lastError && !updated.running)) {
          failed.push(r.name)
        }
      } catch (e) {
        failed.push(`${r.name}（${e instanceof Error ? e.message : '检查失败'}）`)
      }
    }
    await loadSummary()
    if (failed.length) {
      ElMessage.warning(
        `${failed.length} 个中间件缺信息：${failed.slice(0, 3).join('、')}${failed.length > 3 ? ' 等' : ''}`)
    } else if (stopped) {
      ElMessage.success(`已检查 ${resources.value.length} 个中间件：${runningCount.value} 个运行中、${stopped} 个未启动（部署时会自动启动）`)
    } else {
      ElMessage.success(`已检查 ${resources.value.length} 个中间件，全部运行中`)
    }
  } finally {
    checkingAll.value = false
  }
}

/** 启动 / 停止 docker 中间件（手工登记的资源会被后端拒绝）。 */
async function toggleRunning(r: EnvResourceView): Promise<void> {
  if (runtimeBusyId.value) return
  runtimeBusyId.value = r.id
  try {
    const result = r.running
      ? await stopEnvResource(env.value, r.id)
      : await startEnvResource(env.value, r.id)
    if (result.ok) ElMessage.success(result.message || (r.running ? '已停止' : '已启动'))
    else ElMessage.error(result.message || '操作失败')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    runtimeBusyId.value = ''
  }
}

async function removeResource(r: EnvResourceView): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `删除中间件 ${r.name}（${r.host}:${r.port}）？`, '删除中间件',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteEnvResource(env.value, r.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

const resourceDialog = ref(false)
const resourceSaving = ref(false)
const resourceForm = reactive({
  kind: 'mysql', name: '', host: '127.0.0.1', port: 3306,
  databaseName: '', username: '', password: '',
})

function openResourceDialog(): void {
  resourceForm.kind = 'mysql'
  resourceForm.name = ''
  resourceForm.host = '127.0.0.1'
  resourceForm.port = 3306
  resourceForm.databaseName = ''
  resourceForm.username = ''
  resourceForm.password = ''
  resourceDialog.value = true
}

async function saveResource(): Promise<void> {
  if (!resourceForm.name.trim() || !resourceForm.host.trim()) {
    ElMessage.warning('名称和主机必填')
    return
  }
  resourceSaving.value = true
  try {
    await saveEnvResource(env.value, {
      kind: resourceForm.kind,
      name: resourceForm.name.trim(),
      host: resourceForm.host.trim(),
      port: Number(resourceForm.port) || 0,
      databaseName: resourceForm.databaseName.trim() || undefined,
      username: resourceForm.username.trim() || undefined,
      password: resourceForm.password || undefined,
    })
    ElMessage.success('已添加中间件')
    resourceDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    resourceSaving.value = false
  }
}

// ---------------------------------------------------------------- 发现导入

interface Candidate extends EnvResourceView {
  candidateId: string
}

const discoverDialog = ref(false)
const discovering = ref(false)
const importing = ref(false)
const discoverSource = ref<'local' | 'remote'>('local')
const discoverServerId = ref('')
const candidates = ref<Candidate[]>([])
const selectedIds = ref<string[]>([])

async function openDiscover(): Promise<void> {
  discoverDialog.value = true
  candidates.value = []
  selectedIds.value = []
  if (!servers.value.length) {
    try {
      servers.value = await listServers()
    } catch {
      servers.value = []
    }
  }
}

async function runDiscover(): Promise<void> {
  if (discoverSource.value === 'remote' && !discoverServerId.value) {
    ElMessage.warning('请先选择远程服务器')
    return
  }
  discovering.value = true
  try {
    candidates.value = await discoverEnvResources(
      env.value, discoverSource.value,
      discoverSource.value === 'remote' ? discoverServerId.value : undefined)
    selectedIds.value = candidates.value.map((c) => c.candidateId)
    if (!candidates.value.length) {
      ElMessage.info('没有发现 docker 容器；可以手工添加')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发现失败')
  } finally {
    discovering.value = false
  }
}

async function importSelected(): Promise<void> {
  if (!selectedIds.value.length) {
    ElMessage.warning('请先勾选要导入的中间件')
    return
  }
  importing.value = true
  try {
    const saved = await importEnvResources(env.value, selectedIds.value)
    ElMessage.success(`已导入 ${saved.length} 个中间件`)
    discoverDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导入失败')
  } finally {
    importing.value = false
  }
}

// ---------------------------------------------------------------- 变量

const varDialog = ref(false)
const varSaving = ref(false)
const varForm = reactive({ key: '', value: '', secret: true })

function openVarDialog(): void {
  varForm.key = ''
  varForm.value = ''
  varForm.secret = true
  varDialog.value = true
}

async function saveVar(): Promise<void> {
  if (!varForm.key.trim() || !varForm.value) {
    ElMessage.warning('变量名和值必填')
    return
  }
  varSaving.value = true
  try {
    await saveEnvTemplateVar(env.value, {
      key: varForm.key.trim(),
      value: varForm.value,
      secret: varForm.secret,
    })
    ElMessage.success('已保存变量')
    varDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    varSaving.value = false
  }
}

async function removeVar(v: EnvVariableView): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `删除模板变量 ${v.key}？所有项目都会失去该默认值。`, '删除变量',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteEnvTemplateVar(env.value, v.key)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function kindLabel(kind: string): string {
  const map: Record<string, string> = {
    mysql: 'MySQL', mariadb: 'MariaDB', postgres: 'PostgreSQL', redis: 'Redis',
    mongo: 'MongoDB', nacos: 'Nacos', rabbitmq: 'RabbitMQ', minio: 'MinIO', other: '其他',
  }
  return map[kind] ?? kind
}

/** 点眼睛查看敏感变量明文；再点一次收起。明文变量本来就不需要解密。 */
async function toggleReveal(v: EnvVariableView): Promise<void> {
  if (revealed.value[v.key] !== undefined) {
    const next = { ...revealed.value }
    delete next[v.key]
    revealed.value = next
    return
  }
  if (!canReveal.value) {
    ElMessage.warning('当前账号没有查看明文权限，请用管理员账号登录')
    return
  }
  revealingKey.value = v.key
  try {
    const result = await revealEnvVar(env.value, v.key)
    revealed.value = { ...revealed.value, [v.key]: result.value }
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      ElMessage.error('当前账号没有查看明文权限（需 settings:write / settings:reveal）')
    } else {
      ElMessage.error(e instanceof Error ? e.message : '查看失败')
    }
  } finally {
    revealingKey.value = ''
  }
}

/** 复制当前显示的值；明文变量直接复制，敏感变量需先点眼睛。 */
async function copyValue(v: EnvVariableView): Promise<void> {
  try {
    await navigator.clipboard.writeText(varDisplay(v))
    ElMessage.success(`已复制 ${v.key}`)
  } catch {
    ElMessage.warning(`复制失败，请手动选中 ${v.key} 的值`)
  }
}

function varDisplay(v: EnvVariableView): string {
  return revealed.value[v.key] ?? v.value
}

function sourceLabel(source: string): string {
  if (source === 'RESOURCE') return '中间件'
  if (source === 'OVERRIDE') return '项目覆盖'
  return '手工'
}

onMounted(async () => {
  await loadDetail()
  await loadSummary()
  try {
    servers.value = await listServers()
  } catch {
    servers.value = []
  }
})

defineExpose({ load })
</script>

<template>
  <section class="env-template-panel" aria-label="环境模板">
    <header class="et-head">
      <div>
        <h2 class="et-title">环境模板</h2>
        <p class="et-sub">
          为开发 / 测试 / 生产各配一套变量、网络和中间件；项目差异在「项目环境」里覆盖。
          敏感值加密保存，列表默认掩码，可点眼睛查看；容器未启动不算异常，部署时会自动启动项目引用的中间件。
        </p>
      </div>
      <el-button size="small" :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
    </header>

    <div class="et-cards">
      <button
        v-for="row in rows" :key="row.template.env"
        type="button" class="et-card"
        :class="{ active: env === row.template.env, danger: row.missingCount > 0 }"
        @click="switchEnv(row.template.env)"
      >
        <span class="et-card-top">
          <b>{{ envLabel(row.template.env) }}</b>
          <span v-if="row.missingCount > 0" class="et-badge danger">{{ row.missingCount }} 个缺信息</span>
          <span v-else-if="row.resourceCount > 0" class="et-badge ok">已配置</span>
        </span>
        <span class="et-card-line">网络 {{ row.template.network || '默认' }}</span>
        <span class="et-card-line">
          形态 {{ MODE_LABEL[row.template.runtimeMode] || row.template.runtimeMode }} · 命名空间 {{ row.template.namespace }}
        </span>
        <span class="et-card-line">
          端口 {{ row.template.defaultPort || '未配置' }} · 变量 {{ row.varCount }} 个
          <template v-if="row.secretCount">（{{ row.secretCount }} 敏感）</template>
        </span>
        <span class="et-card-line">
          中间件 {{ row.resourceCount }} 个 · 运行 {{ row.runningCount }}
          <template v-if="row.stoppedCount"> · 未启动 {{ row.stoppedCount }}</template>
        </span>
      </button>
    </div>

    <div class="et-detail" v-loading="loading">
      <div class="et-detail-head">
        <b>{{ envLabel(env) }} 模板</b>
        <span v-if="template?.updatedAt" class="et-muted">
          更新于 {{ formatTime(template.updatedAt) }}
        </span>
      </div>

      <div class="et-form-grid">
        <label class="et-field">
          <span>运行形态</span>
          <el-select v-model="settingForm.runtimeMode" size="small">
            <el-option label="本机进程 + 本机容器（开发）" value="LOCAL_DEV" />
            <el-option label="本机容器，前后端各一个（测试）" value="LOCAL_TEST" />
            <el-option label="服务器容器（生产）" value="SERVER" />
          </el-select>
        </label>
        <label class="et-field">
          <span>命名空间（隔离 / 发现过滤）</span>
          <el-input v-model="settingForm.namespace" size="small" placeholder="agent-dev" />
        </label>
        <label v-if="settingForm.runtimeMode === 'SERVER'" class="et-field">
          <span>绑定服务器</span>
          <el-select v-model="settingForm.serverId" size="small" placeholder="默认服务器" clearable>
            <el-option v-for="s in servers" :key="s.id" :label="s.name || s.host" :value="s.id" />
          </el-select>
        </label>
        <label class="et-field">
          <span>部署网络（--network）</span>
          <el-input v-model="settingForm.network" size="small" placeholder="为空则用容器默认网络" />
        </label>
        <label class="et-field">
          <span>默认部署目标</span>
          <el-select v-model="settingForm.defaultTarget" size="small">
            <el-option label="本机 Docker" value="docker" />
            <el-option label="本机直接运行" value="host" />
            <el-option label="远程服务器" value="server" />
          </el-select>
        </label>
        <label class="et-field">
          <span>默认端口</span>
          <el-input-number v-model="settingForm.defaultPort" size="small" :min="0" :max="65535" controls-position="right" />
        </label>
        <label class="et-field">
          <span>健康路径</span>
          <el-input v-model="settingForm.healthPath" size="small" placeholder="/healthz" />
        </label>
        <div class="et-form-actions">
          <el-button size="small" type="primary" :loading="savingTemplate" @click="saveSetting">
            保存模板
          </el-button>
        </div>
      </div>

      <!-- 中间件 -->
      <div class="et-block">
        <div class="et-block-head">
          <div class="et-block-title">
            <b>中间件资源</b>
            <span class="et-muted">
              共 {{ resources.length }} 个 · 运行 {{ runningCount }}
              <template v-if="stoppedCount"> · 未启动 {{ stoppedCount }}（不算异常）</template>
              <template v-if="missingCount"> · 缺信息 {{ missingCount }}</template>
            </span>
          </div>
          <div class="et-block-actions">
            <el-button size="small" :icon="Search" @click="openDiscover">发现 / 导入</el-button>
            <el-button size="small" :icon="Refresh" :loading="checkingAll" @click="checkAll">检查全部</el-button>
            <el-button size="small" :icon="Plus" @click="openResourceDialog">手工添加</el-button>
          </div>
        </div>

        <div v-if="resources.length" class="et-table">
          <div class="et-row et-row-head">
            <span>类型 / 名称</span><span>地址</span><span>库 / 用户</span><span>来源</span><span>状态</span><span />
          </div>
          <div v-for="r in resources" :key="r.id" class="et-row">
            <span class="et-cell">
              <b>{{ r.name }}</b>
              <em class="et-kind">{{ kindLabel(r.kind) }}</em>
            </span>
            <code class="et-cell mono">{{ r.host }}:{{ r.port }}</code>
            <span class="et-cell et-muted">
              {{ r.databaseName || '—' }} / {{ r.username || '—' }}
            </span>
            <span class="et-cell et-muted">
              {{ r.source === 'MANUAL' ? '手工' : r.source === 'LOCAL_DOCKER' ? '发现候选·本机' : '发现候选·远程' }}
            </span>
            <span class="et-cell">
              <span
                v-if="r.status === 'MISSING'" class="et-state danger"
                :title="`缺少连接信息：${r.missingFields.join('、')}`"
              >缺{{ r.missingFields.join('/') }}</span>
              <span
                v-else-if="r.status === 'RUNNING'" class="et-state ok"
                :title="r.source === 'LOCAL_DOCKER' || r.source === 'REMOTE_DOCKER' ? `容器 ${r.name} 运行中` : '端口可连通'"
              >运行中</span>
              <span
                v-else-if="r.status === 'STOPPED'" class="et-state idle"
                title="容器未启动不算异常；部署时会自动启动项目引用的中间件"
              >已停止</span>
              <span v-else class="et-state idle" title="还没检查过运行状态">未检查</span>
            </span>
            <span class="et-cell et-actions">
              <button
                v-if="r.source === 'LOCAL_DOCKER' || r.source === 'REMOTE_DOCKER'"
                type="button" class="et-icon"
                :title="r.running ? '停止容器' : '启动容器'"
                :disabled="!!runtimeBusyId" @click="toggleRunning(r)"
              >
                <el-icon :class="{ 'et-spin': runtimeBusyId === r.id }">
                  <VideoPause v-if="r.running" /><VideoPlay v-else />
                </el-icon>
              </button>
              <button
                type="button" class="et-icon" title="检查健康"
                :disabled="checkingAll || checkingId === r.id" @click="checkOne(r)"
              >
                <el-icon :class="{ 'et-spin': checkingId === r.id }"><Refresh /></el-icon>
              </button>
              <button type="button" class="et-icon danger" title="删除" @click="removeResource(r)">
                <el-icon><Delete /></el-icon>
              </button>
            </span>
          </div>
        </div>
        <p v-else class="et-empty">还没有登记中间件；点「发现 / 导入」从本机或远程 docker 导入，或手工添加。</p>
      </div>

      <!-- 变量 -->
      <div class="et-block">
        <div class="et-block-head">
          <div class="et-block-title">
            <b>模板变量</b>
            <span class="et-muted">
              共 {{ vars.length }} 个 · 部署时注入所有项目 · 中间件账号密码已自动保存，可点眼睛查看
            </span>
          </div>
          <el-button size="small" :icon="Plus" @click="openVarDialog">添加变量</el-button>
        </div>
        <div v-if="vars.length" class="et-table et-table-vars">
          <div class="et-row et-row-head">
            <span>变量名</span><span>值</span><span>类型 / 来源</span><span />
          </div>
          <div v-for="v in vars" :key="v.key" class="et-row et-row-var">
            <code class="mono et-key" :title="v.key">{{ v.key }}</code>
            <code
              class="mono et-value" :class="{ revealed: revealed[v.key] !== undefined }"
              :title="varDisplay(v)"
            >{{ varDisplay(v) }}</code>
            <span class="et-cell">
              <span class="et-state" :class="v.secret ? 'warn' : 'idle'">{{ v.secret ? '敏感' : '明文' }}</span>
              <span class="et-state idle">{{ sourceLabel(v.source) }}</span>
            </span>
            <span class="et-cell et-actions">
              <!-- 敏感值才需要解密查看；明文变量给「复制」，避免点了没变化像失效 -->
              <el-tooltip
                v-if="v.secret"
                :content="!canReveal ? '需要管理员权限才能查看明文'
                  : revealed[v.key] !== undefined ? '隐藏明文' : '查看明文（写审计日志）'"
                placement="top" :show-after="200"
              >
                <span class="et-tip-wrap">
                  <button
                    type="button" class="et-icon"
                    :disabled="!canReveal || revealingKey === v.key" @click="toggleReveal(v)"
                  >
                    <el-icon :class="{ 'et-spin': revealingKey === v.key }">
                      <Hide v-if="revealed[v.key] !== undefined" /><View v-else />
                    </el-icon>
                  </button>
                </span>
              </el-tooltip>
              <el-tooltip v-else content="复制值" placement="top" :show-after="200">
                <span class="et-tip-wrap">
                  <button type="button" class="et-icon" @click="copyValue(v)">
                    <el-icon><DocumentCopy /></el-icon>
                  </button>
                </span>
              </el-tooltip>
              <el-tooltip content="删除变量" placement="top" :show-after="200">
                <span class="et-tip-wrap">
                  <button type="button" class="et-icon danger" @click="removeVar(v)">
                    <el-icon><Delete /></el-icon>
                  </button>
                </span>
              </el-tooltip>
            </span>
          </div>
        </div>
        <p v-else class="et-empty">还没有模板变量；点「添加变量」配置 DB_HOST、TOKEN_SECRET 等。</p>
      </div>
    </div>

    <!-- 发现 -->
    <el-dialog v-model="discoverDialog" title="发现中间件" width="720px">
      <div class="et-discover-bar">
        <el-radio-group v-model="discoverSource" size="small">
          <el-radio-button label="local">本机 Docker</el-radio-button>
          <el-radio-button label="remote">远程服务器</el-radio-button>
        </el-radio-group>
        <el-select
          v-if="discoverSource === 'remote'" v-model="discoverServerId"
          size="small" placeholder="选择服务器" style="width: 220px"
        >
          <el-option v-for="s in servers" :key="s.id" :label="s.name || s.host" :value="s.id" />
        </el-select>
        <el-button size="small" type="primary" :icon="Search" :loading="discovering" @click="runDiscover">
          开始扫描
        </el-button>
      </div>

      <el-checkbox-group v-model="selectedIds" class="et-candidates">
        <label v-for="c in candidates" :key="c.candidateId" class="et-candidate">
          <el-checkbox :label="c.candidateId" />
          <span class="et-candidate-main">
            <b>{{ c.name }}</b>
            <em>{{ kindLabel(c.kind) }}</em>
            <code class="mono">{{ c.host }}:{{ c.port }}</code>
            <span v-if="c.network" class="et-muted">网络 {{ c.network }}</span>
          </span>
          <span class="et-state" :class="c.running ? 'ok' : 'idle'">{{ c.running ? '运行中' : '已停止' }}</span>
        </label>
      </el-checkbox-group>
      <p v-if="!discovering && !candidates.length" class="et-empty">
        还没有扫描结果。选择来源后点「开始扫描」。
      </p>

      <template #footer>
        <el-button @click="discoverDialog = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="importSelected">
          导入所选（{{ selectedIds.length }}）
        </el-button>
      </template>
    </el-dialog>

    <!-- 手工添加中间件 -->
    <el-dialog v-model="resourceDialog" title="手工添加中间件" width="520px">
      <div class="et-dialog-grid">
        <label class="et-field">
          <span>类型</span>
          <el-select v-model="resourceForm.kind" size="small">
            <el-option v-for="k in ['mysql', 'postgres', 'redis', 'mongo', 'nacos', 'rabbitmq', 'other']"
              :key="k" :label="kindLabel(k)" :value="k" />
          </el-select>
        </label>
        <label class="et-field">
          <span>名称</span>
          <el-input v-model="resourceForm.name" size="small" placeholder="例如 mysql-test" />
        </label>
        <label class="et-field">
          <span>主机</span>
          <el-input v-model="resourceForm.host" size="small" />
        </label>
        <label class="et-field">
          <span>端口</span>
          <el-input-number v-model="resourceForm.port" size="small" :min="0" :max="65535" controls-position="right" />
        </label>
        <label class="et-field">
          <span>库名（可选）</span>
          <el-input v-model="resourceForm.databaseName" size="small" />
        </label>
        <label class="et-field">
          <span>用户名（可选）</span>
          <el-input v-model="resourceForm.username" size="small" />
        </label>
        <label class="et-field et-field-wide">
          <span>密码（可选，加密保存）</span>
          <el-input v-model="resourceForm.password" size="small" type="password" show-password />
        </label>
      </div>
      <template #footer>
        <el-button @click="resourceDialog = false">取消</el-button>
        <el-button type="primary" :loading="resourceSaving" @click="saveResource">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加变量 -->
    <el-dialog v-model="varDialog" title="添加模板变量" width="480px">
      <div class="et-dialog-grid">
        <label class="et-field et-field-wide">
          <span>变量名</span>
          <el-input v-model="varForm.key" size="small" class="mono" placeholder="例如 DB_HOST" />
        </label>
        <label class="et-field et-field-wide">
          <span>值</span>
          <el-input v-model="varForm.value" size="small" type="password" show-password class="mono" />
        </label>
        <label class="et-switch">
          <el-switch v-model="varForm.secret" />
          <span>敏感（列表默认掩码，可点眼睛查看）</span>
        </label>
      </div>
      <template #footer>
        <el-button @click="varDialog = false">取消</el-button>
        <el-button type="primary" :loading="varSaving" @click="saveVar">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.env-template-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.et-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.et-title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.et-sub {
  margin: 0;
  max-width: 720px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-faint);
}
.et-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}
.et-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  transition: border-color var(--dur-fast) ease, background-color var(--dur-fast) ease;
}
.et-card:hover {
  background: var(--surface-hover);
}
.et-card.active {
  background: var(--panel);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.et-card.danger {
  box-shadow: inset 3px 0 0 var(--danger);
}
.et-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  color: var(--ink);
}
.et-card-line {
  font-size: 11px;
  color: var(--ink-faint);
}
.et-badge {
  padding: 1px 7px;
  font-size: 10px;
  border-radius: var(--radius-pill);
}
.et-badge.ok {
  color: var(--success, #16a34a);
  background: var(--success-soft, rgb(22 163 74 / 12%));
}
.et-badge.danger {
  color: var(--danger);
  background: var(--danger-soft);
}
.et-detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  background: var(--surface-sunken);
  border-radius: var(--radius-md);
}
.et-detail-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: 13px;
  color: var(--ink);
}
.et-muted {
  font-size: 11px;
  color: var(--ink-faint);
}
.et-form-grid {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
}
.et-field {
  display: flex;
  flex: 0 1 190px;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  color: var(--ink-faint);
}
.et-field-wide {
  flex-basis: 100%;
}
.et-form-actions {
  padding-bottom: 1px;
}
.et-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}
.et-block-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.et-block-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink);
}
.et-block-actions {
  display: flex;
  gap: 6px;
}
.et-table {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.et-row {
  display: grid;
  grid-template-columns: minmax(130px, 1.2fr) minmax(120px, 1fr) minmax(110px, 1fr) 64px 84px 100px;
  gap: 10px;
  align-items: center;
  padding: 7px 10px;
  font-size: 11.5px;
  border-top: 1px solid var(--line-soft);
}
.et-row:first-child {
  border-top: 0;
}
.et-row-head {
  font-size: 10.5px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
}
/* 变量表：表头与数据行必须共用同一套列定义，否则列头会整体错位 */
.et-table-vars .et-row {
  grid-template-columns: minmax(120px, 1fr) minmax(160px, 1.5fr) minmax(110px, 1fr) 64px;
}
.et-key,
.et-value {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.et-value {
  color: var(--ink-faint);
}
/* 显示明文后给明显的视觉反馈，避免「点了像没反应」 */
.et-value.revealed {
  padding: 2px 6px;
  font-weight: 600;
  color: var(--ink);
  background: var(--accent-soft);
  border-radius: var(--radius-xs);
}
.et-tip-wrap {
  display: inline-flex;
}
.et-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.et-kind {
  padding: 0 5px;
  font-size: 10px;
  font-style: normal;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
}
.et-state {
  padding: 1px 7px;
  font-size: 10px;
  border-radius: var(--radius-pill);
}
.et-state.idle {
  color: var(--ink-faint);
  background: var(--surface-sunken);
}
.et-state.ok {
  color: var(--success, #16a34a);
  background: var(--success-soft, rgb(22 163 74 / 12%));
}
.et-state.warn {
  color: var(--warn);
  background: var(--warn-soft);
}
.et-state.danger {
  color: var(--danger);
  background: var(--danger-soft);
}
.et-actions {
  justify-content: flex-end;
}
.et-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.et-icon:hover {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.et-icon:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.et-icon:disabled:hover {
  color: var(--ink-faint);
  background: none;
}
.et-icon.danger:hover {
  color: var(--danger);
  background: var(--danger-soft);
}
.et-spin {
  animation: et-spin 0.9s linear infinite;
}
@keyframes et-spin {
  to { transform: rotate(360deg); }
}
.et-empty {
  margin: 0;
  padding: 14px 10px;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.et-discover-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.et-candidates {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 320px;
  overflow-y: auto;
}
.et-candidate {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.et-candidate-main {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  font-size: 12px;
}
.et-candidate-main em {
  font-size: 10px;
  font-style: normal;
  color: var(--ink-faint);
}
.et-dialog-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.et-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 4px;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  user-select: none;
}
@media (max-width: 900px) {
  .et-cards {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
