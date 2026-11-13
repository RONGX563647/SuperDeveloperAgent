<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, DocumentCopy, Edit, Hide, Key, Plus, Refresh, View } from '@element-plus/icons-vue'
import { ApiError } from '../../api/http'
import { listProjects, type ProjectRow } from '../../api/project'
import {
  checkProjectEnv, deleteEnvOverride, getEffectiveEnvironment,
  getLocalServiceLogs, listLocalServices, listPolicies, listPortLeases, listProjectSecrets,
  resolveProjectConfig, restartLocalService, revealEffectiveEnvVar, saveEnvOverride,
  startLocalService, stopLocalService,
  type ConfigCandidateView, type ConfigResolutionView, type EffectiveEnvironmentView,
  type EnvCheckView, type EnvPolicyView, type EnvVariableView, type LocalServiceView,
  type PortLeaseView, type ProjectSecret, type SourceEnvVariableView,
} from '../../api/settings'
import { appStore } from '../../stores/app'
import { formatTime } from '../../utils/status'
import { envLabel } from '../../utils/pipelineLabels'

/**
 * 项目环境（覆盖 + 注入视图）。
 *
 * <p>「设置 → 环境模板」提供 dev/test/prod 的默认变量与中间件；这里只写项目差异，
 * 右侧展示的是部署时真正注入的合并结果（模板 + 中间件 + 覆盖 + 源码 .env），
 * 与实际部署读取同一份数据，避免同一批变量在多张表里各列一遍。
 *
 * <p>「重新检测」会重扫当前环境并对比上一次结果，用来排查"某个环境的变量/来源对不上"。
 */
const ENVS = ['dev', 'test', 'prod']

const projects = ref<ProjectRow[]>([])
const projectId = ref('')
const env = ref(appStore.env)
const effective = ref<EffectiveEnvironmentView | null>(null)
const envCheck = ref<EnvCheckView | null>(null)
const leases = ref<PortLeaseView[]>([])
const services = ref<LocalServiceView[]>([])
const serviceBusy = ref('')
const loading = ref(false)
const saving = ref(false)
/** 已点眼睛查看的变量明文（source:key → 明文）。 */
const revealed = ref<Record<string, string>>({})
const revealingKey = ref('')

/** 查看敏感值明文需要 settings:write 或 settings:reveal；管理员为 * 通配。 */
const canReveal = computed(() => {
  const permissions = appStore.identity.permissions
  return permissions.includes('*')
    || permissions.includes('settings:write')
    || permissions.includes('settings:reveal')
})

const formOpen = ref(false)
const editing = ref<EnvRowView | null>(null)
const form = reactive({ key: '', value: '', secret: true })
/** 值的写法：直接填明文 / 引用密钥库（secret://名称）。 */
const formMode = ref<'value' | 'ref'>('value')
const formRefName = ref('')
/** 当前项目环境的密钥（只含名称与版本）。 */
const secrets = ref<ProjectSecret[]>([])

/** 配置解析弹窗：展示「项目环境优先 + 全量扫描候选」，缺失时提示需要用户回答。 */
const resolveOpen = ref(false)
const resolving = ref(false)
const resolution = ref<ConfigResolutionView | null>(null)
/** 正在采纳的候选变量名（按钮 loading）。 */
const adopting = ref('')

/** 分环境策略矩阵（只读，由后端代码默认值提供）。 */
const policies = ref<Record<string, EnvPolicyView>>({})
const currentPolicy = computed(() => policies.value[env.value] ?? null)
/** 当前环境 L0–L3 的可执行级别，用于顶部一句话说明。 */
const policyRows = computed(() => {
  const p = currentPolicy.value
  if (!p) return []
  return (['L0', 'L1', 'L2', 'L3'] as const).map((level) => ({
    level,
    decision: p[level],
    text: p[level] === 'ALLOW' ? '允许' : p[level] === 'CONFIRM' ? '需确认' : '禁止',
  }))
})

const importOpen = ref(false)
const importText = ref('')
const importSecret = ref(true)
const importing = ref(false)

/** 「重新检测」进行中：重扫源码 .env / 中间件 / 自检，并对比与上一次的差异。 */
const detecting = ref(false)
/** 最近一次检测结果（常驻在变量列表上方，比一闪而过的 toast 更好核对）。 */
const detectNote = ref('')

const projectName = computed(() =>
  projects.value.find((p) => p.id === projectId.value)?.name ?? projectId.value)

const vars = computed<EnvVariableView[]>(() => effective.value?.vars ?? [])
/** 项目源码里声明的变量：部署时自动注入，人工覆盖后以覆盖值为准。 */
const sourceVars = computed<SourceEnvVariableView[]>(() => effective.value?.sourceVars ?? [])
/** 健康只看被项目变量引用的资源；未引用（环境共享/其它项目）不计入分母。 */
const referencedResources = computed(() =>
  (effective.value?.resources ?? []).filter((r) => r.referenced !== false))
const referencedTotal = computed(() =>
  effective.value?.referencedCount ?? referencedResources.value.length)
const runningCount = computed(() =>
  effective.value?.referencedRunningCount
  ?? referencedResources.value.filter((r) => r.status === 'RUNNING').length)
const missingResources = computed(() =>
  referencedResources.value.filter((r) => r.status === 'MISSING'))
const stoppedResources = computed(() =>
  referencedResources.value.filter((r) => r.status === 'STOPPED' || r.status === 'UNKNOWN'))

/** 列表行：把「有效变量」与「源码 .env 变量」合并成一张部署注入清单，避免两张表重复列同一批键。 */
interface EnvRowView {
  key: string
  value: string
  secret: boolean
  source: 'TEMPLATE' | 'RESOURCE' | 'OVERRIDE' | 'SOURCE'
  updatedAt: number
  /** 源码 .env 里也声明了同名变量（部署以当前行为准） */
  alsoSource: boolean
}

const rows = computed<EnvRowView[]>(() => {
  const sourceKeys = new Set(sourceVars.value.map((v) => v.key))
  const list: EnvRowView[] = vars.value.map((v) => ({
    key: v.key,
    value: v.value,
    secret: v.secret,
    source: v.source,
    updatedAt: v.updatedAt,
    alsoSource: sourceKeys.has(v.key),
  }))
  const known = new Set(list.map((r) => r.key))
  for (const s of sourceVars.value) {
    if (known.has(s.key)) continue
    list.push({
      key: s.key,
      value: s.value,
      secret: s.secret,
      source: 'SOURCE',
      updatedAt: 0,
      alsoSource: false,
    })
  }
  return list
})

/** 各来源计数：头部一句话说明「这些变量从哪来」。 */
const sourceCounts = computed(() => {
  const counts = { RESOURCE: 0, OVERRIDE: 0, TEMPLATE: 0, SOURCE: 0 }
  for (const row of rows.value) counts[row.source] += 1
  return counts
})

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
  if (!projectId.value) return
  loading.value = true
  try {
    effective.value = await getEffectiveEnvironment(projectId.value, env.value)
    revealed.value = {}
    try {
      envCheck.value = await checkProjectEnv(projectId.value, env.value)
    } catch {
      envCheck.value = null
    }
    try {
      leases.value = await listPortLeases(projectId.value, env.value)
    } catch {
      leases.value = []
    }
    try {
      secrets.value = await listProjectSecrets(projectId.value, env.value)
    } catch {
      secrets.value = []
    }
    try {
      policies.value = (await listPolicies()).policies
    } catch {
      policies.value = {}
    }
    await loadServices()
  } catch (e) {
    effective.value = null
    envCheck.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载项目环境失败')
  } finally {
    loading.value = false
  }
}

/**
 * 重新检测：重扫当前环境的源码 .env、中间件与注入前自检，并把结果和上一次对比。
 * 后端每次都是现读磁盘，所以这里除了重新拉取，还会明确告诉用户“变没变”。
 */
async function redetect(): Promise<void> {
  if (detecting.value || !projectId.value) return
  detecting.value = true
  const before = new Map(rows.value.map((row) => [row.key, row.source]))
  try {
    await load()
    if (!effective.value) return
    const added = rows.value.filter((row) => !before.has(row.key)).length
    const removed = [...before.keys()].filter((key) => !rows.value.some((row) => row.key === key)).length
    const changed = rows.value.filter((row) =>
      before.has(row.key) && before.get(row.key) !== row.source).length
    const delta = added || removed || changed
      ? `，新增 ${added} / 消失 ${removed} / 来源变化 ${changed}`
      : '，与上次一致'
    const check = envCheck.value
    const checkText = !check
      ? '，自检无结果'
      : check.ok ? '，自检通过' : `，自检未通过：${check.problems[0] ?? ''}`
    const c = sourceCounts.value
    const now = new Date()
    const at = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
    detectNote.value = `重新检测于 ${at}：${rows.value.length} 个变量`
      + `（中间件 ${c.RESOURCE} · 覆盖 ${c.OVERRIDE} · 模板 ${c.TEMPLATE} · 源码 .env ${c.SOURCE}）`
      + `${delta}${checkText}`
  } catch (e) {
    detectNote.value = `重新检测失败：${e instanceof Error ? e.message : '未知错误'}`
  } finally {
    detecting.value = false
  }
}

// ---------------------------------------------------------------- dev 本地服务

/** 只有 dev 形态由平台托管本机进程；其它环境走容器部署。 */
async function loadServices(): Promise<void> {
  if (env.value !== 'dev' || !projectId.value) {
    services.value = []
    return
  }
  try {
    services.value = await listLocalServices(projectId.value, env.value)
  } catch {
    services.value = []
  }
}

/** 静默刷新：轮询用，不闪 loading、失败不清空已有数据。 */
async function refreshServices(): Promise<void> {
  if (env.value !== 'dev' || !projectId.value || serviceBusy.value) return
  try {
    services.value = await listLocalServices(projectId.value, env.value)
  } catch {
    // 轮询失败保留上一次结果
  }
}

/** dev 页停留期间每 6 秒刷新一次进程状态（页面隐藏时跳过，避免无意义请求）。 */
let serviceTimer: number | undefined
function startServicePolling(): void {
  stopServicePolling()
  serviceTimer = window.setInterval(() => {
    if (!document.hidden) void refreshServices()
  }, 6000)
}
function stopServicePolling(): void {
  if (serviceTimer !== undefined) {
    window.clearInterval(serviceTimer)
    serviceTimer = undefined
  }
}

async function serviceAction(service: string, action: 'start' | 'stop' | 'restart'): Promise<void> {
  if (!projectId.value || serviceBusy.value) return
  serviceBusy.value = service
  try {
    const result = action === 'start'
      ? await startLocalService(projectId.value, service, env.value)
      : action === 'stop'
        ? await stopLocalService(projectId.value, service, env.value)
        : await restartLocalService(projectId.value, service, env.value)
    if (result.ok) ElMessage.success(result.message || '操作成功')
    else ElMessage.error(result.message || '操作失败')
    services.value = result.services
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
    await refreshServices()
  } finally {
    serviceBusy.value = ''
  }
}

function serviceLabel(service: string): string {
  return service === 'backend' ? '后端' : '前端'
}

/** 状态文案与色调：EXTERNAL 表示端口被非平台进程占用，需要换端口或先停掉那个进程。 */
function statusView(status: LocalServiceView['status']): { text: string, tone: 'ok' | 'warn' | 'muted' } {
  if (status === 'RUNNING') return { text: '运行中', tone: 'ok' }
  if (status === 'STOPPED') return { text: '已停止', tone: 'muted' }
  if (status === 'EXTERNAL') return { text: '端口被占用', tone: 'warn' }
  return { text: '未启动', tone: 'muted' }
}

const backendRunning = computed(() =>
  services.value.some((s) => s.service === 'backend' && s.status === 'RUNNING'))

/** 前端提示：后端没跑时页面打开也调不通接口。 */
function serviceHint(s: LocalServiceView): string {
  if (!s.supported) return s.hint || '当前仓库缺少可启动的产物'
  if (s.service === 'frontend' && !backendRunning.value) return `${s.hint}；后端未运行，页面将无法调用接口`
  return s.hint
}

// ---------------------------------------------------------------- 本地服务日志

const logOpen = ref(false)
const logTitle = ref('')
const logLines = ref<string[]>([])
const logLoading = ref(false)
const logService = ref('')

async function openLogs(service: string): Promise<void> {
  if (!projectId.value) return
  logService.value = service
  logTitle.value = `${serviceLabel(service)}运行日志`
  logOpen.value = true
  await loadLogs()
}

async function loadLogs(): Promise<void> {
  if (!projectId.value || !logService.value) return
  logLoading.value = true
  try {
    const result = await getLocalServiceLogs(projectId.value, logService.value, env.value, 200)
    logLines.value = result.lines
  } catch (e) {
    logLines.value = [e instanceof Error ? e.message : '读取日志失败']
  } finally {
    logLoading.value = false
  }
}

function startCreate(): void {
  editing.value = null
  form.key = ''
  form.value = ''
  form.secret = true
  formMode.value = 'value'
  formRefName.value = ''
  formOpen.value = true
}

/** 当前行是密钥引用时，编辑态直接切到「引用密钥」并选中对应名称。 */
function applyRefMode(row: EnvRowView): void {
  const match = /^secret:\/\/([A-Za-z0-9._-]{1,128})$/.exec(row.value)
  formMode.value = match ? 'ref' : 'value'
  formRefName.value = match ? match[1] : ''
}

function startEdit(row: EnvRowView): void {
  editing.value = row
  form.key = row.key
  form.value = ''
  form.secret = row.secret
  applyRefMode(row)
  formOpen.value = true
}

/** 用项目覆盖当前行：源码 / 模板 / 中间件来源的行都走这里。 */
function startOverride(row: EnvRowView): void {
  editing.value = null
  form.key = row.key
  form.value = ''
  form.secret = row.secret
  applyRefMode(row)
  formOpen.value = true
}

/** 复制源码里明文变量的值；敏感变量只返回掩码，复制没有意义。 */
async function copyValue(row: EnvRowView): Promise<void> {
  try {
    await navigator.clipboard.writeText(varDisplay(row))
    ElMessage.success(`已复制 ${row.key}`)
  } catch {
    ElMessage.warning(`复制失败，请手动选中 ${row.key} 的值`)
  }
}

function closeForm(): void {
  formOpen.value = false
  editing.value = null
}

async function save(): Promise<void> {
  const key = form.key.trim()
  if (!key) {
    ElMessage.warning('变量名必填')
    return
  }
  if (formMode.value === 'ref' && !formRefName.value) {
    ElMessage.warning('请选择要引用的密钥，或先到「设置 → 密钥库」新建')
    return
  }
  if (formMode.value === 'value' && !form.value) {
    ElMessage.warning('请填写值（覆盖模板默认值）')
    return
  }
  const value = formMode.value === 'ref' ? `secret://${formRefName.value}` : form.value
  const secret = formMode.value === 'ref' ? true : form.secret
  saving.value = true
  try {
    await saveEnvOverride(projectId.value, {
      env: env.value,
      key,
      value,
      secret,
    })
    ElMessage.success('已保存项目覆盖')
    closeForm()
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

/** 重新解析当前项目/环境（弹窗内采纳候选后也要刷新）。 */
async function refreshResolution(): Promise<void> {
  if (!projectId.value) return
  resolving.value = true
  try {
    resolution.value = await resolveProjectConfig(projectId.value, env.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '配置解析失败')
    resolution.value = null
  } finally {
    resolving.value = false
  }
}

/** 配置解析：项目环境优先，其次仓库声明，最后全量扫描候选。 */
async function openResolve(): Promise<void> {
  if (!projectId.value) {
    ElMessage.warning('请先选择项目')
    return
  }
  resolveOpen.value = true
  resolution.value = null
  await refreshResolution()
  if (!resolution.value) resolveOpen.value = false
}

/**
 * 采纳扫描候选：写为项目覆盖（人工点击即确认，Agent 不会自动调用）。
 * 候选值是密钥时不允许采纳——必须到「密钥库」新建后用 secret:// 引用。
 */
async function adoptCandidate(key: string, option: ConfigCandidateView): Promise<void> {
  if (!projectId.value || option.secret || !option.value) return
  adopting.value = key
  try {
    await saveEnvOverride(projectId.value, {
      env: env.value, key, value: option.value, secret: false
    })
    ElMessage.success(`已采纳 ${key} 到项目环境`)
    await load()
    await refreshResolution()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '采纳失败')
  } finally {
    adopting.value = ''
  }
}

async function remove(v: EnvRowView): Promise<void> {
  const ok = await ElMessageBox.confirm(
    `取消 ${v.key} 的项目覆盖？删除后会回退到模板值（若模板也没有则不注入）。`,
    '删除项目覆盖',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
  ).then(() => true).catch(() => false)
  if (!ok) return
  try {
    await deleteEnvOverride(projectId.value, env.value, v.key)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

/** 点眼睛查看敏感变量最终注入值的明文；再点收起。明文变量不需要解密。 */
async function toggleReveal(v: EnvRowView): Promise<void> {
  const id = `${v.source}:${v.key}`
  if (revealed.value[id] !== undefined) {
    const next = { ...revealed.value }
    delete next[id]
    revealed.value = next
    return
  }
  if (!canReveal.value) {
    ElMessage.warning('当前账号没有查看明文权限，请用管理员账号登录')
    return
  }
  revealingKey.value = id
  try {
    const result = await revealEffectiveEnvVar(projectId.value, env.value, v.key)
    revealed.value = { ...revealed.value, [id]: result.value }
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

function varDisplay(v: EnvRowView): string {
  return revealed.value[`${v.source}:${v.key}`] ?? v.value
}

function sourceLabel(source: string): string {
  if (source === 'RESOURCE') return '中间件'
  if (source === 'OVERRIDE') return '项目覆盖'
  if (source === 'SOURCE') return '源码 .env'
  return '环境模板'
}

/** 解析 .env 文本：支持 export、注释、单/双引号包裹。 */
function parseEnvText(text: string): Array<{ key: string, value: string }> {
  const parsed: Array<{ key: string, value: string }> = []
  for (const raw of text.split(/\r?\n/)) {
    let line = raw.trim()
    if (!line || line.startsWith('#')) continue
    if (line.startsWith('export ')) line = line.slice('export '.length).trim()
    const eq = line.indexOf('=')
    if (eq <= 0) continue
    const key = line.slice(0, eq).trim()
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) continue
    let value = line.slice(eq + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    parsed.push({ key, value })
  }
  return parsed
}

async function importAll(): Promise<void> {
  const entries = parseEnvText(importText.value)
  if (!entries.length) {
    ElMessage.warning('没有解析到 KEY=VALUE 行')
    return
  }
  importing.value = true
  let ok = 0
  const failed: string[] = []
  for (const entry of entries) {
    try {
      await saveEnvOverride(projectId.value, {
        env: env.value,
        key: entry.key,
        value: entry.value,
        secret: importSecret.value,
      })
      ok += 1
    } catch (e) {
      failed.push(`${entry.key}: ${e instanceof Error ? e.message : String(e)}`)
    }
  }
  importing.value = false
  await load()
  if (failed.length) {
    ElMessage.warning(`导入 ${ok} 个，失败 ${failed.length} 个：${failed[0]}`)
  } else {
    ElMessage.success(`已导入 ${ok} 个变量`)
    importText.value = ''
    importOpen.value = false
  }
}

watch([projectId, env], () => {
  // 面板里切换环境也算"上一次使用的环境"，回到对话 / 流水线页时沿用。
  appStore.env = env.value
  detectNote.value = ''
  closeForm()
  if (env.value === 'dev') startServicePolling()
  else stopServicePolling()
  void load()
})

/** 用户主动切换项目时才更新全局上下文（首次加载不覆盖流水线页的选择）。 */
function onProjectChange(id: string): void {
  if (id) appStore.projectId = id
}

onMounted(async () => {
  await loadProjects()
  await load()
  if (env.value === 'dev') startServicePolling()
})

onUnmounted(stopServicePolling)

defineExpose({ load })
</script>

<template>
  <section class="env-panel" aria-label="项目环境">
    <header class="ep-head">
      <div class="ep-title-row">
        <h2 class="ep-title">项目环境</h2>
        <div class="ep-actions">
          <el-button size="small" :icon="Refresh" :loading="detecting" @click="redetect">重新检测</el-button>
          <el-button size="small" :icon="Key" @click="openResolve">配置解析</el-button>
          <el-button size="small" type="primary" :icon="Plus" @click="startCreate">添加覆盖</el-button>
        </div>
      </div>
      <p class="ep-sub">
        下面的列表是部署时真正注入的变量（环境模板 + 中间件连接信息 + 项目覆盖 + 项目源码 .env）。
        值 AES-GCM 加密落库，不进入 <code>pipeline.yml</code> / 仓库；列表默认掩码，可点眼睛查看明文。
      </p>
      <div v-if="policyRows.length" class="ep-policy" aria-label="分环境策略">
        <span class="ep-policy-label">本环境策略</span>
        <span
          v-for="row in policyRows" :key="row.level"
          class="ep-policy-chip" :class="row.decision.toLowerCase()"
        >
          {{ row.level }} {{ row.text }}
        </span>
      </div>
    </header>

    <div class="ep-context">
      <label class="ep-field">
        <span>项目</span>
        <el-select v-model="projectId" size="default" filterable placeholder="选择项目" @change="onProjectChange">
          <el-option
            v-for="p in projects" :key="p.id"
            :label="`${p.name}（${p.id}）`" :value="p.id"
          />
        </el-select>
      </label>
      <label class="ep-field">
        <span>环境</span>
        <el-select v-model="env" size="default">
          <el-option v-for="e in ENVS" :key="e" :label="envLabel(e)" :value="e" />
        </el-select>
      </label>
      <span class="ep-count">
        {{ rows.length }} 个注入变量
        <template v-if="effective">
          （中间件 {{ sourceCounts.RESOURCE }} / 覆盖 {{ sourceCounts.OVERRIDE }}
          / 模板 {{ sourceCounts.TEMPLATE }} / 源码 .env {{ sourceCounts.SOURCE }}）
        </template>
      </span>
    </div>

    <p v-if="detectNote" class="ep-detect-note">{{ detectNote }}</p>

    <div v-if="effective" class="ep-summary">
      <span class="ep-chip">网络 {{ effective.network || '默认' }}</span>
      <span class="ep-chip">端口 {{ effective.defaultPort || '未配置' }}</span>
      <span class="ep-chip">健康路径 {{ effective.healthPath || '默认' }}</span>
      <span class="ep-chip" :class="{ danger: missingResources.length > 0 }">
        中间件 {{ runningCount }}/{{ referencedTotal }} 运行
      </span>
      <span v-if="stoppedResources.length" class="ep-chip">
        未启动 {{ stoppedResources.length }} 个（部署时会自动启动）
      </span>
      <span v-if="envCheck?.sourceProvidedVars?.length" class="ep-chip">
        源码已提供 {{ envCheck.sourceProvidedVars.join('、') }}
      </span>
      <span v-if="missingResources.length" class="ep-warn">
        中间件缺少连接信息：{{ missingResources.map((r) => `${r.name}(${r.missingFields.join('/')})`).join('、') }}，
        部署会被 E-701 拦截，请到「环境模板」补齐账号密码
      </span>
      <span v-if="envCheck && !envCheck.ok" class="ep-warn">
        注入前自检未通过：{{ envCheck.problems.join('；') }}
      </span>
      <span v-else-if="envCheck" class="ep-chip ok">
        自检通过<template v-if="envCheck.requiredCount">（{{ envCheck.requiredCount }} 个必填变量齐全）</template>
      </span>
      <span v-if="leases.length" class="ep-lease">
        端口租约：{{ leases.map((l) => `${l.containerPort}→${l.hostPort}`).join('，') }}
      </span>
    </div>

    <!-- dev：平台托管的本机前后端进程与端口 -->
    <div v-if="env === 'dev'" class="ep-services">
      <div class="ep-services-head">
        <b>本地服务</b>
        <span class="ep-services-hint">
          平台分配端口并启动 / 停止本机前后端进程；注入的环境变量与容器部署完全一致。
        </span>
        <el-button
          class="ep-services-refresh" size="small" text :icon="Refresh"
          :disabled="serviceBusy !== ''" @click="refreshServices"
        >
          刷新
        </el-button>
      </div>
      <div class="ep-service-list">
        <div v-for="s in services" :key="s.service" class="ep-service">
          <span class="ep-service-name">{{ serviceLabel(s.service) }}</span>
          <a
            v-if="s.url && s.status === 'RUNNING'" class="ep-service-url mono"
            :href="s.url" target="_blank" rel="noopener"
          >{{ s.url }}</a>
          <code v-else class="mono ep-service-url">{{ s.url || '未分配端口' }}</code>
          <span
            class="ep-chip" :class="{
              ok: statusView(s.status).tone === 'ok',
              warn: statusView(s.status).tone === 'warn',
            }"
          >
            {{ serviceBusy === s.service ? '处理中…' : statusView(s.status).text }}
          </span>
          <span v-if="s.pid" class="ep-service-pid">pid {{ s.pid }}</span>
          <span class="ep-service-actions">
            <el-button
              size="small" :loading="serviceBusy === s.service"
              :disabled="(!s.supported && s.status !== 'RUNNING') || (!!serviceBusy && serviceBusy !== s.service)"
              :title="s.supported ? '' : s.hint"
              @click="serviceAction(s.service, s.status === 'RUNNING' ? 'stop' : 'start')"
            >
              {{ s.status === 'RUNNING' ? '停止' : '启动' }}
            </el-button>
            <el-button
              v-if="s.status === 'RUNNING'" size="small" text
              :disabled="!!serviceBusy" @click="serviceAction(s.service, 'restart')"
            >
              重启
            </el-button>
            <el-button
              size="small" text :disabled="!!serviceBusy" @click="openLogs(s.service)"
            >
              日志
            </el-button>
          </span>
          <p v-if="serviceHint(s)" class="ep-service-hint" :class="{ warn: !s.supported }">
            {{ serviceHint(s) }}
          </p>
        </div>
        <p v-if="!services.length" class="ep-services-hint">
          还没有端口租约；点「启动」会自动分配端口并拉起进程（需要项目内有可运行的后端 jar / 前端 npm 工程）。
        </p>
      </div>
    </div>

    <!-- 本地服务日志（启动即退出时看这里） -->
    <el-dialog v-model="logOpen" :title="logTitle" width="760px" append-to-body>
      <div class="ep-log-toolbar">
        <span class="ep-services-hint">最近 200 行，来源：平台本地运行目录</span>
        <el-button size="small" text :loading="logLoading" :icon="Refresh" @click="loadLogs">刷新</el-button>
      </div>
      <pre v-if="logLines.length" class="ep-log mono">{{ logLines.join('\n') }}</pre>
      <p v-else class="ep-services-hint">暂无日志：服务还未由平台启动过。</p>
    </el-dialog>

    <div class="ep-table" v-loading="loading">
      <div class="ep-row ep-row-head">
        <span>变量名</span>
        <span>值</span>
        <span>来源</span>
        <span>类型</span>
        <span>更新</span>
        <span />
      </div>
      <div v-for="v in rows" :key="`${v.source}:${v.key}`" class="ep-row">
        <code class="ep-key mono">{{ v.key }}</code>
        <code
          class="ep-value mono" :class="{ revealed: revealed[`${v.source}:${v.key}`] !== undefined }"
          :title="varDisplay(v)"
        >
          {{ varDisplay(v) }}
        </code>
        <span
          class="ep-source"
          :class="v.source === 'OVERRIDE' ? 'override'
            : v.source === 'RESOURCE' ? 'resource' : v.source === 'SOURCE' ? 'source' : 'template'"
        >
          {{ sourceLabel(v.source) }}
        </span>
        <span class="ep-secret" :class="v.secret ? 'secret' : 'plain'">
          {{ v.secret ? '敏感' : '明文' }}
        </span>
        <span class="ep-updated">
          <template v-if="v.source === 'SOURCE'">部署时注入</template>
          <template v-else>
            {{ formatTime(v.updatedAt) }}<template v-if="v.alsoSource"> · 源码亦有</template>
          </template>
        </span>
        <span class="ep-actions">
          <!-- 源码 .env 的敏感值无法解密，只给覆盖；明文可直接复制 -->
          <template v-if="v.source === 'SOURCE'">
            <button v-if="!v.secret" type="button" class="ep-icon" title="复制源码值" @click="copyValue(v)">
              <el-icon><DocumentCopy /></el-icon>
            </button>
            <button type="button" class="ep-icon" title="用项目覆盖此值" @click="startOverride(v)">
              <el-icon><Edit /></el-icon>
            </button>
          </template>
          <template v-else>
            <!-- 敏感值才需要解密查看；明文变量给「复制」，避免点了没变化像失效 -->
            <button
              v-if="v.secret"
              type="button" class="ep-icon"
              :title="!canReveal ? '需要管理员权限才能查看明文'
                : revealed[`${v.source}:${v.key}`] !== undefined ? '隐藏明文' : '查看明文（写审计日志）'"
              :disabled="!canReveal || revealingKey === `${v.source}:${v.key}`" @click="toggleReveal(v)"
            >
              <el-icon :class="{ 'ep-spin': revealingKey === `${v.source}:${v.key}` }">
                <Hide v-if="revealed[`${v.source}:${v.key}`] !== undefined" /><View v-else />
              </el-icon>
            </button>
            <button v-else type="button" class="ep-icon" title="复制值" @click="copyValue(v)">
              <el-icon><DocumentCopy /></el-icon>
            </button>
            <button
              v-if="v.source === 'OVERRIDE'" type="button" class="ep-icon" title="编辑覆盖"
              @click="startEdit(v)"
            >
              <el-icon><Edit /></el-icon>
            </button>
            <button
              v-else type="button" class="ep-icon" title="用项目覆盖此值"
              @click="startOverride(v)"
            >
              <el-icon><Edit /></el-icon>
            </button>
            <button
              v-if="v.source === 'OVERRIDE'" type="button" class="ep-icon danger" title="删除覆盖"
              @click="remove(v)"
            >
              <el-icon><Delete /></el-icon>
            </button>
          </template>
        </span>
      </div>
      <p v-if="!loading && !rows.length" class="ep-empty">
        <el-icon><Key /></el-icon>
        {{ projectName }} / {{ envLabel(env) }} 还没有环境变量；先到「环境模板」配置默认值，
        或点「添加覆盖」配置项目专属值。
      </p>
      <p v-else-if="loading" class="ep-empty">加载中…</p>
    </div>

    <div class="ep-import">
      <button type="button" class="ep-link" @click="importOpen = !importOpen">
        {{ importOpen ? '收起批量导入' : '批量导入 .env（作为项目覆盖）' }}
      </button>
      <div v-if="importOpen" class="ep-import-body">
        <textarea
          v-model="importText"
          class="ep-textarea mono"
          rows="6"
          placeholder="每行一个 KEY=VALUE，支持 # 注释与 export 前缀：&#10;DB_HOST=10.0.0.12&#10;TOKEN_SECRET=xxxxxx"
        />
        <div class="ep-import-actions">
          <label class="ep-secret-toggle">
            <el-switch v-model="importSecret" />
            <span>全部标记为敏感</span>
          </label>
          <el-button size="small" type="primary" :loading="importing" @click="importAll">
            导入
          </el-button>
        </div>
      </div>
    </div>

    <div v-if="formOpen" class="ep-form">
      <div class="ep-form-head">
        <b>{{ editing ? `编辑覆盖 ${editing.key}` : `项目覆盖 ${form.key || ''}` }}</b>
        <span v-if="editing" class="ep-current">留空表示不修改</span>
        <span v-else class="ep-current">将覆盖模板值（若无模板则新增）</span>
      </div>
      <div class="ep-form-grid">
        <label class="ep-field">
          <span>变量名</span>
          <el-input
            v-model="form.key" size="default" :disabled="!!editing"
            placeholder="例如 DB_HOST" class="mono"
          />
        </label>
        <label class="ep-field">
          <span>写法</span>
          <el-radio-group v-model="formMode" size="small">
            <el-radio-button label="value">直接填值</el-radio-button>
            <el-radio-button label="ref">引用密钥</el-radio-button>
          </el-radio-group>
        </label>
        <label v-if="formMode === 'value'" class="ep-field">
          <span>值</span>
          <el-input
            v-model="form.value" size="default" type="password" show-password
            placeholder="变量值" class="mono"
          />
        </label>
        <label v-else class="ep-field">
          <span>密钥</span>
          <el-select v-model="formRefName" size="default" filterable placeholder="选择密钥">
            <el-option
              v-for="s in secrets" :key="s.name"
              :label="`${s.name}（v${s.version}）`" :value="s.name"
            />
          </el-select>
        </label>
        <label v-if="formMode === 'value'" class="ep-secret-toggle">
          <el-switch v-model="form.secret" />
          <span>敏感（列表默认掩码，可点眼睛查看）</span>
        </label>
        <p v-else class="ep-form-hint">
          保存为 <code>secret://{{ formRefName || '名称' }}</code>；密钥只写不读，
          没有可选密钥时先到「设置 → 密钥库」新建。
        </p>
      </div>
      <div class="ep-form-actions">
        <el-button @click="closeForm">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>

    <!-- 配置解析：项目环境优先 + 全量扫描候选；缺失/冲突时明确提示需要用户回答 -->
    <el-dialog v-model="resolveOpen" title="配置解析" width="760px" append-to-body>
      <div v-loading="resolving" class="ep-resolve">
        <template v-if="resolution">
          <div class="ep-resolve-badges">
            <span class="ep-chip" :class="resolution.status === 'READY' ? 'ok' : 'danger'">
              {{ resolution.status === 'READY' ? '配置齐全' : '需要你确认' }}
            </span>
            <span class="ep-chip">项目环境已解析 {{ Object.keys(resolution.resolved).length }}</span>
            <span class="ep-chip">扫描 {{ resolution.scannedFiles }} 个文件</span>
            <span v-if="resolution.missing.length" class="ep-chip danger">
              缺失 {{ resolution.missing.length }}
            </span>
            <span v-if="resolution.conflicts.length" class="ep-chip danger">
              冲突 {{ resolution.conflicts.length }}
            </span>
          </div>

          <el-alert
            v-if="resolution.status === 'NEED_USER_INPUT'"
            type="warning" :closable="false" show-icon
            title="缺配置或存在多个候选：Agent 会停下来向你提问，确认前不会继续"
            :description="resolution.summary"
          />

          <div v-if="resolution.missing.length" class="ep-resolve-block">
            <b>缺失（项目环境与仓库声明都没有）</b>
            <div class="ep-resolve-tags">
              <code v-for="k in resolution.missing" :key="k" class="mono">{{ k }}</code>
            </div>
          </div>

          <div v-if="resolution.conflicts.length" class="ep-resolve-block">
            <b>冲突（同一变量有多个不同候选）</b>
            <ul class="ep-resolve-list">
              <li v-for="c in resolution.conflicts" :key="c">{{ c }}</li>
            </ul>
          </div>

          <div v-if="Object.keys(resolution.resolved).length" class="ep-resolve-block">
            <b>已解析（项目环境优先，敏感值只显示掩码）</b>
            <div class="ep-resolve-table">
              <div
                v-for="(v, k) in resolution.resolved" :key="k"
                class="ep-resolve-row"
              >
                <code class="mono ep-resolve-key">{{ k }}</code>
                <code class="mono ep-resolve-value">{{ v || '—' }}</code>
                <span class="ep-resolve-source">{{ resolution.resolvedSource[k] }}</span>
              </div>
            </div>
          </div>

          <div v-if="resolution.candidates.length" class="ep-resolve-block">
            <b>扫描到的候选（不会自动写入项目环境）</b>
            <div v-for="group in resolution.candidates" :key="group.key" class="ep-cand">
              <code class="mono ep-cand-key">{{ group.key }}</code>
              <span class="ep-cand-opts">
                <span v-for="(o, i) in group.options" :key="i" class="ep-cand-opt">
                  <code class="mono">{{ o.value || '****' }}</code>
                  <span class="ep-cand-src">
                    {{ o.source }}<template v-if="o.envHint"> · {{ o.envHint }}</template>
                  </span>
                  <el-button
                    v-if="!o.secret && o.value" link type="primary" size="small"
                    :loading="adopting === group.key"
                    title="写入项目覆盖（人工确认，Agent 不会自动采纳）"
                    @click="adoptCandidate(group.key, o)"
                  >
                    采纳
                  </el-button>
                  <span v-else-if="o.secret" class="ep-cand-src" title="密钥需到密钥库新建后引用">
                    需建密钥
                  </span>
                </span>
              </span>
            </div>
          </div>
        </template>
        <p v-else-if="!resolving" class="ep-services-hint">没有解析结果。</p>
      </div>
      <template #footer>
        <el-button @click="resolveOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.env-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ep-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ep-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.ep-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}
.ep-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
.ep-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--ink);
}
.ep-sub {
  margin: 0;
  max-width: 46rem;
  font-size: var(--text-xs);
  line-height: var(--leading-base);
  color: var(--ink-muted);
}
.ep-sub code {
  padding: 0 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
}
.ep-policy {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}
.ep-policy-label {
  font-size: 11px;
  color: var(--ink-faint);
}
.ep-policy-chip {
  padding: 1px 7px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.ep-policy-chip.allow {
  color: var(--ok-ink);
  background: color-mix(in srgb, var(--ok) 10%, transparent);
  border-color: transparent;
}
.ep-policy-chip.confirm {
  color: var(--warn-ink);
  background: var(--warn-soft);
  border-color: transparent;
}
.ep-policy-chip.deny {
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 10%, transparent);
  border-color: transparent;
}
.ep-context {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
  padding: 10px 12px;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.ep-field {
  display: flex;
  flex: 0 1 240px;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  color: var(--ink-faint);
}
.ep-count {
  margin-left: auto;
  padding-bottom: 6px;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.ep-detect-note {
  margin: 0;
  padding: 4px 8px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-soft);
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.ep-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
}
.ep-chip {
  padding: 2px 9px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-pill);
}
.ep-chip.danger {
  color: var(--danger-ink);
  background: var(--danger-soft);
}
.ep-chip.ok {
  color: var(--ok-ink);
  background: var(--ok-soft);
}
.ep-chip.warn {
  color: var(--warn-ink);
  background: var(--warn-soft);
}
.ep-warn {
  flex: 1 1 100%;
  margin: 0;
  padding: 6px 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: var(--radius-sm);
}
.ep-lease {
  font-size: 11px;
  color: var(--ink-faint);
}
.ep-services {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  background: var(--surface-sunken);
  border-radius: var(--radius-md);
}
.ep-services-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink);
}
.ep-services-hint {
  margin: 0;
  font-size: 11px;
  color: var(--ink-faint);
}
.ep-services-refresh {
  margin-left: auto;
}
.ep-service-hint {
  flex: 1 1 100%;
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-faint);
}
.ep-service-hint.warn {
  color: var(--danger);
}
.ep-log-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.ep-log {
  max-height: 55vh;
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--ink-muted);
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--surface-sunken);
  border-radius: var(--radius-sm);
}
.ep-service-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ep-service {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  font-size: 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.ep-service-name {
  min-width: 3em;
  font-weight: 600;
  color: var(--ink);
}
.ep-service-url {
  color: var(--ink-muted);
}
a.ep-service-url {
  text-decoration: none;
  border-bottom: 1px dashed currentcolor;
}
a.ep-service-url:hover {
  color: var(--accent, #2563eb);
}
.ep-service-pid {
  font-size: 11px;
  color: var(--ink-faint);
}
.ep-service-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
}
.ep-table {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.ep-row {
  display: grid;
  grid-template-columns: minmax(120px, 1.1fr) minmax(140px, 1.4fr) 76px 56px minmax(110px, 1fr) 96px;
  gap: 10px;
  align-items: center;
  padding: 8px 12px;
  font-size: 12px;
  border-top: 1px solid var(--line-soft);
}
.ep-row-head {
  font-size: 10.5px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-top: 0;
}
.ep-key {
  overflow: hidden;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ep-value {
  overflow: hidden;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 显示明文后给明显的视觉反馈，避免「点了像没反应」 */
.ep-value.revealed {
  padding: 2px 6px;
  font-weight: 600;
  color: var(--ink);
  background: var(--accent-soft);
  border-radius: var(--radius-xs);
}
.ep-source {
  justify-self: start;
  padding: 1px 7px;
  font-size: 10px;
  border-radius: var(--radius-pill);
}
.ep-source.template {
  color: var(--ink-muted);
  background: var(--surface-sunken);
}
.ep-source.override {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.ep-source.resource {
  color: var(--warn);
  background: var(--warn-soft);
}
.ep-source.source {
  color: var(--ok);
  background: var(--ok-soft);
}
.ep-source.missing {
  color: var(--danger);
  background: var(--danger-soft);
}
.ep-secret {
  justify-self: start;
  padding: 1px 7px;
  font-size: 10px;
  border-radius: var(--radius-pill);
}
.ep-secret.secret {
  color: var(--warn);
  background: var(--warn-soft);
}
.ep-secret.plain {
  color: var(--ink-muted);
  background: var(--surface-sunken);
}
.ep-updated {
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ep-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
}
.ep-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.ep-icon:hover {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.ep-icon.danger:hover {
  color: var(--danger);
  background: var(--danger-soft);
}
.ep-icon:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.ep-icon:disabled:hover {
  color: var(--ink-faint);
  background: none;
}
.ep-spin {
  animation: ep-spin 0.9s linear infinite;
}
@keyframes ep-spin {
  to { transform: rotate(360deg); }
}
.ep-empty {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 20px 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.ep-import {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ep-link {
  align-self: flex-start;
  padding: 0;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
  text-decoration: underline dotted;
}
.ep-import-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ep-textarea {
  width: 100%;
  padding: 8px 10px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink);
  resize: vertical;
  background: var(--code-bg);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
  outline: none;
}
.ep-textarea:focus {
  border-color: rgb(77 130 247 / 55%);
}
.ep-import-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}
.ep-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  background: var(--panel);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--radius-md);
}
.ep-form-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  font-size: 12.5px;
  color: var(--ink);
}
.ep-current {
  font-size: 11px;
  color: var(--ink-faint);
}
.ep-form-grid {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
}
.ep-form-hint {
  flex: 1 1 240px;
  padding-bottom: 7px;
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-faint);
}
.ep-form-hint code {
  padding: 1px 4px;
  font-size: 10.5px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-xs);
}
.ep-secret-toggle {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  padding-bottom: 7px;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  user-select: none;
}
.ep-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
/* 配置解析弹窗 */
.ep-resolve {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 80px;
  /* 全量扫描候选可能很多，弹窗内部滚动，避免撑破页面。 */
  max-height: 62vh;
  overflow-y: auto;
}
/* 解析摘要本身是多行文本（project/env/missing/ask），保留换行便于直接阅读。 */
.ep-resolve :deep(.el-alert__description) {
  white-space: pre-line;
  font-family: var(--font-mono);
  font-size: 11.5px;
}
.ep-resolve-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.ep-resolve-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--ink-soft);
}
.ep-resolve-block > b {
  font-size: 11.5px;
  color: var(--ink);
}
.ep-resolve-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ep-resolve-tags code {
  padding: 2px 7px;
  font-size: 11px;
  color: var(--danger);
  background: color-mix(in srgb, var(--danger) 10%, transparent);
  border-radius: var(--radius-pill);
}
.ep-resolve-list {
  padding-left: 18px;
  margin: 0;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-muted);
}
.ep-resolve-table {
  overflow: hidden;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.ep-resolve-row {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) minmax(180px, 1.4fr) 90px;
  gap: 10px;
  padding: 6px 10px;
  font-size: 11.5px;
  border-top: 1px solid var(--line-soft);
}
.ep-resolve-row:first-child {
  border-top: 0;
}
.ep-resolve-key {
  color: var(--ink);
}
.ep-resolve-value {
  overflow: hidden;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ep-resolve-source {
  color: var(--ink-faint);
}
.ep-cand {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 0;
  font-size: 11.5px;
  border-top: 1px dashed var(--line-soft);
}
.ep-cand:first-of-type {
  border-top: 0;
}
.ep-cand-key {
  flex: 0 0 150px;
  color: var(--ink);
}
.ep-cand-opts {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 10px;
}
.ep-cand-opt {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
}
.ep-cand-opt code {
  font-size: 11px;
  color: var(--accent-ink);
}
.ep-cand-src {
  font-size: 10.5px;
  color: var(--ink-faint);
}
</style>
