<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Close, CopyDocument, Download, Link, RefreshRight, VideoPlay } from '@element-plus/icons-vue'
import PageHeader from '../layouts/components/PageHeader.vue'
import ResizableSplitter from '../components/project/ResizableSplitter.vue'
import TriggerBar from '../components/pipeline/TriggerBar.vue'
import EnvStateBand from '../components/pipeline/EnvStateBand.vue'
import EnvSummary from '../components/pipeline/EnvSummary.vue'
import HistoryList from '../components/pipeline/HistoryList.vue'
import StageTimeline from '../components/pipeline/StageTimeline.vue'
import RunLog from '../components/pipeline/RunLog.vue'
import {
  artifactFilePath, deleteHistory, deployImageVersion, deployImages, dryRunPipeline, getHistory, getRun, isTerminalStatus,
  listPipelines, previewPipeline, retryStage, stopLocalDeploy, triggerPipeline,
  type ArtifactView, type DryRunReportView, type HistoryItem, type ImageVersionView, type PreviewStage,
  type RunView, type StageView,
} from '../api/pipeline'
import { apiBlob } from '../api/http'
import { gitCommits, listProjects, type GitCommit, type ProjectRow } from '../api/project'
import { deployConfirm, deployEnvs, deployReplay, streamDeployAgent, type EnvStatus } from '../api/deploy'
import { confirmEnvBinding, draftEnvBinding } from '../api/settings'
import type { AgentEvent, AgentProjection } from '../types'
import { emptyAgentProjection, reduceAgentEvent } from '../state/agentEventReducer'
import { appStore } from '../stores/app'
import { useRunTracker } from '../composables/useRunTracker'
import { formatDuration, statusLabel, statusTone } from '../utils/status'
import { envLabel, envShort, formatBytes, pipelineLabel } from '../utils/pipelineLabels'

/**
 * 流水线工作台：
 *
 * 一次运行只保留三个决策：项目、环境、提交 + 一个「部署方式」开关。
 *
 * 规则：
 * 1. 环境切换真正生效（env 传给后端）；
 * 2. 部署方式只有两种：打包成镜像（docker）/ 直接部署（host）；
 *    - dev 固定「直接部署」：前后端跑在本机，可一键停止后改用 IDE 启动，不提供镜像选项；
 *    - test 默认「打包成镜像」，可切到直接部署；
 *    - prod 固定远程服务器发布（不做本机部署）。
 * 3. 运行前展示真实阶段（后端同一份定义），不再用文案骗人。
 */
const route = useRoute()
const router = useRouter()

const projectId = computed(() => appStore.projectId)
const env = computed(() => appStore.env)

interface EnvStrategy {
  key: string
  pipeline: string
  target: string
}

/** 部署方式默认值：dev 本机、test 镜像、prod 远程服务器。 */
function defaultTarget(key: string): string {
  if (key === 'dev') return 'host'
  if (key === 'prod') return 'server'
  return 'docker'
}

const ENV_STRATEGIES: EnvStrategy[] = [
  { key: 'dev', pipeline: 'release', target: 'host' },
  { key: 'test', pipeline: 'release', target: 'docker' },
  { key: 'prod', pipeline: 'release', target: 'server' },
]

const projects = ref<ProjectRow[]>([])
const projectChoices = computed<Array<{ id: string, name: string }>>(() =>
  projects.value.length
    ? projects.value.map((p) => ({ id: p.id, name: p.name }))
    : [{ id: projectId.value, name: projectId.value }])

const commits = ref<GitCommit[]>([])
const commitsLoading = ref(false)

const pipelines = ref<string[]>([])
const engine = ref('')
const pipelineId = ref('')
const sha = ref('')
const triggerBusy = ref(false)
const pipelinesLoading = ref(false)
/** 最近一次 dry-run 报告（只读预检）；代码/环境/提交变化后清空。 */
const dryRun = ref<DryRunReportView | null>(null)
const dryRunBusy = ref(false)

// ---------------------------------------------------------------- 发布 Agent
/** 发布 Agent 的事件投影：与对话共用同一份 reducer，视图只做渲染。 */
const agentProjection = shallowRef<AgentProjection>(emptyAgentProjection())
const agentOpen = ref(false)
const agentRunning = ref(false)
const agentError = ref('')
let agentController: AbortController | null = null

const agentTools = computed(() => Object.values(agentProjection.value.tools))
const agentMessages = computed(() => {
  const rows = agentProjection.value.messages.map((m) => ({
    id: m.id, role: m.role, text: m.text,
  }))
  const turn = agentProjection.value.streamingTurn
  if (turn) rows.push({ id: 'streaming', role: 'assistant', text: turn.text || '（思考中…）' })
  return rows
})
/** 生产环境不允许自动部署：必须走「测试证据 + 一次性确认」；只读角色也不能触发。 */
const canRunAgent = computed(() => {
  const id = appStore.identity
  return id.role === 'ADMIN' || id.permissions.includes('*')
    || id.permissions.includes('action:run')
})
const agentDisabled = computed(() => env.value === 'prod' || !canRunAgent.value)
const agentHint = computed(() => {
  if (!canRunAgent.value) return '当前角色没有执行权限（需要 action:run）'
  if (env.value === 'prod') return '生产环境必须走「发布到生产」（测试证据 + 一次性确认）'
  return '由发布 Agent 自动执行：自检 → 构建 → 部署 → 验证'
})

/** 发布 Agent 主流程：人工确认环境绑定 → 消费事件流 → 刷新运行记录。 */
async function runDeployAgent(): Promise<void> {
  if (agentRunning.value || !canRun.value || !shaValid.value) return
  if (agentDisabled.value) {
    ElMessage.warning(agentHint.value)
    return
  }
  try {
    await ElMessageBox.confirm(
      `将由发布 Agent 自动部署提交 ${sha.value.slice(0, 8)} 到「${envLabel(env.value)}」。\n`
      + '执行范围限定在人工确认过的环境绑定内，过程中可随时停止。',
      '发布 Agent',
      { confirmButtonText: '继续', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  // 1. 环境绑定：草稿 → 人工确认 → 只在确认范围内执行
  let bindingId = ''
  try {
    const draft = await draftEnvBinding(projectId.value, env.value)
    await ElMessageBox.confirm(
      `环境绑定\nhash: ${draft.mappingHash.slice(0, 12)}\n`
      + `生效至: ${new Date(draft.expiresAt).toLocaleString()}\n\n`
      + '确认后 Agent 才能读取该环境的实际配置。',
      '确认环境绑定',
      { confirmButtonText: '确认绑定', cancelButtonText: '取消' },
    )
    const confirmed = await confirmEnvBinding(projectId.value, draft.id)
    bindingId = confirmed.id
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '环境绑定失败')
    }
    return
  }

  // 2. 事件流
  agentOpen.value = true
  agentRunning.value = true
  agentError.value = ''
  agentProjection.value = emptyAgentProjection()
  agentController = new AbortController()
  try {
    await streamDeployAgent(
      projectId.value,
      {
        env: env.value,
        sha: sha.value,
        pipelineId: pipelineId.value || undefined,
        bindingId,
        sessionId: `deploy-${Date.now()}`,
      },
      (event: AgentEvent) => {
        agentProjection.value = reduceAgentEvent(agentProjection.value, event)
        if (event.type === 'error') {
          agentError.value = typeof event.payload?.message === 'string'
            ? event.payload.message
            : '发布 Agent 执行异常'
        }
      },
      agentController.signal,
    )
  } catch (e) {
    if (agentController && !agentController.signal.aborted) {
      agentError.value = e instanceof Error ? e.message : String(e)
    }
  } finally {
    agentRunning.value = false
    agentController = null
    void loadHistory()
    void loadEnvs()
  }
}

function stopAgent(): void {
  agentController?.abort()
  agentController = null
  agentRunning.value = false
}

const deployTarget = ref(defaultTarget(appStore.env))

const preview = ref<PreviewStage[]>([])
/** verify 流水线的预览：展示「只检查构建会跑几步、跳过什么」。 */
const verifyPreview = ref<PreviewStage[]>([])
const previewLoading = ref(false)
/** 预览请求序号：提交切换很快时丢弃旧响应，避免"选了 A 却显示 B 的命令"。 */
let previewSeq = 0

/** 本次要执行的动作：发布 / 只检查构建。 */
const action = ref<'release' | 'verify'>('release')

const envRows = ref<EnvStatus[]>([])
const envsLoading = ref(false)
/** 当前环境的镜像版本（current / previous / 历史），来自持久化版本账本。 */
const imageVersions = ref<ImageVersionView[]>([])
/** 本机 Docker 是否可用：不可用时禁止「按版本部署」，避免触发后才失败。 */
const dockerAvailable = ref(true)

const history = ref<HistoryItem[]>([])
const historyLoading = ref(false)
/** 是否还有更早的历史可加载（上一页返回满一页即认为还有）。 */
const historyHasMore = ref(false)
const HISTORY_PAGE = 50

const localStopping = ref(false)

/** 从失败阶段继续：重试进行中。 */
const retrying = ref(false)

/** 两次运行对比弹窗。 */
const compareOpen = ref(false)
const compareLoading = ref(false)
const compareCurrent = ref<RunView | null>(null)
const comparePrev = ref<RunView | null>(null)

const leftPct = ref(34)
const topError = ref('')
/** 手机端主从切换：配置区 / 运行详情；桌面端不参与布局。 */
const mobilePane = ref<'config' | 'run'>('config')

/** 当前右侧查看的运行来自哪次触发/哪条历史（运行详情本身不带 env/pipeline）。 */
const activeMeta = ref<{ env: string, pipelineId: string, sha: string } | null>(null)

const {
  run, runId, loading: runLoading, errorText, startedAt, running,
  open: openRun, attach, cancel, clear,
} = useRunTracker({ onFinished: () => { void loadHistory(); void loadEnvs(); void loadImages() } })

/** 有运行被打开/触发时，手机端自动切到「运行详情」，避免用户再多点一次。 */
watch(runId, (id) => {
  mobilePane.value = id ? 'run' : 'config'
})

/** 当前查看运行所属环境在「设置 → 项目环境」里的状态（用于展示固化脚本版本）。 */
const activeEnvRow = computed(() => {
  const target = activeMeta.value?.env ?? env.value
  return envRows.value.find((row) => row.env === target)
})

/** 本次运行的流水线定义来自哪里（Agent 固化脚本 / 仓库 / 自动生成）。 */
const definitionSourceLabel = computed(() => {
  const source = run.value?.meta?.definitionSource
  if (source === 'RECIPE') return '已固化脚本'
  if (source === 'REPO') return '仓库定义'
  if (source === 'PLATFORM') return '平台定义'
  if (source === 'GENERATED') return '自动生成'
  return ''
})

/** 定义来自固化脚本时，展示当前生效的 vN（证据运行 id 放在 title 里）。 */
const activeRecipeVersion = computed(() =>
  run.value?.meta?.definitionSource === 'RECIPE' ? activeEnvRow.value?.recipeVersion ?? 0 : 0)

/* ---------------------------------------------------------------- 计算属性 */

const envModel = computed({
  get: () => env.value,
  set: (value: string) => applyEnv(value),
})

const currentEnvRow = computed(() => envRows.value.find((row) => row.env === env.value))

const shaValid = computed(() => /^[0-9a-f]{7,40}$/.test(sha.value))

const lastGoodSha = computed(() =>
  history.value.find((h) => h.status === 'SUCCEEDED'
    && h.pipelineId === 'release'
    && h.env === env.value)?.gitSha ?? '')

const headerSubtitle = computed(() => {
  const parts = [envLabel(env.value), pipelineLabel(pipelineId.value)]
  if (preview.value.length) parts.push(`${preview.value.length} 个阶段`)
  return parts.join(' · ')
})

const runHint = computed(() => {
  if (!pipelines.value.length) {
    return '没有可用的流水线定义：到项目页「生成流水线定义」后刷新'
  }
  if (!shaValid.value) return '提交需为 7–40 位十六进制（可直接粘贴完整 sha）'
  if (!preview.value.length) return '正在读取本次会执行的阶段…'
  return ''
})

const primaryLabel = computed(() => {
  if (env.value === 'prod') return '发布到生产'
  if (env.value === 'dev') return '启动本机前后端'
  if (deployTarget.value === 'host') return '本机部署'
  if (currentEnvRow.value?.recipeVersion) return `重放到${envShort(env.value)}环境`
  return `打包并部署到${envShort(env.value)}环境`
})

/** dev 固定本机、prod 固定服务器：只有 test 需要选镜像/直接部署。 */
const showModeSwitch = computed(() => env.value === 'test')

const modeNote = computed(() => {
  if (env.value === 'dev') return '开发环境固定本机运行：后端 jar + 前端 dist，不打镜像'
  if (deployTarget.value === 'host') return '前后端直接跑在本机（后端 jar + 前端 dist）'
  if (!dockerAvailable.value) return '本机 Docker 不可用：已改为直接部署（后端 jar + 前端 dist）'
  return '构建镜像并在本机 Docker 运行'
})

/** Docker 不可用时不允许停留在「打包成镜像」：切到直接部署，并在方式旁说明原因。 */
watch([dockerAvailable, env], () => {
  if (env.value === 'test' && !dockerAvailable.value && deployTarget.value === 'docker') {
    deployTarget.value = 'host'
  }
}, { immediate: true })

/** 最近一次 dev 本机部署的前后端端口（来自 run 产物的 meta）。 */
const localDeployInfo = computed(() => {
  const dep = (run.value?.artifacts ?? []).find(
    (a) => a.kind === 'deployment' && String(a.meta?.target ?? '') === 'host')
  if (!dep) return null
  return {
    backend: Number(dep.meta?.backendPort ?? 0),
    frontend: Number(dep.meta?.frontendPort ?? 0),
    endpoint: dep.ref,
  }
})

const localHint = computed(() => {
  const info = localDeployInfo.value
  if (info && info.backend) {
    return `本机运行：后端 127.0.0.1:${info.backend}`
      + (info.frontend ? ` · 前端 127.0.0.1:${info.frontend}` : '')
      + '（可在 IDE 启动前先停止）'
  }
  return '平台托管的本机前后端可随时停止，之后用 IDE 启动'
})

/** dev 本机部署成功后的「打开前端 / 后端」入口（以产物 meta 为准，不依赖左侧当前环境）。 */
const localLinks = computed<Array<{ label: string, url: string }>>(() => {
  const info = localDeployInfo.value
  if (!info) return []
  const links: Array<{ label: string, url: string }> = []
  if (info.frontend) links.push({ label: '打开前端', url: `http://127.0.0.1:${info.frontend}` })
  if (info.backend) links.push({ label: '打开后端', url: `http://127.0.0.1:${info.backend}` })
  return links
})

/** 失败阶段（取最后一个失败/超时的阶段）——失败卡片据此展示原因与重试入口。 */
const failedStage = computed<StageView | null>(() => {
  const stages = run.value?.stages ?? []
  for (let i = stages.length - 1; i >= 0; i -= 1) {
    if (stages[i].status === 'FAILED' || stages[i].status === 'TIMEOUT') return stages[i]
  }
  return null
})

/** 失败原因涉及端口占用时，给出可操作的提示。 */
const portHint = computed(() => {
  const stage = failedStage.value
  if (!stage) return ''
  const text = `${stage.errorCode} ${stage.summary}`
  if (/port|端口|EADDRINUSE|address already in use/i.test(text)) {
    return '端口被占用：先停掉占用进程（或「停止本机服务」），再重试该阶段'
  }
  return ''
})

/** 「对比上次」的基线：同项目 + 流水线 + 环境的上一条终态运行。 */
const compareBaseline = computed<HistoryItem | null>(() => {
  if (!run.value || !activeMeta.value || running.value) return null
  const currentRunId = run.value.runId
  const current = history.value.find((h) => h.runId === currentRunId)
  const pool = history.value.filter((h) =>
    h.runId !== currentRunId
    && h.env === activeMeta.value?.env
    && h.pipelineId === activeMeta.value?.pipelineId
    && isTerminalStatus(h.status)
    && (!current || h.createdAt < current.createdAt))
  return pool[0] ?? null
})

interface StageDiff {
  id: string
  prevStatus: string
  curStatus: string
  prevMs: number
  curMs: number
  deltaMs: number
}

/** 阶段耗时/状态对比（按阶段 id 对齐）。 */
const stageDiffs = computed<StageDiff[]>(() => {
  const prev = comparePrev.value
  const cur = compareCurrent.value
  if (!prev || !cur) return []
  const ids = [...new Set([...prev.stages.map((s) => s.id), ...cur.stages.map((s) => s.id)])]
  return ids.map((id) => {
    const p = prev.stages.find((s) => s.id === id)
    const c = cur.stages.find((s) => s.id === id)
    return {
      id,
      prevStatus: p?.status ?? '—',
      curStatus: c?.status ?? '—',
      prevMs: p?.durationMs ?? 0,
      curMs: c?.durationMs ?? 0,
      deltaMs: (c?.durationMs ?? 0) - (p?.durationMs ?? 0),
    }
  })
})

/** 产物差异：按 kind + ref 对齐，得到新增 / 消失。 */
const artifactDiff = computed<{ added: ArtifactView[], removed: ArtifactView[] }>(() => {
  const prev = comparePrev.value
  const cur = compareCurrent.value
  if (!prev || !cur) return { added: [], removed: [] }
  const key = (a: ArtifactView): string => `${a.kind}|${a.ref}`
  const prevKeys = new Set(prev.artifacts.map(key))
  const curKeys = new Set(cur.artifacts.map(key))
  return {
    added: cur.artifacts.filter((a) => !prevKeys.has(key(a))),
    removed: prev.artifacts.filter((a) => !curKeys.has(key(a))),
  }
})

/** 产物面板按目录分组：同目录只显示一次目录，行内只显示文件名，避免一屏相同长路径。 */
interface ArtifactGroup {
  dir: string
  items: Array<{ a: ArtifactView, index: number }>
  /** 文件很多（如前端 dist）时折叠成一行，需要时再展开。 */
  collapsed: boolean
}

const artifactGroups = computed<ArtifactGroup[]>(() => {
  const groups = new Map<string, Array<{ a: ArtifactView, index: number }>>()
  ;(run.value?.artifacts ?? []).forEach((a, index) => {
    const parts = a.ref.replace(/\\/g, '/').split('/')
    const dir = parts.length > 1 ? parts.slice(0, -1).join('/') : ''
    const key = `${a.kind}::${dir}`
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)?.push({ a, index })
  })
  return [...groups.entries()].map(([key, items]) => ({
    dir: key.slice(key.indexOf('::') + 2),
    items,
    // dist 这类目录动辄几十个文件：折叠成 1 行，避免产物面板被文件名淹没
    collapsed: items.length > 8 && items.every((it) => it.a.kind === 'file')
  }))
})

/** 手动展开的折叠目录（key = 目录）。 */
const expandedDirs = ref<Set<string>>(new Set())

function toggleDir(dir: string): void {
  const next = new Set(expandedDirs.value)
  if (next.has(dir)) next.delete(dir)
  else next.add(dir)
  expandedDirs.value = next
}

function isDirExpanded(dir: string): boolean {
  return expandedDirs.value.has(dir)
}

function totalSize(items: Array<{ a: ArtifactView }>): number {
  return items.reduce((sum, it) => sum + (it.a.sizeBytes || 0), 0)
}

/** 文件名词：产物行主文本（完整路径仍在 title / 复制里）。 */
function fileName(ref: string): string {
  const parts = ref.replace(/\\/g, '/').split('/')
  return parts[parts.length - 1] || ref
}

/** 目录只留最后两段，前面用 … 省略（完整目录在 title 里）。 */
function shortDir(dir: string): string {
  const parts = dir.split('/').filter(Boolean)
  return parts.length <= 2 ? dir : `…/${parts.slice(-2).join('/')}`
}

/** verify 运行只记录路径与摘要，文件没有归档，下载按钮应隐藏。 */
function isArchived(a: ArtifactView): boolean {
  return a.meta?.archived !== 'false'
}

/** 镜像版本徽标文案。 */
function imageBadge(img: ImageVersionView): string {
  if (img.current) return '当前'
  if (img.previous) return '上一版'
  return '历史'
}

function imageBadgeClass(img: ImageVersionView): string {
  if (img.current) return 'is-current'
  if (img.previous) return 'is-previous'
  return ''
}

/** 版本时间：月-日 时:分（镜像谱系只需要相对顺序，不需要秒）。 */
function formatClock(ts: number): string {
  if (!ts) return ''
  const d = new Date(ts)
  const p = (n: number): string => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** dev 固定本机运行，停止入口始终可见（不依赖当前选中的部署方式）。 */
const canStopLocal = computed(() => env.value === 'dev')

const canRun = computed(() =>
  !!pipelineId.value && shaValid.value && !triggerBusy.value && !pipelinesLoading.value)

const runDuration = computed(() => {
  if (!run.value) return ''
  if (!running.value) {
    const total = run.value.stages.reduce((sum, s) => sum + s.durationMs, 0)
    return formatDuration(total)
  }
  return formatDuration(Math.max(0, nowTick.value - startedAt.value))
})

/* ---------------------------------------------------------------- 数据加载 */

async function loadPipelines(): Promise<void> {
  pipelinesLoading.value = true
  try {
    const data = await listPipelines(projectId.value)
    engine.value = data.engine
    pipelines.value = data.pipelines ?? []
    if (!pipelines.value.includes(pipelineId.value)) {
      choosePipeline()
    }
  } catch (e) {
    topError.value = `加载流水线失败：${e instanceof Error ? e.message : String(e)}`
    pipelines.value = []
    pipelineId.value = ''
  } finally {
    pipelinesLoading.value = false
  }
}

/** 按环境策略选默认流水线（用户仍可在高级选项里改）。 */
function choosePipeline(): void {
  const strategy = ENV_STRATEGIES.find((s) => s.key === env.value) ?? ENV_STRATEGIES[1]
  if (pipelines.value.includes(strategy.pipeline)) {
    pipelineId.value = strategy.pipeline
  } else if (pipelines.value.length) {
    pipelineId.value = pipelines.value[0]
  }
}

async function loadPreview(): Promise<void> {
  if (!pipelineId.value || !projectId.value) {
    preview.value = []
    verifyPreview.value = []
    return
  }
  previewLoading.value = true
  const seq = ++previewSeq
  try {
    // 发布 / 只检查构建两份预览一起取：动作卡片要显示各自步数与是否产镜像
    // 带 sha：仓库定义随提交变化，预览要按"这次会检出的提交"解析
    const [release, verify] = await Promise.all([
      previewPipeline(projectId.value, pipelineId.value, env.value, sha.value).catch(() => null),
      previewPipeline(projectId.value, 'verify', env.value, sha.value).catch(() => null),
    ])
    if (seq !== previewSeq) return
    preview.value = release?.stages ?? []
    verifyPreview.value = verify?.stages ?? []
  } finally {
    previewLoading.value = false
  }
}

async function loadEnvs(): Promise<void> {
  envsLoading.value = true
  try {
    envRows.value = await deployEnvs(projectId.value)
  } catch {
    envRows.value = []
  } finally {
    envsLoading.value = false
  }
}

/** 构建历史：reset=true 重新取第一页，否则按已加载条数往后翻。 */
async function loadHistory(reset = true, silent = false): Promise<void> {
  if (historyLoading.value && !reset) return
  if (silent && historyLoading.value) return
  if (!silent) historyLoading.value = true
  try {
    const offset = reset ? 0 : history.value.length
    const page = await getHistory(projectId.value, offset, HISTORY_PAGE)
    history.value = reset ? page : [...history.value, ...page]
    historyHasMore.value = page.length === HISTORY_PAGE
  } catch {
    if (reset && !silent) {
      history.value = []
      historyHasMore.value = false
    }
  } finally {
    if (!silent) historyLoading.value = false
  }
}

function loadMoreHistory(): void {
  void loadHistory(false)
}

/** 镜像版本列表：读失败只清空，不影响主流程。 */
async function loadImages(): Promise<void> {
  try {
    const res = await deployImages(projectId.value, env.value)
    imageVersions.value = res.images
    dockerAvailable.value = res.dockerAvailable !== false
  } catch {
    imageVersions.value = []
  }
}

/**
 * 按版本部署：把某个历史镜像重新发到当前环境。
 * 镜像已存在，后端只跑 deploy + health（不重新构建），因此秒级完成。
 */
async function deployVersion(img: ImageVersionView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将 ${img.tag}（提交 ${(img.gitSha || '').slice(0, 7)}）重新部署到 ${env.value} 环境？`
      + '\n不会重新构建：直接复用已登记镜像，只跑部署与健康检查。',
      '按版本部署',
      { type: 'warning', confirmButtonText: '部署', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    const result = await deployImageVersion(pipelineId.value, projectId.value, env.value,
      img.tag, img.gitSha)
    attachRun(result, { env: env.value, pipelineId: pipelineId.value, sha: img.gitSha })
    ElMessage.success('已触发按版本部署')
    void loadImages()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '按版本部署失败')
  }
}

async function loadProjects(): Promise<void> {
  try {
    projects.value = await listProjects()
    if (!projects.value.some((p) => p.id === projectId.value) && projects.value.length) {
      appStore.projectId = projects.value[0].id
    }
  } catch {
    projects.value = []
  }
}

async function loadCommits(): Promise<void> {
  commitsLoading.value = true
  try {
    const page = await gitCommits(projectId.value, 30)
    commits.value = page.commits
    if (page.commits.length && !page.commits.some((c) => c.sha === sha.value)) {
      sha.value = page.commits[0].sha
    }
  } catch {
    commits.value = []
  } finally {
    commitsLoading.value = false
  }
}

function applyEnv(key: string): void {
  if (!ENV_STRATEGIES.some((s) => s.key === key)) return
  appStore.env = key
  // 切环境时部署方式跟着换默认值：dev 本机、test 镜像、prod 远程服务器
  deployTarget.value = defaultTarget(key)
  choosePipeline()
}

function applyProject(id: string): void {
  appStore.projectId = id
  sha.value = ''
  void router.replace({ query: { ...route.query, projectId: id } })
}

/* ---------------------------------------------------------------- 触发 */

function attachRun(result: { runId: string, status: string },
                   meta: { env: string, pipelineId: string, sha: string }): void {
  const initial: RunView = {
    runId: result.runId,
    status: result.status,
    stages: [],
    artifacts: [],
    log: '',
  }
  attach(result.runId, initial)
  activeMeta.value = meta
  syncUrl(result.runId)
  void loadHistory()
  void loadEnvs()
}

async function triggerSelected(): Promise<void> {
  const result = await triggerPipeline(
    pipelineId.value, projectId.value, sha.value, deployTarget.value, env.value)
  attachRun(result, { env: env.value, pipelineId: pipelineId.value, sha: sha.value })
  ElMessage.success('已触发运行')
}

async function replayEnv(): Promise<void> {
  const result = await deployReplay(projectId.value, env.value, sha.value)
  attachRun(result, { env: env.value, pipelineId: 'release', sha: sha.value })
  ElMessage.success('已按环境脚本重放')
}

/** 生产发布：测试证据 + 一次性确认令牌（与后端 DeployGate 同一套规则）。 */
async function promoteProd(): Promise<void> {
  const plan = await deployConfirm(projectId.value, 'prod', sha.value)
  if (!plan.allowed) {
    ElMessage.warning(plan.reason ?? '测试环境还没有成功案例')
    return
  }
  const evidence = plan.evidence
  const ok = await ElMessageBox.confirm(
    `即将发布提交 ${sha.value.slice(0, 7)} 到生产环境。\n`
    + `依据：测试环境 v${evidence?.version ?? '?'} · 证据运行 ${evidence?.runId ?? '?'}`
    + `（${evidence?.status ?? '?'}）\n这是高危操作，确认后立即执行。`,
    '确认发布到生产',
    { confirmButtonText: '确认发布', cancelButtonText: '取消', type: 'warning' },
  ).then(() => true).catch(() => false)
  if (!ok) {
    ElMessage.info('已取消')
    return
  }
  const result = await deployReplay(
    projectId.value, 'prod', sha.value, plan.confirmToken)
  attachRun(result, { env: 'prod', pipelineId: 'release', sha: sha.value })
  ElMessage.success('已触发生产发布')
}

async function doPrimary(): Promise<void> {
  if (!canRun.value || triggerBusy.value) return
  triggerBusy.value = true
  topError.value = ''
  try {
    if (env.value === 'prod') {
      await promoteProd()
    } else if (env.value === 'dev') {
      // dev 固定本机：当前工作区代码直接构建运行，不走固化脚本重放（脚本里的 docker 步骤不适用）
      await triggerSelected()
    } else if (deployTarget.value === 'host') {
      await triggerSelected()
    } else if (currentEnvRow.value?.recipeVersion) {
      await replayEnv()
    } else {
      await triggerSelected()
    }
  } catch (e) {
    topError.value = `触发失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    triggerBusy.value = false
  }
}

/** 只做构建检查（不部署）：dev 想验证代码能编过时用。 */
async function doVerify(): Promise<void> {
  if (triggerBusy.value || !shaValid.value) return
  triggerBusy.value = true
  topError.value = ''
  try {
    const result = await triggerPipeline(
      'verify', projectId.value, sha.value, deployTarget.value, env.value)
    attachRun(result, { env: env.value, pipelineId: 'verify', sha: sha.value })
    ElMessage.success('已触发检查构建')
  } catch (e) {
    topError.value = `触发失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    triggerBusy.value = false
  }
}

/** 主按钮：按当前选择分发到「发布」或「只检查构建」。 */
async function doAction(): Promise<void> {
  if (action.value === 'verify') {
    await doVerify()
    return
  }
  await doPrimary()
}

/** 部署预检（dry-run）：只读检查配置/密钥/Docker，不产生任何变更。 */
async function preflight(): Promise<void> {
  if (!shaValid.value || dryRunBusy.value) return
  dryRunBusy.value = true
  topError.value = ''
  dryRun.value = null
  try {
    const target = action.value === 'verify' ? 'verify' : pipelineId.value
    const report = await dryRunPipeline(target, projectId.value, env.value, sha.value)
    dryRun.value = report
    if (report.ok) {
      ElMessage.success('预检通过')
    } else {
      ElMessage.warning(`预检未通过：${report.blockers[0] ?? '见运行卡'}`)
    }
  } catch (e) {
    topError.value = `预检失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    dryRunBusy.value = false
  }
}

// 代码/环境/流水线/动作变化后，旧报告不再有参考价值。
watch([sha, env, pipelineId, action], () => { dryRun.value = null })

/** 从失败阶段继续：后端复用失败现场，只重跑该阶段及其后续。 */
async function retryFailedStage(): Promise<void> {
  const stage = failedStage.value
  const current = run.value
  if (!stage || !current || retrying.value) return
  retrying.value = true
  topError.value = ''
  try {
    const res = await retryStage(current.runId, stage.id)
    if (!res.retrying) {
      ElMessage.warning(res.message || '该阶段无法重试，请整条重跑')
      return
    }
    ElMessage.success(res.message || '已从失败阶段继续')
    // 后端复用同一个 runId，重新接入轮询即可看到后续阶段
    await openRun(current.runId)
  } catch (e) {
    topError.value = `重试失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    retrying.value = false
  }
}

/** 复制失败信息：阶段、退出码、摘要、端口提示，方便贴到聊天/工单里。 */
function copyFailure(): void {
  const stage = failedStage.value
  const current = run.value
  if (!stage || !current) return
  const lines = [
    `运行 ${current.runId} · 阶段 ${stage.id} 失败`,
    `状态 ${stage.status} · 退出码 ${stage.exitCode}`
      + (stage.errorCode ? ` · ${stage.errorCode}` : ''),
    stage.summary,
    portHint.value,
  ].filter(Boolean)
  void copyText(lines.join('\n'), '失败信息')
}

/** 打开「与上一次运行对比」：拉两次运行的阶段/产物（不带日志）。 */
async function openCompare(): Promise<void> {
  const base = compareBaseline.value
  const current = run.value
  if (!base || !current) {
    ElMessage.info('没有可对比的上一次运行')
    return
  }
  compareOpen.value = true
  compareLoading.value = true
  comparePrev.value = null
  compareCurrent.value = null
  try {
    const [prev, cur] = await Promise.all([
      getRun(base.runId, false),
      getRun(current.runId, false),
    ])
    comparePrev.value = prev
    compareCurrent.value = cur
  } catch (e) {
    compareOpen.value = false
    topError.value = `对比失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    compareLoading.value = false
  }
}

/** 下载文件产物（jar / dist 等）：走带令牌的 apiBlob，再触发浏览器保存。 */
async function downloadArtifact(a: ArtifactView, index: number): Promise<void> {
  const current = run.value
  if (!current || a.kind !== 'file') return
  try {
    const blob = await apiBlob(artifactFilePath(current.runId, index))
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = a.ref.split(/[\\/]/).pop() || `artifact-${index + 1}`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(`下载失败：${e instanceof Error ? e.message : String(e)}`)
  }
}

/** 镜像产物的本机启动命令（优先用本地镜像地址）。 */
function dockerRunCommand(a: ArtifactView): string {
  const image = String(a.meta?.localImage ?? '') || a.ref
  return `docker run --rm ${image}`
}

/** 对比弹窗里的耗时差（正数变慢、负数变快）。 */
function deltaLabel(ms: number): string {
  if (!ms) return '—'
  return `${ms > 0 ? '+' : '-'}${formatDuration(Math.abs(ms))}`
}

/** 停掉平台托管的 dev 前后端，把运行权交还 IDE。 */
async function stopLocalServices(): Promise<void> {
  localStopping.value = true
  try {
    const result = await stopLocalDeploy(projectId.value, 'dev')
    ElMessage[result.stopped ? 'success' : 'info'](result.message || '已处理')
  } catch (e) {
    topError.value = `停止本机服务失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    localStopping.value = false
  }
}

/* ---------------------------------------------------------------- 历史 / 详情 */

function openedRun(item: HistoryItem): void {
  activeMeta.value = { env: item.env, pipelineId: item.pipelineId, sha: item.gitSha }
  void openRun(item.runId, { startedAt: item.createdAt })
  syncUrl(item.runId)
}

async function rerun(item: HistoryItem): Promise<void> {
  triggerBusy.value = true
  topError.value = ''
  try {
    const result = await triggerPipeline(
      item.pipelineId, projectId.value, item.gitSha, deployTarget.value, item.env)
    attachRun(result, { env: item.env, pipelineId: item.pipelineId, sha: item.gitSha })
    ElMessage.success('已用该提交重跑')
  } catch (e) {
    topError.value = `重跑失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    triggerBusy.value = false
  }
}

/** 删除一条构建历史：只删记录（含磁盘上的 run yml），不动仓库与已构建镜像。 */
async function handleDeleteHistory(item: HistoryItem): Promise<void> {
  const label = `#${item.seq || '—'} · ${item.gitSha.slice(0, 8)}`
  try {
    await ElMessageBox.confirm(
      `删除构建历史 ${label}？只删除记录，不影响代码和已构建的镜像。`,
      '删除历史',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await deleteHistory(item.runId)
    if (!res.deleted) {
      ElMessage.warning(res.message || '这条运行不能删除')
      return
    }
    // 正看着被删的那次运行：关掉详情，避免轮询已删除的记录
    if (runId.value === item.runId) closeRun()
    await loadHistory()
    ElMessage.success('已删除该条构建历史')
  } catch (e) {
    topError.value = `删除失败：${e instanceof Error ? e.message : String(e)}`
  }
}

async function doCancel(): Promise<void> {
  if (await cancel()) ElMessage.warning('已请求取消')
}

/** 运行条上的「重跑」：重跑"正在看的这次"，而不是当前表单里选的那次。 */
async function rerunActive(): Promise<void> {
  const meta = activeMeta.value
  if (!meta) {
    await doPrimary()
    return
  }
  triggerBusy.value = true
  topError.value = ''
  try {
    const result = await triggerPipeline(
      meta.pipelineId, projectId.value, meta.sha, deployTarget.value, meta.env)
    attachRun(result, { env: meta.env, pipelineId: meta.pipelineId, sha: meta.sha })
    ElMessage.success('已用相同参数重跑')
  } catch (e) {
    topError.value = `重跑失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    triggerBusy.value = false
  }
}

function closeRun(): void {
  clear()
  activeMeta.value = null
  syncUrl('')
}

/** 运行 id 同步到 URL，刷新/分享可恢复。 */
function syncUrl(id: string): void {
  const query = { ...route.query }
  if (id) query.runId = id
  else delete query.runId
  void router.replace({ query })
}

/* ---------------------------------------------------------------- 生命周期 */

const nowTick = ref(Date.now())
let tickTimer: ReturnType<typeof setInterval> | null = null
/** 轮询构建历史：Agent / 聊天等其它入口触发的运行不会主动推送到本页，靠它自动出现。 */
let historyTimer: ReturnType<typeof setInterval> | null = null

watch(running, (value) => {
  if (value && tickTimer === null) {
    tickTimer = setInterval(() => (nowTick.value = Date.now()), 1000)
  } else if (!value && tickTimer !== null) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}, { immediate: true })

watch(projectId, () => {
  sha.value = ''
  void Promise.all([
    loadPipelines(), loadHistory(), loadEnvs(), loadImages(), loadCommits(),
  ]).then(() => loadPreview())
})

watch(pipelineId, () => void loadPreview())

// 提交变化会改变"仓库定义"（ci/pipeline.yml 随提交），预览必须重取
watch(sha, () => void loadPreview())

watch(env, () => {
  // 环境也可能从侧栏/其他页面切换，不只是点环境胶囊
  deployTarget.value = defaultTarget(env.value)
  choosePipeline()
  void loadEnvs()
  void loadHistory()
  void loadImages()
  void loadPreview()
})

onMounted(async () => {
  const fromQuery = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  if (fromQuery) appStore.projectId = fromQuery
  await loadProjects()
  await Promise.all([
    loadPipelines(), loadHistory(), loadEnvs(), loadImages(), loadCommits(),
  ])
  await loadPreview()
  historyTimer = setInterval(() => {
    if (!document.hidden && !historyHasMore.value) void loadHistory(true, true)
  }, 5000)
  const fromUrl = typeof route.query.runId === 'string' ? route.query.runId : ''
  if (fromUrl) {
    const item = history.value.find((h) => h.runId === fromUrl)
    if (item) activeMeta.value = { env: item.env, pipelineId: item.pipelineId, sha: item.gitSha }
    void openRun(fromUrl)
  } else if (history.value.length) {
    // 打开页面就展示最近一次"有内容"的运行：空白详情页对"看看刚才跑成什么样"毫无帮助
    const latest = history.value.find((h) => h.stageCount > 0) ?? history.value[0]
    openedRun(latest)
  }
})

onBeforeUnmount(() => {
  if (tickTimer !== null) clearInterval(tickTimer)
  if (historyTimer !== null) clearInterval(historyTimer)
  agentController?.abort()
})

/* ---------------------------------------------------------------- 小工具 */

/** 镜像产物附带的可读版本与本地镜像地址；非镜像产物返回 null。 */
function imageMeta(a: ArtifactView): { version: string, local: string } | null {
  if (a.kind !== 'image') return null
  const version = String(a.meta?.version ?? '')
  const local = String(a.meta?.localImage ?? '')
  return version || local ? { version, local } : null
}

function shortDigest(digest: string): string {
  if (!digest) return '—'
  return digest.length > 16 ? `${digest.slice(0, 16)}…` : digest
}

async function copyText(text: string, label: string): Promise<void> {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板）')
  }
}

async function copyDigest(digest: string): Promise<void> {
  if (!digest) return
  try {
    await navigator.clipboard.writeText(digest)
    ElMessage.success('digest 已复制')
  } catch {
    ElMessage.error('复制失败（浏览器未授权剪贴板）')
  }
}
</script>

<template>
  <div class="pipeline-page">
    <PageHeader title="流水线工作台" :subtitle="headerSubtitle">
      <template #actions>
        <span v-if="running" class="run-chip busy">
          <span class="dot" /> 运行中 · {{ runDuration }}
        </span>
        <span v-else-if="run" class="run-chip" :class="statusTone(run.status)">
          <span class="dot" /> {{ statusLabel(run.status) }}
        </span>
      </template>
    </PageHeader>

    <!-- 手机端：配置区与运行详情分屏切换，各占满整屏高度 -->
    <div class="mobile-tabs" role="tablist" aria-label="流水线视图">
      <button
        type="button" role="tab" class="mt-btn"
        :class="{ active: mobilePane === 'config' }"
        :aria-selected="mobilePane === 'config'"
        @click="mobilePane = 'config'"
      >
        配置
      </button>
      <button
        type="button" role="tab" class="mt-btn"
        :class="{ active: mobilePane === 'run' }"
        :aria-selected="mobilePane === 'run'"
        @click="mobilePane = 'run'"
      >
        运行详情
        <span v-if="run" class="mt-dot" :class="statusTone(run.status)" />
      </button>
    </div>

    <div class="wp-body" :class="`mpane-${mobilePane}`">
      <!-- 左：上下文 + 运行 + 历史 -->
      <div class="wp-left" :style="{ width: `${leftPct}%` }">
        <div class="wp-left-inner">
          <div class="ctx-bar">
            <label class="ctx-project">
              <span class="ctx-label">项目</span>
              <el-select
                :model-value="projectId" size="default" class="ctx-select"
                placeholder="选择项目" filterable
                @update:model-value="applyProject"
              >
                <el-option
                  v-for="p in projectChoices" :key="p.id"
                  :label="`${p.name}（${p.id}）`" :value="p.id"
                />
              </el-select>
            </label>
            <div class="ctx-env">
              <span class="ctx-label">
                环境
                <span v-if="envsLoading" class="ctx-loading">读取中…</span>
              </span>
              <EnvStateBand v-model="envModel" :rows="envRows" :loading="envsLoading" />
            </div>
          </div>

          <TriggerBar
            v-model:sha="sha"
            v-model:deploy-target="deployTarget"
            v-model:action="action"
            :env-label="envLabel(env)"
            :primary-label="primaryLabel"
            :commits="commits"
            :commits-loading="commitsLoading"
            :show-mode-switch="showModeSwitch"
            :docker-available="dockerAvailable"
            :mode-note="modeNote"
            :local-hint="localHint"
            :can-stop-local="canStopLocal"
            :preview="preview"
            :verify-preview="verifyPreview"
            :preview-loading="previewLoading"
            :busy="triggerBusy || pipelinesLoading || localStopping"
            :last-good-sha="lastGoodSha"
            :hint="runHint"
            :can-run="canRun"
            :dry-run="dryRun"
            :dry-run-busy="dryRunBusy"
            :agent-busy="agentRunning"
            :agent-disabled="agentDisabled"
            :agent-hint="agentHint"
            @run="doAction"
            @dryrun="preflight"
            @agent="runDeployAgent"
            @refresh-commits="loadCommits"
            @stop-local="stopLocalServices"
          />

          <el-dialog
            v-model="agentOpen" title="发布 Agent" width="760px" append-to-body
            :close-on-click-modal="false" :close-on-press-escape="!agentRunning"
            :show-close="!agentRunning"
          >
            <div class="agent-meta">
              <span class="mono">{{ envShort(env) }} · {{ sha.slice(0, 8) }}</span>
              <span v-if="agentRunning" class="agent-live">执行中…</span>
              <span v-else-if="agentError" class="agent-bad">{{ agentError }}</span>
              <span v-else class="agent-done">已结束</span>
            </div>

            <ol v-if="agentTools.length" class="agent-tools">
              <li
                v-for="t in agentTools" :key="t.toolCallId"
                class="agent-tool" :class="t.status.toLowerCase()"
              >
                <span class="at-name mono">{{ t.toolName }}</span>
                <span class="at-status">{{ t.status }}</span>
                <p v-if="t.resultText" class="at-result mono">{{ t.resultText }}</p>
              </li>
            </ol>
            <p v-else class="agent-empty">
              {{ agentRunning ? '正在启动…' : '没有工具调用记录' }}
            </p>

            <div v-if="agentMessages.length" class="agent-msgs">
              <p v-for="m in agentMessages" :key="m.id" class="agent-msg" :class="m.role">
                {{ m.text }}
              </p>
            </div>

            <template #footer>
              <el-button v-if="agentRunning" @click="stopAgent">停止</el-button>
              <el-button :disabled="agentRunning" @click="agentOpen = false">关闭</el-button>
            </template>
          </el-dialog>

          <HistoryList
            class="fill"
            :items="history"
            :active-run-id="runId"
            :env="env"
            :loading="historyLoading"
            :has-more="historyHasMore"
            @open="openedRun"
            @rerun="rerun"
            @delete="handleDeleteHistory"
            @refresh="loadHistory"
            @more="loadMoreHistory"
          />

          <EnvSummary :project-id="projectId" :env="env" />
        </div>
      </div>

      <ResizableSplitter v-model="leftPct" :min="24" :max="55" />

      <!-- 右：运行详情 -->
      <div class="wp-right">
        <el-alert
          v-if="topError" class="top-alert" type="error" :closable="true" show-icon
          :title="topError" @close="topError = ''"
        />

        <div v-if="!run && !runLoading" class="run-empty">
          <p class="re-title">选择提交后点「{{ primaryLabel }}」</p>
          <p class="re-sub">
            运行详情会实时显示阶段、产物与日志；也可以从左侧历史打开一次旧运行。
          </p>
          <button
            type="button" class="re-btn"
            :disabled="!canRun"
            @click="doPrimary"
          >
            <el-icon><VideoPlay /></el-icon> {{ primaryLabel }}
          </button>
        </div>

        <template v-else>
          <div class="run-bar" :class="statusTone(run?.status ?? '')">
            <span class="rb-status">
              <span class="rb-dot" /> {{ run ? statusLabel(run.status) : '加载中' }}
            </span>
            <span v-if="activeMeta" class="rb-tag">{{ envShort(activeMeta.env) }}</span>
            <span v-if="activeMeta" class="rb-tag plain">{{ pipelineLabel(activeMeta.pipelineId) }}</span>
            <span v-if="activeMeta" class="rb-sha mono">{{ activeMeta.sha.slice(0, 8) }}</span>
            <span
              v-if="definitionSourceLabel" class="rb-tag plain"
              :title="run?.meta?.definitionNote"
            >{{ definitionSourceLabel }}</span>
            <span
              v-if="activeRecipeVersion" class="rb-tag plain"
              :title="`证据运行 ${activeEnvRow?.evidenceRunId || '—'}`"
            >v{{ activeRecipeVersion }}</span>
            <span
              v-if="run?.meta?.bindingId" class="rb-tag plain mono"
              :title="`环境绑定 ${run.meta.bindingId}（确认人 ${run.meta.confirmedBy || '—'}）`"
            >binding</span>
            <span v-if="run?.meta?.actor" class="rb-tag plain">{{ run.meta.actor }}</span>
            <span v-if="run" class="rb-meta">{{ run.stages.length }} 阶段 · {{ run.artifacts.length }} 产物 · {{ runDuration }}</span>
            <span v-if="errorText" class="rb-err">{{ errorText }}</span>
            <div class="rb-actions">
              <button
                v-if="compareBaseline" type="button" class="rb-btn"
                :disabled="running" title="与上一次同环境运行对比阶段耗时和产物"
                @click="openCompare"
              >
                对比上次
              </button>
              <button v-if="running" type="button" class="rb-btn warn" @click="doCancel">取消运行</button>
              <button type="button" class="rb-btn" :disabled="running" @click="rerunActive">
                <el-icon><RefreshRight /></el-icon> 重跑
              </button>
              <button type="button" class="rb-btn icon" title="关闭详情" @click="closeRun">
                <el-icon><Close /></el-icon>
              </button>
            </div>
          </div>

          <!-- 失败卡片：失败阶段 / 退出码 / 摘要 / 端口提示 + 三个快捷动作 -->
          <section v-if="failedStage" class="fail-card" aria-label="运行失败">
            <div class="fc-head">
              <span class="fc-title">阶段 {{ failedStage.id }} 失败</span>
              <span class="fc-meta">
                退出码 {{ failedStage.exitCode }}
                <template v-if="failedStage.errorCode"> · {{ failedStage.errorCode }}</template>
              </span>
            </div>
            <p class="fc-summary">
              {{ failedStage.summary || '没有错误摘要，打开下方日志查看详情' }}
            </p>
            <p v-if="portHint" class="fc-hint">{{ portHint }}</p>
            <div class="fc-actions">
              <button type="button" class="fc-btn" @click="copyFailure">
                <el-icon><CopyDocument /></el-icon> 复制错误
              </button>
              <button
                type="button" class="fc-btn primary"
                :disabled="retrying" @click="retryFailedStage"
              >
                <el-icon><RefreshRight /></el-icon> {{ retrying ? '继续中…' : '重试该阶段' }}
              </button>
              <button type="button" class="fc-btn" @click="rerunActive">
                <el-icon><VideoPlay /></el-icon> 整条重跑
              </button>
            </div>
          </section>

          <div class="run-detail">
            <!-- dev 一键体验：本机服务就绪后直接给可点开的地址 -->
            <div v-if="localLinks.length" class="local-links">
              <span class="ll-label">本机服务已就绪</span>
              <a
                v-for="l in localLinks" :key="l.url"
                class="ll-link" :href="l.url" target="_blank" rel="noreferrer"
              >
                <el-icon><Link /></el-icon> {{ l.label }}
              </a>
            </div>

            <StageTimeline
              v-if="run"
              :stages="run.stages"
              :running="running"
              :started-at="startedAt"
            />

            <section v-if="run?.artifacts.length" class="artifact-panel">
              <h3 class="ap-title">产物 <span class="ap-count">{{ run.artifacts.length }}</span></h3>
              <div v-for="g in artifactGroups" :key="g.dir" class="artifact-group">
                <div v-if="g.dir" class="artifact-dir" :title="g.dir">
                  <span class="mono">{{ shortDir(g.dir) }}</span>
                  <span class="ad-count">
                    {{ g.items.length }} 个
                    <button
                      v-if="g.collapsed && isDirExpanded(g.dir)" type="button" class="ad-toggle"
                      @click="toggleDir(g.dir)"
                    >收起</button>
                  </span>
                </div>
                <div v-if="g.collapsed && !isDirExpanded(g.dir)" class="artifact-row artifact-collapsed">
                  <span class="art-kind">目录</span>
                  <code class="art-ref mono" :title="g.dir" @click="copyText(g.dir, '产物目录')">
                    {{ shortDir(g.dir) || '根目录' }} / {{ g.items.length }} 个文件
                  </code>
                  <span class="art-size">{{ formatBytes(totalSize(g.items)) }}</span>
                  <span class="art-digest mono">已折叠</span>
                  <span class="art-actions">
                    <button type="button" class="art-act" @click="toggleDir(g.dir)">展开</button>
                  </span>
                </div>
                <template v-if="!g.collapsed || isDirExpanded(g.dir)">
                  <template
                    v-for="item in g.items" :key="`${item.a.kind}-${item.a.ref}-${item.index}`"
                  >
                  <div class="artifact-row">
                    <span class="art-kind">{{ item.a.kind }}</span>
                    <code class="art-ref mono" :title="item.a.ref" @click="copyText(item.a.ref, '产物地址')">{{ fileName(item.a.ref) }}</code>
                    <span class="art-size">{{ formatBytes(item.a.sizeBytes) }}</span>
                    <button
                      type="button" class="art-digest mono"
                      :title="item.a.digest ? `点击复制 ${item.a.digest}` : '无摘要'"
                      @click="copyDigest(item.a.digest)"
                    >
                      {{ shortDigest(item.a.digest) }}
                    </button>
                    <span class="art-actions">
                      <span
                        v-if="!isArchived(item.a)" class="art-pending mono"
                        title="检查构建只记录路径与摘要，不复制文件"
                      >未归档</span>
                      <button
                        v-else-if="item.a.kind === 'file'" type="button" class="art-act"
                        title="下载到本机" @click="downloadArtifact(item.a, item.index)"
                      >
                        <el-icon><Download /></el-icon> 下载
                      </button>
                      <button
                        v-else-if="item.a.kind === 'image'" type="button" class="art-act"
                        title="复制本机启动命令" @click="copyText(dockerRunCommand(item.a), 'docker run 命令')"
                      >
                        <el-icon><CopyDocument /></el-icon> docker run
                      </button>
                    </span>
                  </div>
                  <div v-if="imageMeta(item.a)" class="artifact-image">
                    <span class="ai-label">镜像版本</span>
                    <span class="ai-version mono">{{ imageMeta(item.a)?.version || '—' }}</span>
                    <span class="ai-label">本地镜像</span>
                    <button
                      type="button" class="ai-local mono"
                      :title="`点击复制本地镜像地址 ${imageMeta(item.a)?.local}`"
                      @click="copyText(imageMeta(item.a)?.local ?? '', '本地镜像地址')"
                    >{{ imageMeta(item.a)?.local }}</button>
                  </div>
                  </template>
                </template>
              </div>
            </section>

            <section v-if="imageVersions.length" class="artifact-panel">
              <h3 class="ap-title">
                镜像版本 <span class="ap-count">{{ imageVersions.length }}</span>
                <span class="ap-hint">独立版本账本：按 (项目, 环境) 记账，删构建历史不影响版本与回滚锚点</span>
              </h3>
              <div v-for="img in imageVersions.slice(0, 6)" :key="img.tag" class="image-row">
                <span class="iv-badge" :class="imageBadgeClass(img)">{{ imageBadge(img) }}</span>
                <code class="art-ref mono" :title="img.tag" @click="copyText(img.tag, '镜像 tag')">{{ img.tag }}</code>
                <span class="iv-version mono" :title="`构建版本 ${img.version || '—'}`">{{ img.version || '—' }}</span>
                <button
                  type="button" class="iv-digest mono"
                  :title="img.digest ? `点击复制镜像摘要 ${img.digest}` : '未记录摘要（Docker 不可用时无法读取）'"
                  @click="copyDigest(img.digest)"
                >{{ shortDigest(img.digest) }}</button>
                <span class="iv-size mono">{{ formatBytes(img.sizeBytes) }}</span>
                <button
                  type="button" class="iv-act"
                  :disabled="!dockerAvailable"
                  :title="dockerAvailable
                    ? '复用该镜像重新部署：跳过构建与打包，只跑部署 + 健康检查'
                    : '本机 Docker 不可用，无法按版本部署'"
                  @click="deployVersion(img)"
                >部署</button>
                <div class="iv-audit mono">
                  <span>提交 {{ (img.gitSha || '').slice(0, 7) || '—' }}</span>
                  <span>部署人 {{ img.deployedBy || '—' }}</span>
                  <span>部署于 {{ formatClock(img.deployedAt || img.createdAt) }}</span>
                </div>
              </div>
            </section>

            <section v-else-if="env !== 'dev'" class="artifact-panel">
              <h3 class="ap-title">镜像版本 <span class="ap-count">0</span></h3>
              <p class="ap-empty">
                这个环境还没有镜像版本。发布（或显式打包镜像）后按环境记录当前 / 上一版，
                回滚以「上一版」为目标；旧版本按保留策略自动清理。
              </p>
              <p v-if="!dockerAvailable" class="ap-empty ap-warn">
                本机 Docker 不可用，无法按版本部署；安装并启动 Docker 后刷新即可。
              </p>
            </section>

            <RunLog
              v-if="run"
              :run-id="run.runId"
              :running="running"
              class="grow"
            />
          </div>
        </template>
      </div>
    </div>

    <!-- 对比上次运行：阶段状态/耗时 + 产物增删 -->
    <el-dialog v-model="compareOpen" title="与上一次运行对比" width="720px" append-to-body>
      <p v-if="compareLoading" class="cmp-none">加载中…</p>
      <template v-else-if="comparePrev && compareCurrent">
        <div class="cmp-head">
          <span class="cmp-side">
            上次 <em class="mono">{{ comparePrev.runId.slice(0, 8) }}</em>
          </span>
          <span class="cmp-side">
            本次 <em class="mono">{{ compareCurrent.runId.slice(0, 8) }}</em>
          </span>
        </div>

        <el-table v-if="stageDiffs.length" :data="stageDiffs" size="small" class="cmp-table">
          <el-table-column prop="id" label="阶段" min-width="130" />
          <el-table-column label="上次" width="120">
            <template #default="{ row }">
              <span class="cmp-st" :class="statusTone(row.prevStatus)">{{ row.prevStatus }}</span>
              <span class="cmp-ms">{{ row.prevMs ? formatDuration(row.prevMs) : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="本次" width="120">
            <template #default="{ row }">
              <span class="cmp-st" :class="statusTone(row.curStatus)">{{ row.curStatus }}</span>
              <span class="cmp-ms">{{ row.curMs ? formatDuration(row.curMs) : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时变化" width="100">
            <template #default="{ row }">
              <span
                class="cmp-delta"
                :class="{ slow: row.deltaMs > 0, fast: row.deltaMs < 0 }"
              >{{ deltaLabel(row.deltaMs) }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="cmp-artifacts">
          <p class="cmp-sub">产物变化</p>
          <p
            v-if="!artifactDiff.added.length && !artifactDiff.removed.length"
            class="cmp-none"
          >产物一致</p>
          <p
            v-for="a in artifactDiff.added" :key="`add-${a.kind}-${a.ref}`"
            class="cmp-art add"
          >+ {{ a.kind }} · {{ a.ref }}</p>
          <p
            v-for="a in artifactDiff.removed" :key="`del-${a.kind}-${a.ref}`"
            class="cmp-art del"
          >- {{ a.kind }} · {{ a.ref }}</p>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pipeline-page {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}
.wp-body {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  overflow: hidden;
}
/* 手机端分屏切换条：桌面端不显示 */
.mobile-tabs {
  display: none;
}
.wp-left {
  flex: none;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  border-right: 1px solid var(--line-soft);
}
.wp-left-inner {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  padding: 12px 12px 12px 14px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
.wp-left-inner .fill {
  flex: 1 1 420px;
  min-height: 320px;
}
.wp-right {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg);
}
.top-alert {
  margin: 12px 14px 0;
}
.grow {
  flex: 1 1 240px;
  min-height: 200px;
}

/* 上下文：项目 + 环境 */
.ctx-bar {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.ctx-project {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ctx-label {
  flex: none;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ctx-select {
  flex: 1;
  min-width: 0;
}
.ctx-env {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.ctx-loading {
  margin-left: 4px;
  color: var(--ink-faint);
}
.ctx-env :deep(.env-switch) {
  width: 100%;
}

/* 运行条 */
.run-bar {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: var(--panel);
  border-bottom: 1px solid var(--line-soft);
}
.rb-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.rb-dot {
  width: 7px;
  height: 7px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.run-bar.ok .rb-dot { background: var(--ok); }
.run-bar.bad .rb-dot { background: var(--danger); }
.run-bar.busy .rb-dot {
  background: var(--accent);
  animation: dot-pulse 1.4s ease-in-out infinite;
}
@keyframes dot-pulse { 50% { opacity: 0.3; } }
.rb-tag {
  padding: 1px 7px;
  font-size: 10.5px;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-pill);
}
.rb-tag.plain {
  color: var(--ink-muted);
  background: var(--surface-sunken);
}
.rb-sha {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.rb-meta {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.rb-err {
  font-size: 11.5px;
  color: var(--danger);
}
.rb-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}
.rb-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink-soft);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.rb-btn:hover:not(:disabled) {
  color: var(--ink);
  background: var(--surface-hover);
}
.rb-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.rb-btn.warn {
  color: var(--warn);
  background: var(--warn-soft);
  border-color: color-mix(in srgb, var(--warn) 30%, transparent);
}
.rb-btn.icon {
  width: 26px;
  padding: 0;
  justify-content: center;
}

.run-detail {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  padding: 12px 14px 14px;
  overflow: hidden;
}

/* 产物 */
.artifact-panel {
  flex: none;
  padding: 10px 12px;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-md);
}
.ap-title {
  margin: 0 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}
.ap-count {
  margin-left: 4px;
  font-weight: 400;
  color: var(--ink-faint);
}
.ap-hint {
  margin-left: 8px;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--ink-faint);
}
.ap-empty {
  margin: 6px 0 2px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-faint);
}
.artifact-group + .artifact-group {
  margin-top: 2px;
}
.artifact-dir {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-faint);
  border-top: 1px solid var(--line-soft);
}
.artifact-dir .mono {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ad-count {
  flex: none;
}
.art-pending {
  font-size: 10.5px;
  color: var(--ink-faint);
  cursor: help;
}
/* 镜像版本谱系：当前 / 上一版 / 历史 */
.image-row {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr) 64px 88px 64px 48px;
  gap: 10px;
  align-items: center;
  padding: 6px 0;
  font-size: 11.5px;
  border-top: 1px solid var(--line-soft);
}
.iv-audit {
  display: flex;
  grid-column: 1 / -1;
  gap: 14px;
  font-size: 10px;
  color: var(--ink-faint);
}
.iv-version,
.iv-size {
  overflow: hidden;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.iv-size {
  text-align: right;
}
.iv-digest {
  padding: 0;
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: 0;
}
.iv-digest:hover {
  color: var(--accent-ink);
}
.iv-act:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.iv-act {
  height: 22px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-xs);
}
.iv-act:hover {
  color: var(--accent-ink);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
.iv-badge {
  padding: 1px 6px;
  font-size: 10px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: var(--radius-xs);
  text-align: center;
}
.iv-badge.is-current {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.iv-sha,
.iv-time {
  color: var(--ink-faint);
}
.iv-time {
  text-align: right;
}
.artifact-collapsed .art-ref {
  color: var(--ink-muted);
  cursor: pointer;
}
.ap-warn {
  color: var(--warn-ink);
}
.ad-toggle {
  margin-left: 6px;
  padding: 0;
  font-size: 10px;
  color: var(--accent-ink);
  cursor: pointer;
  background: none;
  border: 0;
}
.artifact-row {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr) 76px 170px 96px;
  gap: 10px;
  align-items: center;
  padding: 5px 0;
  font-size: 11.5px;
  border-top: 1px solid var(--line-soft);
}
.art-kind {
  color: var(--accent-ink);
}
.art-ref {
  overflow: hidden;
  color: var(--ink-soft);
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: copy;
}
.art-size {
  color: var(--ink-faint);
  text-align: right;
}
.art-digest {
  padding: 0;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-muted);
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: 0;
}
.art-digest:hover {
  color: var(--accent-ink);
}
.art-actions {
  display: flex;
  justify-content: flex-end;
}
.art-act {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 8px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-xs);
}
.art-act:hover {
  color: var(--accent-ink);
  border-color: color-mix(in srgb, var(--accent) 45%, transparent);
}
/* 镜像产物的附带信息：可读版本（env-vN）+ 本地镜像地址 */
.artifact-image {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  padding: 0 0 6px 80px;
  font-size: 10.5px;
  color: var(--ink-faint);
}
.ai-version {
  padding: 0 5px;
  font-weight: 600;
  color: var(--accent-ink);
  background: var(--accent-soft);
  border-radius: var(--radius-xs);
}
.ai-local {
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  font-size: 10.5px;
  color: var(--ink-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  background: none;
  border: 0;
}
.ai-local:hover {
  color: var(--accent-ink);
  text-decoration: underline;
}

/* 失败卡片 */
.fail-card {
  flex: none;
  padding: 10px 14px;
  background: color-mix(in srgb, var(--danger) 7%, var(--panel));
  border-bottom: 1px solid color-mix(in srgb, var(--danger) 25%, transparent);
}
.fc-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.fc-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--danger);
}
.fc-meta {
  font-size: 10.5px;
  color: var(--ink-faint);
}
.fc-summary {
  margin: 5px 0 0;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--ink-soft);
  overflow-wrap: anywhere;
}
.fc-hint {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--warn);
}
.fc-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.fc-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 26px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 11.5px;
  color: var(--ink-soft);
  cursor: pointer;
  background: var(--panel);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-sm);
}
.fc-btn:hover:not(:disabled) {
  color: var(--ink);
  border-color: color-mix(in srgb, var(--ink) 25%, transparent);
}
.fc-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
.fc-btn.primary {
  color: #fff;
  background: var(--accent);
  border-color: transparent;
}
.fc-btn.primary:hover:not(:disabled) {
  background: color-mix(in srgb, var(--accent) 86%, #000);
}

/* dev 本机服务入口 */
.local-links {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  background: color-mix(in srgb, var(--ok) 8%, var(--panel));
  border: 1px solid color-mix(in srgb, var(--ok) 28%, transparent);
  border-radius: var(--radius-md);
}
.ll-label {
  font-size: 11.5px;
  color: var(--ink-muted);
}
.ll-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 10px;
  font-size: 11.5px;
  color: var(--accent-ink);
  text-decoration: none;
  background: var(--panel);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
  border-radius: var(--radius-pill);
}
.ll-link:hover {
  background: var(--accent-soft);
}

/* 对比弹窗 */
.cmp-head {
  display: flex;
  gap: 24px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--ink-muted);
}
.cmp-side em {
  font-style: normal;
  color: var(--accent-ink);
}
.cmp-table {
  width: 100%;
}
.cmp-st {
  display: inline-block;
  min-width: 64px;
  font-size: 11px;
  color: var(--ink-faint);
}
.cmp-st.ok { color: var(--ok); }
.cmp-st.bad { color: var(--danger); }
.cmp-st.busy { color: var(--accent-ink); }
.cmp-ms {
  font-size: 11px;
  color: var(--ink-muted);
}
.cmp-delta {
  font-size: 11.5px;
  color: var(--ink-faint);
}
.cmp-delta.slow { color: var(--danger); }
.cmp-delta.fast { color: var(--ok); }
.cmp-artifacts {
  margin-top: 12px;
}
.cmp-sub {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink);
}
.cmp-art {
  margin: 0 0 3px;
  font-size: 11.5px;
  color: var(--ink-muted);
  overflow-wrap: anywhere;
}
.cmp-art.add { color: var(--ok); }
.cmp-art.del { color: var(--danger); }
.cmp-none {
  margin: 0;
  font-size: 12px;
  color: var(--ink-faint);
}

/* 空状态 */
.run-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 40px;
  text-align: center;
}
.re-title {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: 600;
  color: var(--ink-soft);
}
.re-sub {
  margin: 0;
  max-width: 420px;
  font-size: var(--text-xs);
  line-height: var(--leading-relaxed);
  color: var(--ink-muted);
}
.re-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 14px;
  margin-top: 10px;
  font-family: inherit;
  font-size: 12.5px;
  color: #fff;
  cursor: pointer;
  background: var(--accent);
  border: 0;
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow-1);
  transition: background-color var(--dur-fast) ease, box-shadow var(--dur-fast) ease;
}
.re-btn:hover:not(:disabled) {
  background: var(--accent-hover);
  box-shadow: 0 2px 10px rgb(77 130 247 / 28%);
}
.re-btn:disabled {
  color: var(--ink-faint);
  cursor: not-allowed;
  background: var(--surface-sunken);
  box-shadow: none;
}

/* Header 状态 chip */
.run-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 9px;
  font-size: 11.5px;
  color: var(--ink-muted);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.run-chip .dot {
  width: 5px;
  height: 5px;
  background: var(--ink-faint);
  border-radius: 50%;
}
.run-chip.ok .dot { background: var(--ok); }
.run-chip.bad .dot { background: var(--danger); }
.run-chip.busy .dot {
  background: var(--accent);
  animation: dot-pulse 1.4s ease-in-out infinite;
}

@media (max-width: 900px) {
  /* 主从切换：配置 / 运行详情一次只显示一个，避免上下各占一半 */
  .wp-body.mpane-config .wp-right {
    display: none;
  }
  .wp-body.mpane-run .wp-left {
    display: none;
  }
  .wp-left {
    width: 100% !important;
    height: auto;
    flex: 1 1 0;
    border-right: 0;
  }
  .wp-body :deep(.resizer) {
    display: none;
  }
  .wp-right {
    flex: 1 1 0;
    min-height: 0;
  }
  /* 运行详情在手机上整体可滚，日志区不再被挤成一条缝 */
  .run-detail {
    overflow-y: auto;
    overscroll-behavior: contain;
    -webkit-overflow-scrolling: touch;
  }
  .run-detail :deep(.run-log) {
    min-height: 320px;
  }
  .run-detail :deep(.log-scroll) {
    min-height: 150px;
  }
  .mobile-tabs {
    display: flex;
    flex: none;
    gap: 6px;
    padding: 6px 12px;
    background: var(--surface-raised);
    border-bottom: 1px solid var(--line-soft);
  }
  .mt-btn {
    display: inline-flex;
    flex: 1;
    align-items: center;
    justify-content: center;
    gap: 6px;
    height: 32px;
    font-family: inherit;
    font-size: 13px;
    color: var(--ink-muted);
    cursor: pointer;
    background: var(--surface-sunken);
    border: 1px solid transparent;
    border-radius: var(--radius-md);
  }
  .mt-btn.active {
    font-weight: 600;
    color: var(--accent-ink);
    background: var(--panel);
    border-color: var(--line-soft);
  }
  .mt-dot {
    width: 6px;
    height: 6px;
    background: var(--ink-faint);
    border-radius: 50%;
  }
  .mt-dot.busy { background: var(--accent); }
  .mt-dot.ok { background: var(--ok); }
  .mt-dot.bad { background: var(--danger); }
}

/* 手机：左侧上下文区压缩，产物行去掉摘要列，避免横向溢出 */
@media (max-width: 720px) {
  .wp-left-inner {
    padding: 10px;
  }
  .run-bar {
    padding: 8px 10px;
  }
  .artifact-row {
    grid-template-columns: 52px minmax(0, 1fr) 60px 64px;
    gap: 8px;
  }
  .art-digest {
    display: none;
  }
  .image-row {
    grid-template-columns: 46px minmax(0, 1fr) 54px 46px;
    gap: 8px;
  }
  .iv-version,
  .iv-size {
    display: none;
  }
}

/* 发布 Agent：实时工具轨迹与正文 */
.agent-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 11.5px;
  color: var(--ink-muted);
}
.agent-live {
  color: var(--accent-ink);
}
.agent-bad {
  color: var(--danger);
}
.agent-done {
  color: var(--ink-faint);
}
.agent-tools {
  padding: 0;
  margin: 0 0 10px;
  list-style: none;
}
.agent-tool {
  padding: 6px 9px;
  margin-bottom: 6px;
  background: var(--surface-sunken);
  border-left: 2px solid var(--line-soft);
  border-radius: var(--radius-xs);
}
.agent-tool.running {
  border-left-color: var(--accent);
}
.agent-tool.completed {
  border-left-color: var(--ok);
}
.agent-tool.failed {
  border-left-color: var(--danger);
}
.at-name {
  font-size: 11.5px;
  color: var(--ink);
}
.at-status {
  margin-left: 8px;
  font-size: 10px;
  color: var(--ink-faint);
}
.at-result {
  max-height: 120px;
  padding: 0;
  margin: 4px 0 0;
  overflow: auto;
  font-size: 10.5px;
  line-height: 1.5;
  color: var(--ink-soft);
  white-space: pre-wrap;
  word-break: break-word;
}
.agent-empty {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-faint);
}
.agent-msgs {
  max-height: 240px;
  overflow: auto;
}
.agent-msg {
  margin: 0 0 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-soft);
  white-space: pre-wrap;
}
.agent-msg.assistant {
  color: var(--ink);
}
</style>
