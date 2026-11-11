<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, DocumentCopy, Edit, Hide, Key, Lock, Plus, Refresh, View } from '@element-plus/icons-vue'
import { ApiError } from '../../api/http'
import { listProjects, type ProjectRow } from '../../api/project'
import {
  checkProjectEnv, confirmEnvBinding, deleteEnvOverride, draftEnvBinding, getEffectiveEnvironment,
  getEnvBinding, getLocalServiceLogs, listLocalServices, listPortLeases, restartLocalService,
  revealEffectiveEnvVar, saveEnvOverride, startLocalService, stopLocalService,
  type EffectiveEnvironmentView, type EnvBindingView, type EnvCheckView, type EnvVariableView,
  type LocalServiceView, type PortLeaseView, type SourceEnvVariableView,
} from '../../api/settings'
import { appStore } from '../../stores/app'
import { formatTime } from '../../utils/status'
import { envLabel } from '../../utils/pipelineLabels'

/**
 * 项目环境（覆盖 + 有效视图 + Agent 绑定）。
 *
 * <p>「设置 → 环境模板」提供 dev/test/prod 的默认变量与中间件；这里只写项目差异，
 * 右侧展示的是部署时真正注入的合并结果（模板 + 覆盖），与实际部署读取同一份数据。
 *
 * <p>Agent 触发部署前必须有人确认一份映射草案（bindingId），避免模型擅自把变量指向错环境。
 */
const ENVS = ['dev', 'test', 'prod']
const BINDING_KEY = 'agent-env-binding'

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
const editing = ref<EnvVariableView | null>(null)
const form = reactive({ key: '', value: '', secret: true })

const importOpen = ref(false)
const importText = ref('')
const importSecret = ref(true)
const importing = ref(false)

const binding = ref<EnvBindingView | null>(null)
const bindingBusy = ref(false)
/** 人工确认采用"两步点击"，避免误点直接把映射交给 Agent。 */
const confirmArmed = ref(false)

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

interface BindingKey {
  key: string
  source: string
  secret: boolean
  required?: boolean
  /** 值摘要：非空表示项目源码或有效环境里已有该变量；只在绑定记录里留哈希，不留明文 */
  valueHash?: string
}

/** 解析草案；missing 由后端按「必填且无值」计算，旧草案缺失该字段时返回 null。 */
function parseBindingMapping(): { keys: BindingKey[], missing: string[] | null } {
  if (!binding.value) return { keys: [], missing: null }
  try {
    const parsed = JSON.parse(binding.value.mappingJson) as {
      keys?: BindingKey[]
      missing?: string[]
    }
    return {
      keys: parsed.keys ?? [],
      missing: Array.isArray(parsed.missing) ? parsed.missing : null,
    }
  } catch {
    return { keys: [], missing: null }
  }
}

const bindingMapping = computed(parseBindingMapping)
const bindingKeys = computed(() => bindingMapping.value.keys)
/** 来自项目源码 .env 的变量：上方「项目源码变量」已逐条展示，这里只给一行汇总，避免重复列表。 */
const bindingSourceKeys = computed(() =>
  bindingKeys.value.filter((k) => bindingSourceOf(k).cls === 'source'))
/** 其它来源（覆盖 / 中间件 / 模板 / 未配置）仍逐条列出，便于核对是否错指环境。 */
const bindingOtherKeys = computed(() =>
  bindingKeys.value.filter((k) => bindingSourceOf(k).cls !== 'source'))

/** 真正缺值：优先用后端 missing；旧草案回退为「未知来源且没有值哈希」。 */
const bindingMissing = computed(() => {
  const { keys, missing } = bindingMapping.value
  if (missing) return keys.filter((k) => missing.includes(k.key))
  return keys.filter((k) => k.source === 'UNKNOWN' && !k.valueHash)
})

/** 草案里的来源标签：源码 .env 里的值不会出现在有效变量里，但部署时会自动注入。 */
function bindingSourceOf(k: BindingKey): { label: string, cls: string } {
  if (k.source === 'OVERRIDE') return { label: '项目覆盖', cls: 'override' }
  if (k.source === 'RESOURCE') return { label: '中间件', cls: 'resource' }
  if (k.source === 'TEMPLATE') return { label: '环境模板', cls: 'template' }
  if (k.source === 'SOURCE' || k.valueHash) return { label: '源码 .env', cls: 'source' }
  return { label: '未配置', cls: 'missing' }
}

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
    await loadServices()
    restoreBinding()
  } catch (e) {
    effective.value = null
    envCheck.value = null
    ElMessage.error(e instanceof Error ? e.message : '加载项目环境失败')
  } finally {
    loading.value = false
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

/** 只恢复"当前项目 + 当前环境"的绑定，避免把别的环境确认结果带进来。 */
function restoreBinding(): void {
  const raw = sessionStorage.getItem(BINDING_KEY)
  if (!raw) {
    binding.value = null
    return
  }
  try {
    const stored = JSON.parse(raw) as EnvBindingView
    if (stored.projectId === projectId.value && stored.env === env.value) {
      binding.value = stored
      void refreshBinding(stored.id)
    } else {
      binding.value = null
    }
  } catch {
    binding.value = null
  }
}

async function refreshBinding(id: string): Promise<void> {
  try {
    const latest = await getEnvBinding(projectId.value, id)
    binding.value = latest
  } catch {
    binding.value = null
    sessionStorage.removeItem(BINDING_KEY)
  }
}

function startCreate(): void {
  editing.value = null
  form.key = ''
  form.value = ''
  form.secret = true
  formOpen.value = true
}

function startEdit(v: EnvVariableView): void {
  editing.value = v
  form.key = v.key
  form.value = ''
  form.secret = v.secret
  formOpen.value = true
}

function startOverride(v: EnvVariableView): void {
  editing.value = null
  form.key = v.key
  form.value = ''
  form.secret = v.secret
  formOpen.value = true
}

/** 源码变量没有 EnvVariableView 的元数据，覆盖时只需要变量名与敏感标记。 */
function startOverrideSource(v: SourceEnvVariableView): void {
  editing.value = null
  form.key = v.key
  form.value = ''
  form.secret = v.secret
  formOpen.value = true
}

/** 复制源码里明文变量的值；敏感变量只显示掩码，复制没有意义。 */
async function copySourceValue(v: SourceEnvVariableView): Promise<void> {
  try {
    await navigator.clipboard.writeText(v.value)
    ElMessage.success(`已复制 ${v.key}`)
  } catch {
    ElMessage.warning(`复制失败，请手动选中 ${v.key} 的值`)
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
  if (!form.value) {
    ElMessage.warning('请填写值（覆盖模板默认值）')
    return
  }
  saving.value = true
  try {
    await saveEnvOverride(projectId.value, {
      env: env.value,
      key,
      value: form.value,
      secret: form.secret,
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

async function remove(v: EnvVariableView): Promise<void> {
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
async function toggleReveal(v: EnvVariableView): Promise<void> {
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
  return revealed.value[`${v.source}:${v.key}`] ?? v.value
}

function sourceLabel(source: string): string {
  if (source === 'RESOURCE') return '中间件'
  if (source === 'OVERRIDE') return '项目覆盖'
  return '环境模板'
}

/** 解析 .env 文本：支持 export、注释、单/双引号包裹。 */
function parseEnvText(text: string): Array<{ key: string, value: string }> {
  const rows: Array<{ key: string, value: string }> = []
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
    rows.push({ key, value })
  }
  return rows
}

async function importAll(): Promise<void> {
  const rows = parseEnvText(importText.value)
  if (!rows.length) {
    ElMessage.warning('没有解析到 KEY=VALUE 行')
    return
  }
  importing.value = true
  let ok = 0
  const failed: string[] = []
  for (const row of rows) {
    try {
      await saveEnvOverride(projectId.value, {
        env: env.value,
        key: row.key,
        value: row.value,
        secret: importSecret.value,
      })
      ok += 1
    } catch (e) {
      failed.push(`${row.key}: ${e instanceof Error ? e.message : String(e)}`)
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

// ---------------------------------------------------------------- Agent 绑定

async function makeDraft(): Promise<void> {
  bindingBusy.value = true
  confirmArmed.value = false
  try {
    const draft = await draftEnvBinding(projectId.value, env.value)
    binding.value = draft
    sessionStorage.setItem(BINDING_KEY, JSON.stringify(draft))
    if (!draft.mappingJson.includes('"key"')) {
      ElMessage.info('项目里没有 .env.example，草案为空；可直接确认（运行仍用当前有效变量）')
    } else {
      ElMessage.success('已生成映射草案，请核对后确认')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '生成草案失败')
  } finally {
    bindingBusy.value = false
  }
}

async function confirmBinding(): Promise<void> {
  if (!binding.value) return
  if (!confirmArmed.value) {
    confirmArmed.value = true
    return
  }
  confirmArmed.value = false
  bindingBusy.value = true
  try {
    const confirmed = await confirmEnvBinding(projectId.value, binding.value.id)
    binding.value = confirmed
    sessionStorage.setItem(BINDING_KEY, JSON.stringify(confirmed))
    ElMessage.success(`已确认，绑定 ${confirmed.id}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '确认失败')
  } finally {
    bindingBusy.value = false
  }
}

async function copyBinding(): Promise<void> {
  if (!binding.value) return
  try {
    await navigator.clipboard.writeText(binding.value.id)
    ElMessage.success('bindingId 已复制')
  } catch {
    ElMessage.warning(`请手动复制：${binding.value.id}`)
  }
}

watch([projectId, env], () => {
  // 面板里切换环境也算"上一次使用的环境"，回到对话 / 流水线页时沿用。
  appStore.env = env.value
  closeForm()
  binding.value = null
  confirmArmed.value = false
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
        <el-button size="small" type="primary" :icon="Plus" @click="startCreate">添加覆盖</el-button>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
      </div>
      <p class="ep-sub">
        下面的列表是部署时真正注入的变量（环境模板 + 中间件连接信息 + 项目覆盖）。值 AES-GCM 加密落库，
        不进入 <code>pipeline.yml</code> / 仓库；列表默认掩码，可点眼睛查看明文。
      </p>
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
        {{ vars.length }} 个有效变量
        <template v-if="sourceVars.length"> · 源码 {{ sourceVars.length }} 个可读取</template>
        <template v-if="effective">（模板 {{ effective.templateCount }} / 覆盖 {{ effective.overrideCount }}）</template>
      </span>
    </div>

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
      <div v-for="v in vars" :key="`${v.source}:${v.key}`" class="ep-row">
        <code class="ep-key mono">{{ v.key }}</code>
        <code
          class="ep-value mono" :class="{ revealed: revealed[`${v.source}:${v.key}`] !== undefined }"
          :title="varDisplay(v)"
        >
          {{ varDisplay(v) }}
        </code>
        <span class="ep-source" :class="v.source === 'OVERRIDE' ? 'override' : v.source === 'RESOURCE' ? 'resource' : 'template'">
          {{ sourceLabel(v.source) }}
        </span>
        <span class="ep-secret" :class="v.secret ? 'secret' : 'plain'">
          {{ v.secret ? '敏感' : '明文' }}
        </span>
        <span class="ep-updated">{{ formatTime(v.updatedAt) }}</span>
        <span class="ep-actions">
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
        </span>
      </div>
      <p v-if="!loading && !vars.length && !sourceVars.length" class="ep-empty">
        <el-icon><Key /></el-icon>
        {{ projectName }} / {{ envLabel(env) }} 还没有环境变量；先到「环境模板」配置默认值，
        或点「添加覆盖」配置项目专属值。
      </p>
      <p v-else-if="loading" class="ep-empty">加载中…</p>
    </div>

    <!-- 源码里能直接读到、部署时自动注入的变量；点「覆盖」可人工改值 -->
    <div v-if="sourceVars.length" class="ep-table ep-source-table">
      <div class="ep-row ep-row-head">
        <span>项目源码变量</span>
        <span>值</span>
        <span>来源</span>
        <span>类型</span>
        <span>说明</span>
        <span />
      </div>
      <div v-for="v in sourceVars" :key="`SOURCE:${v.key}`" class="ep-row">
        <code class="ep-key mono">{{ v.key }}</code>
        <code class="ep-value mono" :title="v.value">{{ v.value }}</code>
        <span class="ep-source source">项目源码</span>
        <span class="ep-secret" :class="v.secret ? 'secret' : 'plain'">
          {{ v.secret ? '敏感' : '明文' }}
        </span>
        <span class="ep-updated">{{ v.overridden ? '已被有效值覆盖' : '部署时自动注入' }}</span>
        <span class="ep-actions">
          <button
            v-if="!v.secret" type="button" class="ep-icon" title="复制值"
            @click="copySourceValue(v)"
          >
            <el-icon><DocumentCopy /></el-icon>
          </button>
          <button type="button" class="ep-icon" title="用项目覆盖此值" @click="startOverrideSource(v)">
            <el-icon><Edit /></el-icon>
          </button>
        </span>
      </div>
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

    <!-- Agent 绑定 -->
    <div class="ep-binding">
      <div class="ep-binding-head">
        <span class="ep-binding-title">
          <el-icon><Lock /></el-icon>
          Agent 部署绑定
        </span>
        <span v-if="binding" class="ep-binding-state" :class="binding.status === 'CONFIRMED' ? 'ok' : 'draft'">
          {{ binding.status === 'CONFIRMED' ? '已确认' : '草案待确认' }}
        </span>
      </div>
      <p class="ep-binding-desc">
        Agent 自主触发部署时，必须携带一份人工确认过的变量映射（bindingId）。
        草案只包含变量名与来源，不含任何值；来自项目 .env / .env.example 的变量显示为「源码 .env」，
        部署时会自动注入，无需在设置页重复填写。
      </p>

      <div v-if="!binding" class="ep-binding-actions">
        <el-button size="small" :loading="bindingBusy" @click="makeDraft">生成绑定草案</el-button>
      </div>

      <template v-else>
        <div class="ep-binding-meta">
          <span>ID <code class="mono">{{ binding.id }}</code></span>
          <span v-if="binding.confirmedBy">
            确认人 {{ binding.confirmedBy }} · {{ formatTime(binding.confirmedAt) }}
          </span>
          <span v-if="binding.expiresAt">有效期至 {{ formatTime(binding.expiresAt) }}</span>
          <button type="button" class="ep-link" @click="copyBinding">复制 ID</button>
        </div>

        <div v-if="bindingOtherKeys.length" class="ep-binding-table">
          <div v-for="k in bindingOtherKeys" :key="k.key" class="ep-binding-row">
            <code class="mono">{{ k.key }}</code>
            <span class="ep-source" :class="bindingSourceOf(k).cls">
              {{ bindingSourceOf(k).label }}
            </span>
            <span class="ep-secret" :class="k.secret ? 'secret' : 'plain'">
              {{ k.secret ? '敏感' : '明文' }}
            </span>
          </div>
        </div>
        <!-- 源码变量在「项目源码变量」里已有完整一行，这里不重复列出 -->
        <p v-if="bindingSourceKeys.length" class="ep-binding-hint">
          另有 {{ bindingSourceKeys.length }} 个变量来自项目源码 .env（部署时自动注入），
          已在上方「项目源码变量」中列出。
        </p>
        <p v-else-if="!bindingKeys.length" class="ep-empty">项目里没有 .env.example，草案为空；运行仍会使用当前有效变量。</p>

        <p v-if="bindingMissing.length" class="ep-warn">
          有 {{ bindingMissing.length }} 个必填变量尚未配置（源码和有效变量里都没有值）：{{ bindingMissing.map((k) => k.key).join('、') }}。
          建议先补齐再确认。
        </p>

        <p v-if="confirmArmed && binding.status !== 'CONFIRMED'" class="ep-warn">
          再次点击确认后，{{ projectName }} / {{ envLabel(env) }} 的映射将交给 Agent 使用，
          24 小时内有效；变量变更需重新生成。
          <button type="button" class="ep-link" @click="confirmArmed = false">取消</button>
        </p>

        <div class="ep-binding-actions">
          <el-button
            v-if="binding.status !== 'CONFIRMED'" size="small"
            :type="confirmArmed ? 'danger' : 'primary'"
            :loading="bindingBusy" @click="confirmBinding"
          >
            {{ confirmArmed ? '再次点击确认绑定' : '确认绑定（人工）' }}
          </el-button>
          <el-button size="small" :loading="bindingBusy" @click="makeDraft">重新生成草案</el-button>
        </div>
      </template>
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
          <span>值</span>
          <el-input
            v-model="form.value" size="default" type="password" show-password
            placeholder="变量值" class="mono"
          />
        </label>
        <label class="ep-secret-toggle">
          <el-switch v-model="form.secret" />
          <span>敏感（列表默认掩码，可点眼睛查看）</span>
        </label>
      </div>
      <div class="ep-form-actions">
        <el-button @click="closeForm">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>
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
  gap: 10px;
}
.ep-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
}
.ep-sub {
  margin: 0;
  max-width: 780px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-faint);
}
.ep-sub code {
  padding: 0 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
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
  color: var(--danger);
  background: var(--danger-soft);
}
.ep-chip.ok {
  color: var(--success, #16a34a);
  background: var(--success-soft, rgb(22 163 74 / 12%));
}
.ep-chip.warn {
  color: var(--warning, #b45309);
  background: var(--warning-soft, rgb(180 83 9 / 12%));
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
  color: var(--success, #16a34a);
  background: var(--success-soft, rgb(22 163 74 / 12%));
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
.ep-binding {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.ep-binding-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.ep-binding-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.ep-binding-state {
  padding: 1px 8px;
  font-size: 10px;
  border-radius: var(--radius-pill);
}
.ep-binding-state.ok {
  color: var(--success, #16a34a);
  background: var(--success-soft, rgb(22 163 74 / 12%));
}
.ep-binding-state.draft {
  color: var(--warn);
  background: var(--warn-soft);
}
.ep-binding-desc {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--ink-faint);
}
.ep-binding-hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.ep-binding-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 11px;
  color: var(--ink-muted);
}
.ep-binding-table {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 6px;
}
.ep-binding-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  font-size: 11.5px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.ep-binding-actions {
  display: flex;
  gap: 8px;
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
</style>
