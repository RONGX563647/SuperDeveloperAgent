<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
  artifactFilePath, deleteHistory, dryRunPipeline, getHistory, getRun, isTerminalStatus,
  listPipelines, previewPipeline, retryStage, stopLocalDeploy, triggerPipeline,
  type ArtifactView, type DryRunReportView, type HistoryItem, type PreviewStage,
  type RunView, type StageView,
} from '../api/pipeline'
import { apiBlob } from '../api/http'
import { gitCommits, listProjects, type GitCommit, type ProjectRow } from '../api/project'
import { deployConfirm, deployEnvs, deployReplay, type EnvStatus } from '../api/deploy'
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

/** 当前右侧查看的运行来自哪次触发/哪条历史（运行详情本身不带 env/pipeline）。 */
const activeMeta = ref<{ env: string, pipelineId: string, sha: string } | null>(null)

const {
  run, runId, loading: runLoading, errorText, startedAt, running,
  open: openRun, attach, cancel, clear,
} = useRunTracker({ onFinished: () => { void loadHistory(); void loadEnvs() } })

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
  return '构建镜像并在本机 Docker 运行'
})

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
    loadPipelines(), loadHistory(), loadEnvs(), loadCommits(),
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
  void loadPreview()
})

onMounted(async () => {
  const fromQuery = typeof route.query.projectId === 'string' ? route.query.projectId : ''
  if (fromQuery) appStore.projectId = fromQuery
  await loadProjects()
  await Promise.all([
    loadPipelines(), loadHistory(), loadEnvs(), loadCommits(),
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

    <div class="wp-body">
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
            @run="doAction"
            @dryrun="preflight"
            @refresh-commits="loadCommits"
            @stop-local="stopLocalServices"
          />

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
              <template v-for="(a, i) in run.artifacts" :key="`${a.kind}-${a.ref}-${i}`">
                <div class="artifact-row">
                  <span class="art-kind">{{ a.kind }}</span>
                  <code class="art-ref mono" :title="a.ref" @click="copyText(a.ref, '产物地址')">{{ a.ref }}</code>
                  <span class="art-size">{{ formatBytes(a.sizeBytes) }}</span>
                  <button
                    type="button" class="art-digest mono"
                    :title="a.digest ? `点击复制 ${a.digest}` : '无摘要'"
                    @click="copyDigest(a.digest)"
                  >
                    {{ shortDigest(a.digest) }}
                  </button>
                  <span class="art-actions">
                    <button
                      v-if="a.kind === 'file'" type="button" class="art-act"
                      title="下载到本机" @click="downloadArtifact(a, i)"
                    >
                      <el-icon><Download /></el-icon> 下载
                    </button>
                    <button
                      v-else-if="a.kind === 'image'" type="button" class="art-act"
                      title="复制本机启动命令" @click="copyText(dockerRunCommand(a), 'docker run 命令')"
                    >
                      <el-icon><CopyDocument /></el-icon> docker run
                    </button>
                  </span>
                </div>
                <div v-if="imageMeta(a)" class="artifact-image">
                  <span class="ai-label">镜像版本</span>
                  <span class="ai-version mono">{{ imageMeta(a)?.version || '—' }}</span>
                  <span class="ai-label">本地镜像</span>
                  <button
                    type="button" class="ai-local mono"
                    :title="`点击复制本地镜像地址 ${imageMeta(a)?.local}`"
                    @click="copyText(imageMeta(a)?.local ?? '', '本地镜像地址')"
                  >{{ imageMeta(a)?.local }}</button>
                </div>
              </template>
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
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-muted);
}
.re-sub {
  margin: 0;
  max-width: 380px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-faint);
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
}
.re-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
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
  .wp-body {
    flex-direction: column;
  }
  .wp-left {
    width: 100% !important;
    height: auto;
    max-height: 50%;
    border-right: 0;
    border-bottom: 1px solid var(--line-soft);
  }
}
</style>
