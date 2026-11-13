<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Delete, Document, DocumentCopy, Hide, Plus, Refresh, Search, VideoPause, VideoPlay, View } from '@element-plus/icons-vue'
import { ApiError } from '../../api/http'
import {
  applyEnvDocument, checkEnvResource, deleteEnvResource, deleteEnvTemplateVar, discoverEnvResources,
  getEnvDetail, importEnvResources, importScenario, listEnvTemplates, listScenarios,
  listServers, revealEnvVar, saveEnvResource, saveEnvTemplate, saveEnvTemplateVar,
  startEnvResource, stopEnvResource,
  type DeployServer, type EnvResourceView, type EnvTemplate, type EnvTemplateRow,
  type EnvVariableView, type RuntimeMode, type ScenarioView,
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
/** 中间件类型 → 平台默认镜像（新增资源时预填，与后端供给用的镜像一致）。 */
const imageDefaults = ref<Record<string, string>>({})
/** 场景配置词条（DeepSeek / OSS / SMTP 等），Agent 通过 config_catalog 读同一份。 */
const scenarios = ref<ScenarioView[]>([])
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
  autonomy: 'AUTO' as 'AUTO' | 'REVIEW' | 'PROD',
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
    imageDefaults.value = detail.imageDefaults ?? {}
    revealed.value = {}
    settingForm.network = detail.template.network ?? ''
    settingForm.defaultTarget = detail.template.defaultTarget || 'docker'
    settingForm.defaultPort = detail.template.defaultPort || 8080
    settingForm.healthPath = detail.template.healthPath || '/healthz'
    settingForm.runtimeMode = detail.template.runtimeMode || 'LOCAL_DEV'
    settingForm.namespace = detail.template.namespace || `agent-${env.value}`
    settingForm.serverId = detail.template.serverId || ''
    settingForm.autonomy = detail.template.autonomy || 'AUTO'
    try {
      scenarios.value = await listScenarios(env.value)
    } catch {
      scenarios.value = []
    }
  } catch (e) {
    template.value = null
    resources.value = []
    vars.value = []
    scenarios.value = []
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
      autonomy: settingForm.autonomy,
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
  databaseName: '', username: '', password: '', image: '',
})

function openResourceDialog(): void {
  resourceForm.kind = 'mysql'
  resourceForm.name = ''
  resourceForm.host = '127.0.0.1'
  resourceForm.port = 3306
  resourceForm.databaseName = ''
  resourceForm.username = ''
  resourceForm.password = ''
  resourceForm.image = imageDefaults.value.mysql ?? ''
  resourceDialog.value = true
}

/** 切换类型时带出该类型的平台默认镜像（模板以镜像为身份，不跟某台机器的容器绑定）。 */
function onKindChange(kind: string): void {
  resourceForm.image = imageDefaults.value[kind] ?? ''
  const ports: Record<string, number> = {
    mysql: 3306, mariadb: 3306, postgres: 5432, redis: 6379,
    mongo: 27017, rabbitmq: 5672, minio: 9000, nacos: 8848,
  }
  resourceForm.port = ports[kind] ?? resourceForm.port
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
      image: resourceForm.image.trim() || undefined,
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

// ---------------------------------------------------------------- 场景配置词条

/** 正在导入的场景；表单键 = 变量名，值 = 用户填写（敏感项默认空）。 */
const scenarioDialog = ref(false)
const scenarioSaving = ref(false)
const activeScenario = ref<ScenarioView | null>(null)
const scenarioValues = reactive<Record<string, string>>({})

function openScenarioImport(s: ScenarioView): void {
  activeScenario.value = s
  for (const key of Object.keys(scenarioValues)) delete scenarioValues[key]
  for (const k of s.keys) {
    scenarioValues[k.key] = k.defaultValue ?? ''
  }
  scenarioDialog.value = true
}

async function saveScenario(): Promise<void> {
  const s = activeScenario.value
  if (!s) return
  const filled = Object.entries(scenarioValues).filter(([, v]) => v.trim() !== '')
  if (!filled.length) {
    ElMessage.warning('至少填写一项配置值')
    return
  }
  scenarioSaving.value = true
  try {
    const result = await importScenario(s.id, env.value, { ...scenarioValues })
    ElMessage.success(
      `已导入 ${result.imported.length} 个变量到${envLabel(env.value)}模板`
      + (result.skipped.length ? `，跳过 ${result.skipped.length} 个空值` : ''))
    scenarioDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导入失败')
  } finally {
    scenarioSaving.value = false
  }
}

/** 场景里已有的键不允许在导入框里重复填写（走「模板变量」修改值）。 */
function scenarioKeyConfigured(key: string): boolean {
  return vars.value.some((v) => v.key === key)
}

// ---------------------------------------------------------------- 全环境配置表（Excel 风格）

/**
 * 三个环境（开发 / 测试 / 生产）放在一张工作簿里在线编辑：
 * 概览按「参数 × 环境」，中间件与变量用「环境」列区分，场景词条按「词条 × 环境」矩阵。
 * 保存时把每个环境导出成平台文档格式再走同一套写回逻辑，
 * 因此「**** 保留原值、删行即删条目」的规则与后端保持一致。
 */
type BookSheet = 'overview' | 'resources' | 'vars' | 'scenarios'

interface BookResource {
  env: string
  name: string
  image: string
  host: string
  port: number
  databaseName: string
  username: string
  password: string
  network: string
}

interface BookVar {
  env: string
  key: string
  value: string
  secret: boolean
}

const SHEETS: Array<{ id: BookSheet; label: string }> = [
  { id: 'overview', label: '概览' },
  { id: 'resources', label: '中间件镜像' },
  { id: 'vars', label: '模板变量' },
  { id: 'scenarios', label: '场景词条' },
]

const OVERVIEW_FIELDS: Array<{ key: string; label: string; kind: 'text' | 'number' | 'mode' | 'target' | 'server' | 'autonomy' }> = [
  { key: 'runtimeMode', label: '运行形态', kind: 'mode' },
  { key: 'autonomy', label: '自治级别', kind: 'autonomy' },
  { key: 'namespace', label: '命名空间', kind: 'text' },
  { key: 'network', label: '部署网络', kind: 'text' },
  { key: 'defaultTarget', label: '默认部署目标', kind: 'target' },
  { key: 'defaultPort', label: '默认端口', kind: 'number' },
  { key: 'healthPath', label: '健康路径', kind: 'text' },
  { key: 'serverId', label: '绑定服务器', kind: 'server' },
]
const TARGET_OPTIONS = [
  { value: 'docker', label: '本机 Docker' },
  { value: 'host', label: '本机直接运行' },
  { value: 'server', label: '远程服务器' },
]

const bookOpen = ref(false)
const bookSheet = ref<BookSheet>('overview')
const bookLoading = ref(false)
const bookSaving = ref(false)
const bookTemplates = ref<EnvTemplate[]>([])
const bookResources = ref<BookResource[]>([])
const bookVars = ref<BookVar[]>([])
const bookScenarios = ref<ScenarioView[]>([])
const bookSnapshot = ref('')
const bookSel = ref<{ sheet: BookSheet; index: number } | null>(null)

const bookPayload = computed(() => JSON.stringify({
  t: bookTemplates.value, r: bookResources.value, v: bookVars.value,
}))
const bookDirty = computed(() => bookSnapshot.value !== '' && bookPayload.value !== bookSnapshot.value)

function sheetTotal(id: BookSheet): number {
  if (id === 'resources') return bookResources.value.length
  if (id === 'vars') return bookVars.value.length
  if (id === 'overview') return OVERVIEW_FIELDS.length
  return bookScenarios.value.reduce((n, s) => n + s.keys.length, 0)
}

const bookSheetLabel = computed(() => SHEETS.find((s) => s.id === bookSheet.value)?.label ?? '')

/** 场景词条跨场景连续编号，和 Excel 行号一致 */
const scenarioRowNumbers = computed(() => {
  const map = new Map<string, number>()
  let n = 0
  for (const s of bookScenarios.value) {
    for (const k of s.keys) map.set(`${s.id}:${k.key}`, ++n)
  }
  return map
})

function columnLetter(index: number): string {
  return String.fromCharCode(65 + index)
}

async function openBook(): Promise<void> {
  bookOpen.value = true
  await loadBook()
}

/** 返回模板页；有未保存修改时先确认。 */
async function closeBook(): Promise<void> {
  if (bookDirty.value) {
    const ok = await ElMessageBox.confirm('表格里有未保存的修改，返回后这些修改会丢失。仍要返回？', '未保存修改',
      { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' }).then(() => true).catch(() => false)
    if (!ok) return
  }
  bookOpen.value = false
  bookSel.value = null
}

async function loadBook(): Promise<void> {
  bookLoading.value = true
  try {
    const [details, catalog] = await Promise.all([
      Promise.all(ENVS.map((e) => getEnvDetail(e))),
      listScenarios(env.value),
    ])
    bookTemplates.value = details.map((d) => ({ ...d.template }))
    bookResources.value = details.flatMap((d) => d.resources.map((r) => ({
      env: d.template.env,
      name: r.name,
      image: r.image ?? d.imageDefaults[r.kind] ?? '',
      host: r.host,
      port: r.port,
      databaseName: r.databaseName ?? '',
      username: r.username ?? '',
      password: r.passwordMasked ?? '',
      network: r.network ?? '',
    })))
    bookVars.value = details.flatMap((d) => d.vars.map((v) => ({
      env: d.template.env, key: v.key, value: v.value, secret: v.secret,
    })))
    bookScenarios.value = catalog
    bookSnapshot.value = bookPayload.value
    bookSel.value = null
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载全部环境失败')
  } finally {
    bookLoading.value = false
  }
}

function switchSheet(id: BookSheet): void {
  bookSheet.value = id
  bookSel.value = null
}

function selectRow(index: number): void {
  bookSel.value = { sheet: bookSheet.value, index }
}

function isRowSelected(index: number): boolean {
  return bookSel.value?.sheet === bookSheet.value && bookSel.value.index === index
}

function addRow(): void {
  if (bookSheet.value === 'resources') {
    bookResources.value.push({
      env: env.value, name: '', image: '', host: '127.0.0.1', port: 0,
      databaseName: '', username: '', password: '', network: '',
    })
    selectRow(bookResources.value.length - 1)
    return
  }
  if (bookSheet.value === 'vars') {
    bookVars.value.push({ env: env.value, key: '', value: '', secret: false })
    selectRow(bookVars.value.length - 1)
    return
  }
  ElMessage.info('这张表按固定结构展示，不能新增行')
}

async function deleteRow(): Promise<void> {
  const sel = bookSel.value
  if (!sel || (sel.sheet !== 'resources' && sel.sheet !== 'vars')) return
  const name = sel.sheet === 'resources'
    ? bookResources.value[sel.index]?.name || '这一行'
    : bookVars.value[sel.index]?.key || '这一行'
  const ok = await ElMessageBox.confirm(`删除「${name}」？保存后会同时从平台删除。`, '删除行',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) return
  if (sel.sheet === 'resources') bookResources.value.splice(sel.index, 1)
  else bookVars.value.splice(sel.index, 1)
  bookSel.value = null
}

function templateValue(t: EnvTemplate, key: string): string {
  const v = (t as unknown as Record<string, unknown>)[key]
  return v === null || v === undefined ? '' : String(v)
}

function onTemplateInput(t: EnvTemplate, key: string, event: Event): void {
  const raw = (event.target as HTMLInputElement | HTMLSelectElement).value
  const target = t as unknown as Record<string, unknown>
  target[key] = key === 'defaultPort' ? Number(raw) || 0 : raw
}

function varValue(envName: string, key: string): string {
  return bookVars.value.find((v) => v.env === envName && v.key === key)?.value ?? ''
}

/** 场景词条矩阵里改值 = 直接改对应环境模板变量；清空即删除该键。 */
function setScenarioValue(envName: string, key: string, secret: boolean, event: Event): void {
  const value = (event.target as HTMLInputElement).value
  const existing = bookVars.value.find((v) => v.env === envName && v.key === key)
  if (existing) {
    if (value === '') bookVars.value = bookVars.value.filter((v) => v !== existing)
    else existing.value = value
    return
  }
  if (value !== '') bookVars.value.push({ env: envName, key, value, secret })
}

function cellText(value: string | number | null | undefined): string {
  return String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ')
}

/** 把某个环境的表格导出成平台文档格式（保存时走同一套解析）。 */
function markdownFor(envName: string): string {
  const t = bookTemplates.value.find((x) => x.env === envName)
  const resources = bookResources.value.filter((r) => r.env === envName)
  const vars = bookVars.value.filter((v) => v.env === envName)
  return [
    `# 环境模板 · ${envLabel(envName)}（${envName}）`, '',
    '## 概览', '',
    `- 运行形态: ${t?.runtimeMode ?? ''}`,
    `- 自治级别: ${t?.autonomy ?? ''}`,
    `- 命名空间: ${t?.namespace ?? ''}`,
    `- 部署网络: ${t?.network ?? ''}`,
    `- 默认部署目标: ${t?.defaultTarget ?? ''}`,
    `- 默认端口: ${t?.defaultPort ?? 0}`,
    `- 健康路径: ${t?.healthPath ?? ''}`,
    `- 绑定服务器: ${t?.serverId ?? ''}`,
    '', '## 中间件镜像', '',
    '| 名称 | 镜像 | 主机 | 端口 | 库名 | 用户名 | 密码 | 网络 |',
    '| --- | --- | --- | --- | --- | --- | --- | --- |',
    ...resources.map((r) => `| ${cellText(r.name)} | ${cellText(r.image)} | ${cellText(r.host)} | ${r.port || ''} | ${cellText(r.databaseName)} | ${cellText(r.username)} | ${cellText(r.password)} | ${cellText(r.network)} |`),
    '', '## 模板变量', '',
    '| 变量名 | 值 | 敏感 | 来源 |',
    '| --- | --- | --- | --- |',
    ...vars.map((v) => `| ${cellText(v.key)} | ${cellText(v.value)} | ${v.secret ? '是' : '否'} |  |`),
    '',
  ].join('\n')
}

async function saveBook(): Promise<void> {
  if (!bookDirty.value) {
    ElMessage.info('没有修改，无需保存')
    return
  }
  const ok = await ElMessageBox.confirm(
    '将按表格写回 开发 / 测试 / 生产：概览参数、中间件镜像、模板变量都以当前表格为准，删除的行会同步删除。确定保存？',
    '保存全部环境',
    { confirmButtonText: '保存', cancelButtonText: '取消', type: 'warning' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  bookSaving.value = true
  try {
    const parts: string[] = []
    for (const e of ENVS) {
      const result = await applyEnvDocument(e, markdownFor(e))
      const bits: string[] = []
      if (result.templateChanged) bits.push('概览')
      const res = result.resources
      if (res.created || res.updated || res.deleted) bits.push(`镜像 +${res.created} ~${res.updated} -${res.deleted}`)
      const v = result.vars
      if (v.created || v.updated || v.deleted) bits.push(`变量 +${v.created} ~${v.updated} -${v.deleted}`)
      if (bits.length) parts.push(`${envLabel(e)}：${bits.join('，')}`)
    }
    ElMessage.success(parts.length ? `已保存 ${parts.join('；')}` : '已保存（无变化）')
    await loadBook()
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    bookSaving.value = false
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
    <!-- 全环境配置表：Excel 风格，概览 / 镜像 / 变量 / 场景词条 四张工作表 -->
    <section v-if="bookOpen" class="xl-page" aria-label="全部环境配置表">
      <header class="xl-head">
        <div class="xl-heading">
          <el-button text :icon="ArrowLeft" class="xl-back" @click="closeBook">返回模板</el-button>
          <h2 class="et-title">全部环境配置</h2>
          <el-tag size="small" effect="plain">开发 / 测试 / 生产</el-tag>
          <el-tag v-if="bookDirty" size="small" type="warning" effect="light">有未保存修改</el-tag>
        </div>
        <div class="xl-actions">
          <el-button
            size="small" :icon="Plus"
            :disabled="bookSheet !== 'resources' && bookSheet !== 'vars'" @click="addRow"
          >新增行</el-button>
          <el-button
            size="small" :icon="Delete"
            :disabled="!bookSel || (bookSel.sheet !== 'resources' && bookSel.sheet !== 'vars')"
            @click="deleteRow"
          >删除行</el-button>
          <span class="xl-sep" />
          <el-button size="small" :icon="Refresh" :loading="bookLoading" @click="loadBook">重新加载</el-button>
          <el-button
            size="small" type="primary" :loading="bookSaving" :disabled="!bookDirty || bookLoading"
            @click="saveBook"
          >保存到平台</el-button>
        </div>
      </header>
      <p class="xl-tip">
        直接改单元格即可；点行号选中整行，敏感值显示 <code>****</code> 不改即保留原值。
      </p>
      <div class="xl-book">
        <div class="xl-tabbar">
          <button
            v-for="s in SHEETS" :key="s.id" type="button" class="xl-tab"
            :class="{ active: bookSheet === s.id }" @click="switchSheet(s.id)"
          >
            {{ s.label }}<span class="xl-tab-count">{{ sheetTotal(s.id) }}</span>
          </button>
        </div>

        <div v-loading="bookLoading" class="xl-scroll">
          <!-- 概览：参数 × 环境 -->
          <table v-if="bookSheet === 'overview'" class="xl-table">
            <colgroup>
              <col style="width: 44px"><col style="width: 150px">
              <col v-for="e in ENVS" :key="e">
            </colgroup>
            <thead>
              <tr class="xl-colhead">
                <th /><th>A</th>
                <th v-for="(e, i) in ENVS" :key="e">{{ columnLetter(i + 1) }}</th>
              </tr>
              <tr>
                <th class="xl-gutter">#</th><th class="xl-field">参数</th>
                <th v-for="e in ENVS" :key="e">{{ envLabel(e) }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(f, ri) in OVERVIEW_FIELDS" :key="f.key" :class="{ selected: isRowSelected(ri) }">
                <td class="xl-rowno" @click="selectRow(ri)">{{ ri + 1 }}</td>
                <td class="xl-field">{{ f.label }}</td>
                <td v-for="t in bookTemplates" :key="t.env">
                  <select
                    v-if="f.kind === 'mode'" class="xl-input"
                    :value="templateValue(t, f.key)" @change="onTemplateInput(t, f.key, $event)"
                  >
                    <option value="LOCAL_DEV">开发 · 本机进程 + 容器</option>
                    <option value="LOCAL_TEST">测试 · 本机容器</option>
                    <option value="SERVER">生产 · 服务器容器</option>
                  </select>
                  <select
                    v-else-if="f.kind === 'target'" class="xl-input"
                    :value="templateValue(t, f.key)" @change="onTemplateInput(t, f.key, $event)"
                  >
                    <option v-for="o in TARGET_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
                  </select>
                  <select
                    v-else-if="f.kind === 'autonomy'" class="xl-input"
                    :value="templateValue(t, f.key)" @change="onTemplateInput(t, f.key, $event)"
                  >
                    <option value="AUTO">AUTO · 自主执行</option>
                    <option value="REVIEW">REVIEW · 受控执行</option>
                    <option value="PROD">PROD · 只读</option>
                  </select>
                  <select
                    v-else-if="f.kind === 'server'" class="xl-input"
                    :value="templateValue(t, f.key)" @change="onTemplateInput(t, f.key, $event)"
                  >
                    <option value="">未绑定</option>
                    <option v-for="s in servers" :key="s.id" :value="s.id">{{ s.name || s.host }}</option>
                  </select>
                  <input
                    v-else class="xl-input" :type="f.kind === 'number' ? 'number' : 'text'"
                    :value="templateValue(t, f.key)" @input="onTemplateInput(t, f.key, $event)"
                  >
                </td>
              </tr>
            </tbody>
          </table>

          <!-- 中间件镜像：全部环境 -->
          <table v-else-if="bookSheet === 'resources'" class="xl-table">
            <colgroup>
              <col style="width: 44px"><col style="width: 96px"><col style="width: 150px">
              <col style="width: 160px"><col style="width: 130px"><col style="width: 80px">
              <col style="width: 120px"><col style="width: 130px"><col style="width: 150px">
              <col style="width: 140px">
            </colgroup>
            <thead>
              <tr class="xl-colhead">
                <th /><th v-for="(label, i) in ['环境', '名称', '镜像', '主机', '端口', '库名', '用户名', '密码', '网络']" :key="i">
                  {{ columnLetter(i + 1) }}
                </th>
              </tr>
              <tr>
                <th class="xl-gutter">#</th>
                <th>环境</th><th>名称</th><th>镜像</th><th>主机</th><th>端口</th>
                <th>库名</th><th>用户名</th><th>密码</th><th>网络</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(r, ri) in bookResources" :key="`${r.env}-${ri}`"
                :class="{ selected: isRowSelected(ri) }"
              >
                <td class="xl-rowno" @click="selectRow(ri)">{{ ri + 1 }}</td>
                <td>
                  <select v-model="r.env" class="xl-input">
                    <option v-for="e in ENVS" :key="e" :value="e">{{ envLabel(e) }}</option>
                  </select>
                </td>
                <td><input v-model="r.name" class="xl-input mono" placeholder="mysql-main" @focus="selectRow(ri)"></td>
                <td><input v-model="r.image" class="xl-input mono" placeholder="mysql:8.0" @focus="selectRow(ri)"></td>
                <td><input v-model="r.host" class="xl-input mono" @focus="selectRow(ri)"></td>
                <td><input v-model.number="r.port" class="xl-input" type="number" @focus="selectRow(ri)"></td>
                <td><input v-model="r.databaseName" class="xl-input mono" @focus="selectRow(ri)"></td>
                <td><input v-model="r.username" class="xl-input mono" @focus="selectRow(ri)"></td>
                <td><input v-model="r.password" class="xl-input mono" @focus="selectRow(ri)"></td>
                <td><input v-model="r.network" class="xl-input mono" @focus="selectRow(ri)"></td>
              </tr>
              <tr v-if="!bookResources.length">
                <td class="xl-empty" colspan="10">还没有中间件；点「新增行」填 名称 / 镜像 / 主机 / 端口 即可。</td>
              </tr>
            </tbody>
          </table>

          <!-- 模板变量：全部环境 -->
          <table v-else-if="bookSheet === 'vars'" class="xl-table">
            <colgroup>
              <col style="width: 44px"><col style="width: 96px"><col style="width: 240px">
              <col style="width: 320px"><col style="width: 90px">
            </colgroup>
            <thead>
              <tr class="xl-colhead">
                <th /><th v-for="(label, i) in ['环境', '变量名', '值', '敏感']" :key="i">{{ columnLetter(i + 1) }}</th>
              </tr>
              <tr>
                <th class="xl-gutter">#</th><th>环境</th><th>变量名</th><th>值</th><th>敏感</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(v, ri) in bookVars" :key="`${v.env}-${ri}`" :class="{ selected: isRowSelected(ri) }">
                <td class="xl-rowno" @click="selectRow(ri)">{{ ri + 1 }}</td>
                <td>
                  <select v-model="v.env" class="xl-input">
                    <option v-for="e in ENVS" :key="e" :value="e">{{ envLabel(e) }}</option>
                  </select>
                </td>
                <td><input v-model="v.key" class="xl-input mono" placeholder="DB_HOST" @focus="selectRow(ri)"></td>
                <td><input v-model="v.value" class="xl-input mono" @focus="selectRow(ri)"></td>
                <td class="xl-center">
                  <input v-model="v.secret" type="checkbox" @focus="selectRow(ri)">
                </td>
              </tr>
              <tr v-if="!bookVars.length">
                <td class="xl-empty" colspan="5">还没有模板变量；点「新增行」添加，或到「场景词条」表里填。</td>
              </tr>
            </tbody>
          </table>

          <!-- 场景词条：按场景分组，词条 × 环境 -->
          <table v-else class="xl-table">
            <colgroup>
              <col style="width: 44px"><col style="width: 300px">
              <col v-for="e in ENVS" :key="e">
            </colgroup>
            <thead>
              <tr class="xl-colhead">
                <th /><th v-for="(label, i) in ['场景 / 变量', ...ENVS.map((e) => envLabel(e))]" :key="i">
                  {{ columnLetter(i + 1) }}
                </th>
              </tr>
              <tr>
                <th class="xl-gutter">#</th><th>场景 / 变量</th>
                <th v-for="e in ENVS" :key="e">{{ envLabel(e) }}</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="s in bookScenarios" :key="s.id">
                <tr class="xl-band">
                  <td class="xl-rowno" />
                  <td class="xl-band-cell" :colspan="1 + ENVS.length">
                    {{ s.name }}<span class="xl-band-summary">{{ s.summary }}</span>
                  </td>
                </tr>
                <tr v-for="k in s.keys" :key="k.key">
                  <td class="xl-rowno">{{ scenarioRowNumbers.get(`${s.id}:${k.key}`) }}</td>
                  <td class="xl-keycell" :title="k.description">
                    <div class="xl-keyinner">
                      <span class="mono">{{ k.key }}</span>
                      <span v-if="k.required" class="xl-flag">必填</span>
                      <span v-if="k.secret" class="xl-flag">敏感</span>
                    </div>
                  </td>
                  <td v-for="e in ENVS" :key="e">
                    <input
                      class="xl-input mono"
                      :value="varValue(e, k.key)" :placeholder="k.example || '未配置'"
                      @input="setScenarioValue(e, k.key, k.secret, $event)"
                    >
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>

        <footer class="xl-status">
          <span class="xl-status-left">
            <span class="xl-dot" :class="{ dirty: bookDirty }" />
            {{ bookSheetLabel }} · {{ sheetTotal(bookSheet) }} 行
          </span>
          <span class="xl-status-hint">{{ bookDirty ? '有未保存修改' : '已与平台一致' }} · 密码与敏感值显示 ****，保留即不改</span>
        </footer>
      </div>
    </section>

    <template v-else>
    <header class="et-head">
      <div>
        <h2 class="et-title">环境模板</h2>
        <p class="et-sub">
          环境模板按<strong>镜像</strong>组织：声明这套环境要拉起哪些中间件镜像，换台机器按镜像重建即可复现；
          容器名、端口、运行状态只是运行态信息。变量与场景词条（DeepSeek / 阿里云 OSS / SMTP 等）配一次全项目共用，
          项目差异在「项目环境」里覆盖；Agent 通过 config_catalog 读取同一份词条。敏感值加密保存，列表默认掩码。
        </p>
      </div>
      <div class="et-head-actions">
        <el-button size="small" type="primary" plain :icon="Document" @click="openBook">
          全部环境表格
        </el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
      </div>
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
        <span class="et-card-line et-card-images" :title="row.images.join(' · ') || '未声明镜像'">
          <template v-if="row.images.length">
            <span class="et-img-chip mono">{{ row.images[0] }}</span>
            <span v-if="row.images.length > 1" class="et-img-chip">+{{ row.images.length - 1 }}</span>
          </template>
          <template v-else>镜像未声明</template>
        </span>
        <span class="et-card-line et-card-runtime">
          容器 运行 {{ row.runningCount }} · 未启动 {{ row.stoppedCount }}（不算异常）
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
          <span>自治级别（写权限 / 确认强度）</span>
          <el-select v-model="settingForm.autonomy" size="small">
            <el-option label="AUTO · 自主执行（dev）" value="AUTO" />
            <el-option label="REVIEW · 受控执行（test）" value="REVIEW" />
            <el-option label="PROD · 只读（prod）" value="PROD" />
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
            <b>中间件镜像</b>
            <span class="et-muted">
              {{ resources.length }} 个镜像 · 容器运行 {{ runningCount }}
              <template v-if="stoppedCount"> · 未启动 {{ stoppedCount }}（部署时会拉起）</template>
              <template v-if="missingCount"> · 缺信息 {{ missingCount }}</template>
            </span>
          </div>
          <div class="et-block-actions">
            <el-button size="small" :icon="Search" @click="openDiscover">发现 / 导入</el-button>
            <el-button size="small" :icon="Refresh" :loading="checkingAll" @click="checkAll">检查全部</el-button>
            <el-button size="small" :icon="Plus" @click="openResourceDialog">添加镜像</el-button>
          </div>
        </div>

        <div v-if="resources.length" class="et-table">
          <div class="et-row et-row-head">
            <span>镜像 / 名称</span><span>地址</span><span>库 / 用户</span><span>来源</span><span>状态</span><span />
          </div>
          <div v-for="r in resources" :key="r.id" class="et-row">
            <span class="et-cell et-cell-stack">
              <b>{{ r.name }}</b>
              <code class="et-image mono" :title="r.image ?? kindLabel(r.kind)">{{ r.image || kindLabel(r.kind) }}</code>
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

      <!-- 场景配置词条 -->
      <div class="et-block">
        <div class="et-block-head">
          <div class="et-block-title">
            <b>场景配置词条</b>
            <span class="et-muted">
              对接第三方服务的标准变量名；Agent 通过 config_catalog 读同一份目录，不自己猜键名
            </span>
          </div>
        </div>
        <div v-if="scenarios.length" class="et-scenarios">
          <article v-for="s in scenarios" :key="s.id" class="et-scenario">
            <header class="et-scenario-head">
              <div>
                <b>{{ s.name }}</b>
                <span class="et-muted"> {{ s.summary }}</span>
              </div>
              <span class="et-scenario-side">
                <span class="et-state" :class="s.configuredCount === s.keys.length ? 'ok' : 'idle'">
                  已配置 {{ s.configuredCount }}/{{ s.keys.length }}
                </span>
                <el-button size="small" type="primary" plain @click="openScenarioImport(s)">导入词条</el-button>
              </span>
            </header>
            <div class="et-scenario-keys">
              <div v-for="k in s.keys" :key="k.key" class="et-scenario-key">
                <code class="mono et-scenario-name" :title="k.description">{{ k.key }}</code>
                <span class="et-muted">{{ k.label }}</span>
                <span v-if="k.secret" class="et-state warn">敏感</span>
                <span v-if="k.required" class="et-state idle">必填</span>
                <span class="et-state" :class="scenarioKeyConfigured(k.key) ? 'ok' : 'idle'">
                  {{ scenarioKeyConfigured(k.key) ? '已配置' : '未配置' }}
                </span>
              </div>
            </div>
          </article>
        </div>
        <p v-else class="et-empty">词条目录加载失败；刷新重试。</p>
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
    <el-dialog v-model="resourceDialog" title="添加中间件镜像" width="520px">
      <div class="et-dialog-grid">
        <label class="et-field">
          <span>类型</span>
          <el-select v-model="resourceForm.kind" size="small" @change="onKindChange">
            <el-option v-for="k in ['mysql', 'postgres', 'redis', 'mongo', 'nacos', 'rabbitmq', 'minio', 'other']"
              :key="k" :label="kindLabel(k)" :value="k" />
          </el-select>
        </label>
        <label class="et-field">
          <span>名称</span>
          <el-input v-model="resourceForm.name" size="small" placeholder="例如 mysql-test" />
        </label>
        <label class="et-field et-field-wide">
          <span>镜像（模板以镜像为身份，空则用类型默认）</span>
          <el-input v-model="resourceForm.image" size="small" class="mono" placeholder="mysql:8.0" />
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

    <!-- 场景配置词条导入 -->
    <el-dialog v-model="scenarioDialog" :title="`导入场景词条 · ${activeScenario?.name ?? ''}`" width="560px">
      <p class="et-dialog-hint">
        填入的值会写入 <b>{{ envLabel(env) }}</b> 环境模板变量，所有项目共用；空值跳过。
        敏感项加密保存，导入后在「模板变量」里可查看/修改。
      </p>
      <div class="et-dialog-grid">
        <label v-for="k in activeScenario?.keys ?? []" :key="k.key" class="et-field et-field-wide">
          <span>
            <code class="mono">{{ k.key }}</code> · {{ k.label }}
            <em v-if="k.required" class="et-required">必填</em>
            <em v-if="scenarioKeyConfigured(k.key)" class="et-required ok">已配置</em>
            <br>
            <small class="et-muted">{{ k.description }}</small>
          </span>
          <el-input
            v-model="scenarioValues[k.key]" size="small" class="mono"
            :type="k.secret ? 'password' : 'text'" :show-password="k.secret"
            :placeholder="k.secret ? (k.example ?? '敏感值') : (k.defaultValue ?? k.example ?? '')"
          />
        </label>
      </div>
      <template #footer>
        <el-button @click="scenarioDialog = false">取消</el-button>
        <el-button type="primary" :loading="scenarioSaving" @click="saveScenario">导入到模板</el-button>
      </template>
    </el-dialog>

    </template>

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
.et-head-actions {
  display: flex;
  flex-shrink: 0;
  gap: 8px;
}
.et-card-images {
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  white-space: nowrap;
}
.et-img-chip {
  padding: 0 6px;
  overflow: hidden;
  font-size: 10px;
  color: var(--accent-ink);
  text-overflow: ellipsis;
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  border-radius: var(--radius-xs);
}
.et-card-runtime {
  opacity: 0.85;
}
.et-cell-stack {
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
}
.et-image {
  max-width: 100%;
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.et-scenarios {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.et-scenario {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.et-scenario-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--ink);
}
.et-scenario-head .et-muted {
  display: block;
  margin-top: 2px;
  line-height: 1.5;
}
.et-scenario-side {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
}
.et-scenario-keys {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.et-scenario-key {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}
.et-scenario-name {
  font-weight: 600;
  color: var(--ink-soft);
}
.et-dialog-hint {
  margin: 0 0 12px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}
.et-required {
  margin-left: 4px;
  font-size: 10px;
  font-style: normal;
  color: var(--warn);
}
.et-required.ok {
  color: var(--success, #16a34a);
}
.et-block-title b {
  position: relative;
  padding-left: 8px;
}
.et-block-title b::before {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 0;
  width: 2px;
  content: '';
  background: color-mix(in srgb, var(--accent) 55%, transparent);
  border-radius: 2px;
}
.et-table .et-row:not(.et-row-head):hover {
  background: var(--surface-hover);
}
.et-scenario {
  transition: border-color var(--dur-fast) ease, box-shadow var(--dur-fast) ease;
}
.et-scenario:hover {
  border-color: color-mix(in srgb, var(--accent) 35%, transparent);
  box-shadow: 0 2px 10px rgb(0 0 0 / 5%);
}
.xl-page {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: calc(100vh - 136px);
  min-height: 520px;
}
.xl-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.xl-heading {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
}
.xl-heading .et-title {
  margin: 0;
}
.xl-back {
  margin-right: 2px;
  font-size: 12px;
}
.xl-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.xl-sep {
  width: 1px;
  height: 18px;
  margin: 0 2px;
  background: var(--line-soft);
}
.xl-tip {
  margin: -2px 0 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
}
.xl-tip code {
  padding: 0 3px;
  background: var(--surface-sunken);
  border-radius: 3px;
}
.xl-book {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
  box-shadow: 0 1px 2px rgb(0 0 0 / 4%);
}
.xl-tabbar {
  display: flex;
  gap: 4px;
  padding: 8px 10px 0;
  background: var(--surface-sunken);
  border-bottom: 1px solid var(--line-soft);
}
.xl-tab {
  display: flex;
  gap: 6px;
  align-items: center;
  height: 30px;
  padding: 0 14px;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--ink-soft, var(--ink-faint));
  cursor: pointer;
  background: transparent;
  border: 1px solid transparent;
  border-top: 2px solid transparent;
  border-bottom: 0;
  border-radius: 6px 6px 0 0;
  transition: color var(--dur-fast) ease, background var(--dur-fast) ease;
}
.xl-tab:hover {
  color: var(--ink);
  background: color-mix(in srgb, var(--panel) 55%, transparent);
}
.xl-tab.active {
  margin-bottom: -1px;
  font-weight: 500;
  color: var(--ink);
  background: var(--panel);
  border-color: var(--line-soft);
  border-top-color: var(--accent);
}
.xl-tab-count {
  min-width: 18px;
  padding: 0 5px;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  color: var(--ink-faint);
  background: var(--surface-hover);
  border-radius: 8px;
}
.xl-tab.active .xl-tab-count {
  color: var(--accent);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
}
.xl-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.xl-table {
  width: 100%;
  table-layout: fixed;
  font-size: 12px;
  border-collapse: separate;
  border-spacing: 0;
}
.xl-table th,
.xl-table td {
  padding: 0;
  border-right: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
}
.xl-table thead th {
  position: sticky;
  top: 0;
  z-index: 2;
  height: 26px;
  padding: 0 8px;
  font-weight: 500;
  color: var(--ink-faint);
  text-align: left;
  background: var(--surface-sunken);
}
.xl-table thead tr:nth-child(2) th {
  top: 26px;
  font-size: 11.5px;
  color: var(--ink);
}
.xl-table .xl-colhead th {
  font-size: 10px;
  text-align: center;
  letter-spacing: 0.5px;
}
.xl-table thead .xl-colhead th:first-child,
.xl-table thead .xl-gutter {
  position: sticky;
  left: 0;
  z-index: 3;
}
.xl-table .xl-gutter,
.xl-table .xl-rowno {
  width: 44px;
  text-align: center;
  color: var(--ink-faint);
  user-select: none;
  background: var(--surface-sunken);
}
.xl-table .xl-rowno {
  position: sticky;
  left: 0;
  z-index: 1;
  cursor: pointer;
}
.xl-table tbody td {
  height: 30px;
}
.xl-table tbody tr:nth-child(even) td {
  background: color-mix(in srgb, var(--surface-sunken) 45%, transparent);
}
.xl-table .xl-field {
  padding: 0 10px;
  font-weight: 500;
  white-space: nowrap;
  background: var(--surface-sunken);
}
.xl-table tbody tr:hover td {
  background: color-mix(in srgb, var(--accent) 5%, transparent);
}
.xl-table tbody tr.selected td {
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}
.xl-table tbody tr.selected .xl-rowno {
  font-weight: 600;
  color: var(--accent);
}
.xl-table td.mono {
  padding: 0 10px;
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 11.5px;
  white-space: nowrap;
}
.xl-table td.xl-center {
  padding: 0 6px;
  text-align: center;
}
.xl-table td.xl-muted {
  padding: 0 10px;
  color: var(--ink-faint);
  white-space: nowrap;
}
.xl-table td.xl-desc {
  max-width: 260px;
  padding: 0 10px;
  overflow: hidden;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.xl-input {
  width: 100%;
  height: 30px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: transparent;
  border: 0;
  outline: none;
}
select.xl-input {
  padding-right: 4px;
  cursor: pointer;
  appearance: auto;
}
.xl-input:focus {
  background: var(--panel);
  box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--accent) 55%, transparent);
}
.xl-input.mono {
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 11.5px;
}
.xl-empty {
  padding: 28px 14px;
  color: var(--ink-faint);
  text-align: center;
}
.xl-band-cell {
  padding: 4px 10px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
  background: color-mix(in srgb, var(--accent) 6%, var(--surface-sunken));
  border-bottom: 1px solid color-mix(in srgb, var(--accent) 14%, var(--line-soft));
}
.xl-band-summary {
  margin-left: 8px;
  font-weight: 400;
  color: var(--ink-faint);
}
.xl-keyinner {
  display: flex;
  gap: 6px;
  align-items: center;
  height: 30px;
  padding: 0 10px;
  white-space: nowrap;
}
.xl-keyinner .mono {
  font-family: var(--mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 11.5px;
}
.xl-flag {
  padding: 0 3px;
  font-size: 10px;
  color: var(--ink-faint);
  border: 1px solid var(--line-soft);
  border-radius: 3px;
}
.xl-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 5px 10px;
  font-size: 11px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-top: 1px solid var(--line-soft);
}
.xl-status-left {
  display: flex;
  gap: 6px;
  align-items: center;
  white-space: nowrap;
}
.xl-dot {
  width: 6px;
  height: 6px;
  background: var(--success, #16a34a);
  border-radius: 50%;
}
.xl-dot.dirty {
  background: var(--warn);
}
.xl-status-hint {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
@media (max-width: 1000px) {
  .xl-page {
    height: auto;
  }
  .xl-scroll {
    max-height: 60vh;
  }
}
@media (max-width: 900px) {
  .et-cards {
    grid-template-columns: minmax(0, 1fr);
  }
  .et-scenarios {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
