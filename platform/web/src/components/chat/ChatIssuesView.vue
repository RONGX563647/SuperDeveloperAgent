<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, Close, Delete, Edit, Link, Picture, Plus, Refresh, RefreshRight, Search,
  Setting, Tickets,
} from '@element-plus/icons-vue'
import {
  addGitIssueComment, closeGitIssue, createGitIssue, deleteGitIssue, deleteGitIssueComment,
  listGitIssueComments, listGitIssues, reopenGitIssue, syncGitIssue, updateGitIssue,
  uploadIssueImage,
  type GitIssueCommentRow, type GitIssuePriority, type GitIssueRow,
} from '../../api/issues'
import {
  cancelBugFix, getBugFixPolicy, getBugFixRun, listBugFixRuns, retryBugFix,
  saveBugFixPolicy, startBugFix,
  type BugFixEventRow, type BugFixPolicyRow, type BugFixRunRow, type BugFixStage,
  type BugFixStageRunRow,
} from '../../api/bugfix'
import MarkdownView from './MarkdownView.vue'
import { appStore } from '../../stores/app'
import { listProjects, type ProjectRow } from '../../api/project'

/**
 * 对话页「任务」Tab：项目托管的 Git Issue 双向同步任务。
 *
 * 采用单列列表：一行一个任务，点击行展开描述 / 评论 / 操作；新建与编辑
 * 直接以列表顶部的卡片形式出现，不使用悬浮弹窗。数据与 Agent 工具同源。
 */
const emit = defineEmits<{ (e: 'count', value: number): void }>()

const items = ref<GitIssueRow[]>([])
const loading = ref(false)
const error = ref('')
const keyword = ref('')
const stateFilter = ref('')
const kindFilter = ref('')
const priorityFilter = ref<GitIssuePriority | ''>('')
const selectedId = ref('')
const comments = ref<GitIssueCommentRow[]>([])
const commentsLoading = ref(false)
const commentText = ref('')
const commenting = ref(false)
const actionBusy = ref('')

// ---------------------------------------------------------------- Bug 自动修复

/** 自动修复阶段（与后端状态机一致，DONE 只在结束后出现）。 */
const FIX_STAGES: { key: BugFixStage; label: string; hint: string }[] = [
  { key: 'ANALYZE', label: '分析版本', hint: '锁定基线提交并给出根因与影响文件' },
  { key: 'REPRO_DEPLOY', label: '部署测试环境', hint: '把基线提交发布到测试环境' },
  { key: 'REPRODUCE', label: '尝试复现', hint: '按复现步骤确认问题可重现' },
  { key: 'FIX', label: '代码修复', hint: '生成补丁并提交到独立修复分支' },
  { key: 'VERIFY_DEPLOY', label: '部署修复版本', hint: '重新部署修复提交' },
  { key: 'VERIFY', label: '验证修复', hint: '确认问题已解决且无回归' },
  { key: 'PULL_REQUEST', label: '提交 PR', hint: '推送分支并创建 PR，等待人工合并' },
]

const FIX_STATUS_LABEL: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  WAITING_MERGE: '等待合并',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  BLOCKED: '待人工处理',
  CANCELLED: '已取消',
}

const fixLoading = ref(false)
const fixBusy = ref('')
const fixRun = ref<BugFixRunRow | null>(null)
const fixEvents = ref<BugFixEventRow[]>([])
const fixStages = ref<BugFixStageRunRow[]>([])
let fixTimer: ReturnType<typeof setTimeout> | undefined

const fixActive = computed(
  () => !!fixRun.value && ['QUEUED', 'RUNNING'].includes(fixRun.value.status))

function stageIndex(stage?: string): number {
  if (stage === 'DONE') return FIX_STAGES.length
  const index = FIX_STAGES.findIndex((s) => s.key === stage)
  return index < 0 ? 0 : index
}

/** 阶段状态：done / active / error / muted / pending。 */
function stageState(stage: BugFixStage, index: number): string {
  const run = fixRun.value
  if (!run) return 'pending'
  if (run.stage === 'DONE' || run.status === 'SUCCEEDED') return 'done'
  // PR 已创建但等人工合并：PR 阶段视为完成，进度 100%
  if (run.status === 'WAITING_MERGE') return 'done'
  const current = stageIndex(run.stage)
  if (index < current) return 'done'
  if (index > current) return 'pending'
  if (run.status === 'FAILED' || run.status === 'BLOCKED') return 'error'
  if (run.status === 'CANCELLED') return 'muted'
  return 'active'
}

function fixStatusLabel(status?: string): string {
  return status ? (FIX_STATUS_LABEL[status] ?? status) : ''
}

function stageLabel(stage?: string): string {
  if (stage === 'DONE') return '全部完成'
  return FIX_STAGES.find((s) => s.key === stage)?.label ?? (stage ?? '')
}

function formatDuration(ms: number): string {
  if (!ms || ms < 0) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  return `${Math.floor(ms / 60_000)}m${Math.round((ms % 60_000) / 1000)}s`
}

/** 阶段记录按时间正序展示最近 12 条，验证失败回到修复的每一轮都保留。 */
const fixStageRuns = computed(() => fixStages.value.slice(-12))

/** 当前阶段序号（1/7），DONE 时显示 7/7。 */
const fixStageNo = computed(() => {
  const run = fixRun.value
  if (!run) return ''
  if (run.stage === 'DONE') return `${FIX_STAGES.length}/${FIX_STAGES.length}`
  return `${stageIndex(run.stage) + 1}/${FIX_STAGES.length}`
})

const fixStageLabel = computed(() => {
  const run = fixRun.value
  if (!run) return ''
  if (run.stage === 'DONE') return '全部完成'
  return FIX_STAGES.find((s) => s.key === run.stage)?.label ?? run.stage
})

/** 失败/阻塞时给出可操作的建议，而不是只显示一行错误。 */
const fixHint = computed(() => {
  const run = fixRun.value
  if (!run) return ''
  if (run.status === 'BLOCKED') {
    const hints: Record<string, string> = {
      REPRO_DEPLOY: '检查策略里的测试环境与「已确认」的环境绑定，补齐后点重试。',
      REPRODUCE: '测试环境未复现或证据不足；请补充复现步骤/日志，人工确认后再重试。',
      FIX: '补丁未通过校验（片段不唯一或文件已变更）；可重试让 Agent 重新分析，或人工检查保留的修复分支。',
      VERIFY_DEPLOY: '修复版本部署失败；查看时间线错误并检查环境与构建日志。',
      VERIFY: '验证证据不足或置信度不够；请人工确认测试环境状态后再重试。',
      PULL_REQUEST: 'PR 未创建：确认已绑定 Git 账号与仓库权限，修复分支已保留。',
    }
    return hints[run.stage] ?? '前置条件不满足，补齐后点重试。'
  }
  if (run.status === 'FAILED') {
    return '执行失败，可点重试从当前阶段继续；连续失败请人工介入查看时间线。'
  }
  if (run.status === 'CANCELLED') return '已取消；点重试会从当前阶段继续。'
  if (run.status === 'QUEUED') return '已排队；同一项目的修复会串行执行，避免同时部署测试环境。'
  return ''
})

async function confirmCancelFix(row: GitIssueRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '取消后正在执行的阶段结果会被丢弃，可从当前阶段重试继续。',
      '取消自动修复',
      { type: 'warning', confirmButtonText: '取消运行', cancelButtonText: '继续运行' },
    )
  } catch {
    return
  }
  await cancelFix(row)
}

async function copyBranch(): Promise<void> {
  const branch = fixRun.value?.branch
  if (!branch) return
  try {
    await navigator.clipboard.writeText(branch)
    ElMessage.success('分支名已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择：' + branch)
  }
}

async function loadBugFix(issueId: string): Promise<void> {
  fixLoading.value = true
  try {
    const rows = await listBugFixRuns(issueId)
    if (selectedId.value !== issueId) return
    if (!rows.length) {
      fixRun.value = null
      fixEvents.value = []
      fixStages.value = []
      return
    }
    const detail = await getBugFixRun(rows[0].id)
    if (selectedId.value !== issueId) return
    fixRun.value = detail.run
    fixEvents.value = detail.events
    fixStages.value = detail.stages ?? []
  } catch {
    if (selectedId.value === issueId) {
      fixRun.value = null
      fixEvents.value = []
      fixStages.value = []
    }
  } finally {
    if (selectedId.value === issueId) fixLoading.value = false
  }
}

/** 运行中每 3 秒刷新一次，结束后自动停止，避免常驻轮询。 */
function scheduleFixPoll(): void {
  clearTimeout(fixTimer)
  const run = fixRun.value
  const issueId = selectedId.value
  if (!issueId || !run || !['QUEUED', 'RUNNING'].includes(run.status)) return
  fixTimer = setTimeout(async () => {
    if (selectedId.value !== issueId) return
    await loadBugFix(issueId)
    scheduleFixPoll()
  }, 3000)
}

async function startFix(row: GitIssueRow): Promise<void> {
  fixBusy.value = 'start'
  try {
    fixRun.value = await startBugFix(row.id)
    fixEvents.value = []
    fixStages.value = []
    ElMessage.success('已开始自动修复')
    scheduleFixPoll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '启动自动修复失败')
  } finally {
    fixBusy.value = ''
  }
}

async function retryFix(row: GitIssueRow): Promise<void> {
  const run = fixRun.value
  if (!run) return
  fixBusy.value = 'retry'
  try {
    fixRun.value = await retryBugFix(run.id)
    await loadBugFix(row.id)
    ElMessage.success('已重新排队，将从中断阶段继续')
    scheduleFixPoll()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '重试失败')
  } finally {
    fixBusy.value = ''
  }
}

async function cancelFix(row: GitIssueRow): Promise<void> {
  const run = fixRun.value
  if (!run) return
  fixBusy.value = 'cancel'
  try {
    fixRun.value = await cancelBugFix(run.id)
    await loadBugFix(row.id)
    ElMessage.success('已取消本次自动修复')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '取消失败')
  } finally {
    fixBusy.value = ''
  }
}

// 自动修复策略（测试环境 + 已确认环境绑定 + PR 开关）
const policyOpen = ref(false)
const policyLoading = ref(false)
const policySaving = ref(false)
const policy = ref<BugFixPolicyRow | null>(null)
const policyForm = reactive({
  enabled: false,
  autoDeploy: true,
  autoCreatePr: true,
  testEnv: 'test',
  bindingId: '',
  maxAttempts: 2,
  baseUrl: '',
  buildCommand: '',
  testCommand: '',
})

async function togglePolicy(): Promise<void> {
  policyOpen.value = !policyOpen.value
  if (!policyOpen.value) return
  policyLoading.value = true
  try {
    const current = await getBugFixPolicy()
    policy.value = current
    Object.assign(policyForm, {
      enabled: current.enabled,
      autoDeploy: current.autoDeploy,
      autoCreatePr: current.autoCreatePr,
      testEnv: current.testEnv,
      bindingId: current.bindingId,
      maxAttempts: current.maxAttempts,
      baseUrl: current.baseUrl ?? '',
      buildCommand: current.buildCommand ?? '',
      testCommand: current.testCommand ?? '',
    })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '读取自动修复策略失败')
  } finally {
    policyLoading.value = false
  }
}

async function submitPolicy(): Promise<void> {
  policySaving.value = true
  try {
    policy.value = await saveBugFixPolicy({ ...policyForm })
    ElMessage.success('自动修复策略已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存策略失败')
  } finally {
    policySaving.value = false
  }
}

const stateOptions = [
  { label: '全部', value: '' },
  { label: '待处理', value: 'OPEN' },
  { label: '已完成', value: 'CLOSED' },
]

/** 任务分类：Bug（缺陷） / Feat（需求）。 */
const kindOptions = [
  { label: '全部类型', value: '' },
  { label: 'Bug', value: 'BUG' },
  { label: 'Feat', value: 'FEAT' },
]

/** 任务页视图：列表（默认）/ 量化看板。 */
const taskView = ref<'list' | 'board'>('list')

/** 优先级：P0 最高，P2 普通。 */
const PRIORITY_VALUES: GitIssuePriority[] = ['P0', 'P1', 'P2']
const PRIORITY_RANK: Record<string, number> = { P0: 0, P1: 1, P2: 2 }
const priorityTabs: { label: string; value: GitIssuePriority | '' }[] = [
  { label: '全部', value: '' },
  { label: 'P0', value: 'P0' },
  { label: 'P1', value: 'P1' },
  { label: 'P2', value: 'P2' },
]

/** 列表排序：默认最近更新优先；任务多时按状态/标题更好找。 */
const sortBy = ref<'updated' | 'created' | 'title' | 'priority'>('updated')

/** 分类、优先级与排序在前端处理：数据量小，切换无需再请求。 */
const visibleItems = computed(() => {
  let rows = [...items.value]
  if (kindFilter.value) rows = rows.filter((i) => (i.kind ?? 'BUG') === kindFilter.value)
  if (priorityFilter.value) {
    rows = rows.filter((i) => (i.priority ?? 'P2') === priorityFilter.value)
  }
  return rows.sort((a, b) => {
    if (sortBy.value === 'priority') {
      const diff = (PRIORITY_RANK[a.priority ?? 'P2'] ?? 2)
        - (PRIORITY_RANK[b.priority ?? 'P2'] ?? 2)
      if (diff) return diff
      return b.updatedAt - a.updatedAt
    }
    if (sortBy.value === 'title') return a.title.localeCompare(b.title, 'zh-CN')
    if (sortBy.value === 'created') return b.createdAt - a.createdAt
    return b.updatedAt - a.updatedAt
  })
})

/** 看板量化：完成率、优先级分布、类型构成。 */
const stats = computed(() => {
  const rows = items.value
  const total = rows.length
  const open = rows.filter((r) => r.state === 'OPEN').length
  const closed = total - open
  const countOf = (p: GitIssuePriority) => rows.filter((r) => (r.priority ?? 'P2') === p).length
  const p0 = countOf('P0')
  const p1 = countOf('P1')
  const p2 = Math.max(0, total - p0 - p1)
  const bug = rows.filter((r) => (r.kind ?? 'BUG') === 'BUG').length
  const feat = total - bug
  const percent = total ? Math.round((closed / total) * 100) : 0
  return { total, open, closed, p0, p1, p2, bug, feat, percent }
})

/** 近 7 天按天分桶：新增（按类型）与完成数，用于趋势/吞吐图。 */
const daily = computed(() => {
  const buckets: { label: string; start: number; bug: number; feat: number; done: number }[] = []
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  for (let i = 6; i >= 0; i -= 1) {
    const start = today.getTime() - i * 86_400_000
    buckets.push({
      label: `${new Date(start).getMonth() + 1}/${new Date(start).getDate()}`,
      start, bug: 0, feat: 0, done: 0,
    })
  }
  const bucketOf = (ts: number) => buckets.find((b) => ts >= b.start && ts < b.start + 86_400_000)
  for (const row of items.value) {
    const created = bucketOf(row.createdAt)
    if (created) {
      if ((row.kind ?? 'BUG') === 'BUG') created.bug += 1
      else created.feat += 1
    }
    if (row.state === 'CLOSED') {
      const done = bucketOf(row.updatedAt)
      if (done) done.done += 1
    }
  }
  return {
    buckets,
    maxCreated: Math.max(1, ...buckets.map((b) => b.bug + b.feat)),
    maxDone: Math.max(1, ...buckets.map((b) => b.done)),
  }
})

/** 近 7 天完成总数与平均闭环时长（小时，近似用 updatedAt - createdAt）。 */
const weeklyDone = computed(() => daily.value.buckets.reduce((sum, b) => sum + b.done, 0))
const avgCycleHours = computed(() => {
  const closed = items.value.filter((i) => i.state === 'CLOSED')
  if (!closed.length) return 0
  const total = closed.reduce((sum, i) => sum + Math.max(0, i.updatedAt - i.createdAt), 0)
  return Math.round((total / closed.length / 3_600_000) * 10) / 10
})

/** 未完成任务的优先级 × 类型矩阵。 */
const priorityMatrix = computed(() => PRIORITY_VALUES.map((key) => {
  const openRows = items.value.filter(
    (i) => i.state === 'OPEN' && (i.priority ?? 'P2') === key)
  return {
    key,
    bug: openRows.filter((i) => (i.kind ?? 'BUG') === 'BUG').length,
    feat: openRows.filter((i) => (i.kind ?? 'BUG') === 'FEAT').length,
    total: openRows.length,
  }
}))

/** 风险项：同步失败的任务（远端推送失败需要重试）。 */
const riskTasks = computed(() => items.value.filter((i) => i.syncStatus === 'FAILED').slice(0, 5))

const priorityBars = computed(() => {
  const total = stats.value.total || 1
  return PRIORITY_VALUES.map((key) => {
    const count = stats.value[key.toLowerCase() as 'p0' | 'p1' | 'p2']
    return { key, count, percent: Math.round((count / total) * 100) }
  })
})

/** 大环：看板里的完成率主视觉。 */
const RING_RADIUS = 82
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS
const ringDash = RING_CIRCUMFERENCE.toFixed(1)
const ringOffset = computed(
  () => (RING_CIRCUMFERENCE * (1 - stats.value.percent / 100)).toFixed(1))

/** 同步状态：已同步 / 仅本地 / 失败。 */
const syncStats = computed(() => ({
  synced: items.value.filter((i) => i.syncStatus === 'SYNCED').length,
  local: items.value.filter((i) => i.syncStatus !== 'SYNCED' && i.syncStatus !== 'FAILED').length,
  failed: items.value.filter((i) => i.syncStatus === 'FAILED').length,
}))

/** 看板「优先处理」：未关闭任务按优先级取前 6 条。 */
const focusTasks = computed(() => items.value
  .filter((i) => i.state === 'OPEN')
  .sort((a, b) => (PRIORITY_RANK[a.priority ?? 'P2'] ?? 2)
    - (PRIORITY_RANK[b.priority ?? 'P2'] ?? 2) || b.updatedAt - a.updatedAt)
  .slice(0, 6))

/** 看板点优先级：带筛选切回列表，形成「看板 → 明细」闭环。 */
function focusPriority(priority: GitIssuePriority | '') {
  priorityFilter.value = priority
  taskView.value = 'list'
}

/** 看板点状态指标：按状态切回列表。 */
function focusState(state: '' | 'OPEN' | 'CLOSED') {
  stateFilter.value = state
  priorityFilter.value = ''
  taskView.value = 'list'
}

/** 看板点任务卡片：切回列表并选中详情。 */
async function openFromBoard(row: GitIssueRow): Promise<void> {
  taskView.value = 'list'
  selectedId.value = ''
  await select(row)
}

/** 概览并进页头：只保留两个关键数字，不再占一整块统计卡片。 */
const openCount = computed(() => items.value.filter((i) => i.state === 'OPEN').length)
const closedCount = computed(() => items.value.length - openCount.value)
/** 右侧详情当前选中的任务。 */
const selectedRow = computed(() => items.value.find((i) => i.id === selectedId.value) ?? null)

const PROVIDER_LABEL: Record<string, string> = {
  GITEE: 'Gitee', GITHUB: 'GitHub', GITLAB: 'GitLab',
}

const projects = ref<ProjectRow[]>([])
const currentProject = computed(
  () => projects.value.find((p) => p.id === appStore.projectId) ?? null)

/** 当前项目托管的仓库（通常只有一个），用于页头提示与新建任务同步目标。 */
const currentRepo = computed(() => {
  const url = currentProject.value?.repoUrl
  return { provider: providerOfUrl(url), repo: repoOfUrl(url) }
})

/** 已绑定仓库去重：只有一个仓库时行内只显示 #编号，避免每行重复仓库名。 */
const boundRepos = computed(() => [
  ...new Set(items.value.filter((i) => isBound(i)).map((i) => `${i.provider}/${i.repo}`)),
])
const singleRepo = computed(() => boundRepos.value.length <= 1)

let loadSeq = 0
let keywordTimer: ReturnType<typeof setTimeout> | undefined

function providerLabel(provider: string): string {
  return PROVIDER_LABEL[provider] ?? (provider || 'Git')
}

function providerOfUrl(url?: string | null): string {
  const text = (url ?? '').toLowerCase()
  if (text.includes('gitee')) return 'GITEE'
  if (text.includes('gitlab')) return 'GITLAB'
  if (text.includes('github')) return 'GITHUB'
  return ''
}

/** https://host/owner/repo(.git) 或 git@host:owner/repo.git → owner/repo */
function repoOfUrl(url?: string | null): string {
  if (!url) return ''
  return url
    .replace(/^git@([^:]+):/, 'https://$1/')
    .replace(/^https?:\/\/[^/]+\//, '')
    .replace(/\.git$/, '')
    .replace(/\/+$/, '')
}

function syncLabel(row: GitIssueRow): string {
  if (row.syncStatus === 'SYNCED') return '已同步'
  if (row.syncStatus === 'FAILED') return '同步失败'
  if (row.syncStatus === 'PENDING') return '同步中'
  return '仅本地'
}

function formatTime(ts?: number): string {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  })
}

function labelList(labels: string): string[] {
  return (labels ?? '').split(/[,\n]/).map((s) => s.trim()).filter(Boolean)
}

function kindLabel(kind?: string): string {
  return kind === 'FEAT' ? 'Feat' : 'Bug'
}

function isBound(row: GitIssueRow): boolean {
  return !!row.provider && !!row.repo && !!row.number
}

function remoteText(row: GitIssueRow): string {
  if (!isBound(row)) return '仅本地'
  return singleRepo.value ? `#${row.number}` : `${row.repo}#${row.number}`
}

async function load(silent = false): Promise<void> {
  const seq = ++loadSeq
  if (!silent) loading.value = true
  error.value = ''
  try {
    const rows = await listGitIssues({
      keyword: keyword.value.trim() || undefined,
      state: stateFilter.value || undefined,
    })
    if (seq !== loadSeq) return
    items.value = rows
    emit('count', rows.filter((r) => r.state === 'OPEN').length)
    if (!rows.some((r) => r.id === selectedId.value)) {
      selectedId.value = ''
    }
    if (pendingFocusId.value) {
      const target = rows.find((r) => r.id === pendingFocusId.value)
      pendingFocusId.value = ''
      if (target) {
        selectedId.value = ''
        void select(target)
      }
    }
  } catch (e) {
    if (seq === loadSeq) error.value = e instanceof Error ? e.message : '加载任务失败'
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

watch(keyword, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => void load(), 260)
})
watch(stateFilter, () => void load())
watch(kindFilter, () => {
  if (selectedId.value && !visibleItems.value.some((i) => i.id === selectedId.value)) {
    selectedId.value = ''
  }
})
watch(priorityFilter, () => {
  if (selectedId.value && !visibleItems.value.some((i) => i.id === selectedId.value)) {
    selectedId.value = ''
  }
})

onMounted(() => {
  void load()
  void listProjects().then((rows) => { projects.value = rows }).catch(() => {})
})

onUnmounted(() => clearTimeout(fixTimer))

// ---------------------------------------------------------------- 选中 / 评论

/** 左列选中一条任务，右列加载它的评论与自动修复状态（已选中则不重复请求）。 */
async function select(row: GitIssueRow): Promise<void> {
  if (selectedId.value === row.id) return
  selectedId.value = row.id
  editorOpen.value = false
  commentText.value = ''
  clearTimeout(fixTimer)
  fixRun.value = null
  fixEvents.value = []
  fixStages.value = []
  await loadComments()
  if ((row.kind ?? 'BUG') === 'BUG') {
    await loadBugFix(row.id)
    scheduleFixPoll()
  }
}

async function loadComments(): Promise<void> {
  if (!selectedId.value) {
    comments.value = []
    return
  }
  const id = selectedId.value
  commentsLoading.value = true
  try {
    const rows = await listGitIssueComments(id)
    if (selectedId.value === id) comments.value = rows
  } catch (e) {
    comments.value = []
    ElMessage.error(e instanceof Error ? e.message : '加载评论失败')
  } finally {
    commentsLoading.value = false
  }
}

async function submitComment(): Promise<void> {
  const id = selectedId.value
  const text = commentText.value.trim()
  if (!id || !text) return
  commenting.value = true
  try {
    const comment = await addGitIssueComment(id, text)
    comments.value = [...comments.value, comment]
    commentText.value = ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '评论失败')
  } finally {
    commenting.value = false
  }
}

async function removeComment(comment: GitIssueCommentRow): Promise<void> {
  const id = selectedId.value
  if (!id || comment.source !== 'PLATFORM') return
  try {
    await deleteGitIssueComment(id, comment.id)
    comments.value = comments.value.filter((c) => c.id !== comment.id)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除评论失败')
  }
}

// ---------------------------------------------------------------- 新建 / 编辑

const editorOpen = ref(false)
const editingId = ref('')
const saving = ref(false)
const form = reactive({
  title: '',
  body: '',
  labels: '',
  kind: 'BUG' as 'BUG' | 'FEAT',
  priority: 'P2' as GitIssuePriority,
})

const editorTarget = computed(() => {
  const row = editingId.value
    ? items.value.find((i) => i.id === editingId.value)
    : undefined
  if (row && isBound(row)) {
    return { provider: row.provider, repo: row.repo }
  }
  return currentRepo.value
})

function openCreate(): void {
  taskView.value = 'list'
  editingId.value = ''
  Object.assign(form, { title: '', body: '', labels: '', kind: 'BUG', priority: 'P2' })
  editorOpen.value = true
  selectedId.value = ''
}

function openEdit(row: GitIssueRow): void {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title, body: row.body, labels: row.labels, kind: row.kind ?? 'BUG',
    priority: row.priority ?? 'P2',
  })
  editorOpen.value = true
}

function closeEditor(): void {
  editorOpen.value = false
  editingId.value = ''
}

// ---------------------------------------------------------------- 配图

/** 上传中标记：按钮与拖拽区共用，避免重复上传。 */
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function pickImage(): void {
  fileInput.value?.click()
}

/** 上传图片并把 Markdown 图片语法追加到正文/评论末尾。 */
async function insertImage(file: File, target: 'body' | 'comment'): Promise<void> {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('只支持图片文件')
    return
  }
  uploading.value = true
  try {
    const url = await uploadIssueImage(file)
    const alt = file.name.replace(/[[\]()]/g, '').trim() || '图片'
    const markdown = `\n![${alt}](${url})\n`
    if (target === 'body') {
      form.body = `${form.body.replace(/\s*$/, '')}${markdown}`
    } else {
      commentText.value = `${commentText.value.replace(/\s*$/, '')}${markdown}`
    }
    ElMessage.success('图片已插入')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '图片上传失败')
  } finally {
    uploading.value = false
  }
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) void insertImage(file, 'body')
  input.value = ''
}

/** 粘贴图片：直接从剪贴板上传，不再需要先存文件。 */
function onPasteImage(event: ClipboardEvent, target: 'body' | 'comment'): void {
  const items = event.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (!item.type.startsWith('image/')) continue
    const file = item.getAsFile()
    if (!file) continue
    event.preventDefault()
    void insertImage(file, target)
    return
  }
}

/** 拖拽图片到输入区。 */
function onDropImage(event: DragEvent, target: 'body' | 'comment'): void {
  const file = event.dataTransfer?.files?.[0]
  if (!file || !file.type.startsWith('image/')) return
  event.preventDefault()
  void insertImage(file, target)
}

async function submit(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('请填写任务标题')
    return
  }
  saving.value = true
  try {
    const editingRow = editingId.value
      ? items.value.find((i) => i.id === editingId.value)
      : undefined
    const payload = {
      // 新建跟随当前项目；编辑保留原归属，避免把已绑定任务换到别的仓库
      projectId: editingRow ? editingRow.projectId : appStore.projectId,
      title: form.title.trim(),
      body: form.body,
      labels: form.labels.trim(),
      kind: form.kind,
      priority: form.priority,
      state: editingRow?.state ?? ('OPEN' as const),
    }
    const row = editingId.value
      ? await updateGitIssue(editingId.value, payload)
      : await createGitIssue(payload)
    if (row.syncStatus === 'FAILED') {
      ElMessage.warning(`任务已保存，但同步到 Git 失败：${row.syncError}`)
    } else {
      ElMessage.success(editingId.value ? '任务已更新' : '任务已创建')
    }
    closeEditor()
    await load(true)
    selectedId.value = row.id
    await loadComments()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存任务失败')
  } finally {
    saving.value = false
  }
}

// ---------------------------------------------------------------- 状态 / 同步 / 删除

function replaceRow(row: GitIssueRow): void {
  const index = items.value.findIndex((i) => i.id === row.id)
  if (index >= 0) items.value[index] = row
  emit('count', items.value.filter((i) => i.state === 'OPEN').length)
}

async function toggleState(row: GitIssueRow): Promise<void> {
  actionBusy.value = 'state:' + row.id
  try {
    const next = row.state === 'OPEN'
      ? await closeGitIssue(row.id)
      : await reopenGitIssue(row.id)
    replaceRow(next)
    if (next.syncStatus === 'FAILED') {
      ElMessage.warning(`本地状态已更新，同步 Git 失败：${next.syncError}`)
    } else {
      ElMessage.success(next.state === 'CLOSED' ? '任务已关闭' : '任务已重开')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新状态失败')
  } finally {
    actionBusy.value = ''
  }
}

async function resync(row: GitIssueRow): Promise<void> {
  actionBusy.value = 'sync:' + row.id
  try {
    const next = await syncGitIssue(row.id)
    replaceRow(next)
    if (next.syncStatus === 'FAILED') {
      ElMessage.error(`同步失败：${next.syncError}`)
    } else {
      ElMessage.success('已同步到 Git')
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '同步失败')
  } finally {
    actionBusy.value = ''
  }
}

async function removeIssue(row: GitIssueRow): Promise<void> {
  try {
    await ElMessageBox.confirm(
      isBound(row)
        ? `删除任务「${row.title}」？仅解除平台绑定，不会删除 Git 上的 Issue。`
        : `删除任务「${row.title}」？`,
      '删除任务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  actionBusy.value = 'delete:' + row.id
  try {
    await deleteGitIssue(row.id)
    items.value = items.value.filter((i) => i.id !== row.id)
    if (selectedId.value === row.id) selectedId.value = ''
    emit('count', items.value.filter((i) => i.state === 'OPEN').length)
    ElMessage.success('任务已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    actionBusy.value = ''
  }
}

// ---------------------------------------------------------------- 对外能力

/** 首次加载完成前收到的定位请求，等列表回来后再选中。 */
const pendingFocusId = ref('')

/** 工作区右栏点任务：切到列表视图并定位到这条工单。 */
async function focus(id: string): Promise<void> {
  taskView.value = 'list'
  const row = items.value.find((i) => i.id === id)
  if (row) {
    await select(row)
    return
  }
  pendingFocusId.value = id
}

defineExpose({ openCreate, focus })
</script>

<template>
  <div class="issues-view">
    <input
      ref="fileInput" type="file" accept="image/png,image/jpeg,image/gif,image/webp"
      class="hidden-file" @change="onFileChange"
    />
    <div class="issues-shell">
      <header class="issues-head">
        <div class="issues-head-main">
          <div class="issues-title-row">
            <h1 class="issues-title">任务</h1>
            <div class="view-switch">
              <button
                type="button" class="view-switch-btn" :class="{ active: taskView === 'list' }"
                @click="taskView = 'list'"
              >列表</button>
              <button
                type="button" class="view-switch-btn" :class="{ active: taskView === 'board' }"
                @click="taskView = 'board'"
              >看板</button>
            </div>
            <span class="issues-sub">
              {{ openCount }} 待处理 · {{ closedCount }} 已完成 · Git 双向同步
            </span>
          </div>
          <p class="issues-desc">
            项目托管的 Git Issue 自动双向同步；Agent 可读取并操作同一批任务。
          </p>
        </div>
        <div class="issues-head-side">
          <span v-if="currentRepo.repo" class="repo-chip">
            <span class="repo-dot" />
            {{ providerLabel(currentRepo.provider) }}
            <span class="mono">{{ currentRepo.repo }}</span>
          </span>
          <el-button size="small" @click="togglePolicy">
            <el-icon><Setting /></el-icon> 自动修复
          </el-button>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon> 新建任务
          </el-button>
          <el-button size="small" :loading="loading" @click="load()">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </header>

      <!-- 量化看板：只在「看板」Tab 下展示 -->
      <section v-if="taskView === 'board'" class="board-view">
        <div class="board-hero">
          <div class="hero-ring">
            <svg viewBox="0 0 200 200" aria-hidden="true">
              <defs>
                <linearGradient id="ringGrad" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0%" stop-color="#4d82f7" />
                  <stop offset="55%" stop-color="#6e8cff" />
                  <stop offset="100%" stop-color="#2ea36b" />
                </linearGradient>
                <filter id="ringGlow" x="-30%" y="-30%" width="160%" height="160%">
                  <feGaussianBlur stdDeviation="6" result="blur" />
                  <feMerge>
                    <feMergeNode in="blur" />
                    <feMergeNode in="SourceGraphic" />
                  </feMerge>
                </filter>
              </defs>
              <circle class="ring-track" cx="100" cy="100" :r="RING_RADIUS" />
              <circle
                class="ring-value" cx="100" cy="100" :r="RING_RADIUS"
                :stroke-dasharray="ringDash"
                :stroke-dashoffset="ringOffset" filter="url(#ringGlow)"
              />
            </svg>
            <div class="ring-center">
              <b class="mono">{{ stats.percent }}%</b>
              <span>完成率</span>
              <em class="mono">{{ stats.closed }} / {{ stats.total }}</em>
            </div>
          </div>

          <div class="hero-side">
            <div class="hero-head">
              <h2>任务健康度</h2>
              <p>实时汇总任务页的全部工单，点任意指标可跳回列表查看明细。</p>
            </div>
            <div class="hero-metrics">
              <button type="button" class="metric" @click="focusState('OPEN')">
                <span class="metric-label">待处理</span>
                <b class="metric-value mono">{{ stats.open }}</b>
                <span class="metric-foot">全部未关闭</span>
              </button>
              <button type="button" class="metric ok" @click="focusState('CLOSED')">
                <span class="metric-label">已完成</span>
                <b class="metric-value mono">{{ stats.closed }}</b>
                <span class="metric-foot">闭环工单</span>
              </button>
              <button type="button" class="metric p0" @click="focusPriority('P0')">
                <span class="metric-label">P0 紧急</span>
                <b class="metric-value mono">{{ stats.p0 }}</b>
                <span class="metric-foot">立即处理</span>
              </button>
              <button type="button" class="metric fail" @click="focusState('')">
                <span class="metric-label">同步失败</span>
                <b class="metric-value mono">{{ syncStats.failed }}</b>
                <span class="metric-foot">需要重试</span>
              </button>
            </div>
          </div>
        </div>

        <!-- 趋势：新增（Bug/Feat 堆叠）与完成吞吐 -->
        <div class="board-grid wide">
          <div class="bpanel">
            <div class="bpanel-head">
              <span class="bpanel-title">新增任务趋势</span>
              <span class="bpanel-hint">近 7 天 · Bug / Feat</span>
            </div>
            <div class="stack-legend">
              <span><i class="dot bug" />Bug</span>
              <span><i class="dot feat" />Feat</span>
            </div>
            <div class="stack-chart">
              <div v-for="day in daily.buckets" :key="day.label" class="stack-col">
                <span class="stack-total mono">{{ day.bug + day.feat }}</span>
                <div class="stack-bar-area">
                  <i
                    class="stack-bar feat"
                    :style="{ height: `${(day.feat / daily.maxCreated) * 100}%` }"
                  />
                  <i
                    class="stack-bar bug"
                    :style="{ height: `${(day.bug / daily.maxCreated) * 100}%` }"
                  />
                </div>
                <span class="stack-label">{{ day.label }}</span>
              </div>
            </div>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <span class="bpanel-title">完成吞吐</span>
              <span class="bpanel-hint">近 7 天完成 {{ weeklyDone }} 个</span>
            </div>
            <div class="throughput-stats">
              <div class="tp-stat">
                <b class="mono">{{ weeklyDone }}</b>
                <span>7 天完成</span>
              </div>
              <div class="tp-stat">
                <b class="mono">{{ avgCycleHours }}</b>
                <span>平均闭环 / 小时</span>
              </div>
            </div>
            <div class="done-chart">
              <div v-for="day in daily.buckets" :key="day.label" class="done-col">
                <span class="done-count mono">{{ day.done }}</span>
                <span class="done-bar-wrap">
                  <i
                    class="done-bar"
                    :style="{ height: `${Math.max(5, (day.done / daily.maxDone) * 100)}%` }"
                  />
                </span>
                <span class="done-label">{{ day.label }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 分布：优先级 / 未完成矩阵 / 类型与同步 -->
        <div class="board-grid">
          <div class="bpanel">
            <div class="bpanel-head">
              <span class="bpanel-title">优先级分布</span>
              <span class="bpanel-hint">点击切换列表</span>
            </div>
            <button
              v-for="bar in priorityBars" :key="bar.key" type="button"
              class="pbar-row" @click="focusPriority(bar.key)"
            >
              <span class="pbar-key" :class="bar.key.toLowerCase()">{{ bar.key }}</span>
              <span class="pbar-track">
                <i :class="bar.key.toLowerCase()" :style="{ width: `${bar.percent}%` }" />
              </span>
              <span class="pbar-num mono">{{ bar.count }}</span>
              <span class="pbar-pct mono">{{ bar.percent }}%</span>
            </button>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <span class="bpanel-title">未完成优先级矩阵</span>
              <span class="bpanel-hint">优先级 × 类型</span>
            </div>
            <div class="matrix-head">
              <span />
              <span>Bug</span>
              <span>Feat</span>
            </div>
            <button
              v-for="cell in priorityMatrix" :key="cell.key" type="button"
              class="matrix-row" @click="focusPriority(cell.key)"
            >
              <span class="pbar-key" :class="cell.key.toLowerCase()">{{ cell.key }}</span>
              <span class="matrix-cell mono">{{ cell.bug }}</span>
              <span class="matrix-cell mono">{{ cell.feat }}</span>
            </button>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <span class="bpanel-title">类型构成</span>
              <span class="bpanel-hint">Bug / Feat</span>
            </div>
            <div class="type-split">
              <span class="type-bar bug" :style="{ flexGrow: stats.bug }" />
              <span class="type-bar feat" :style="{ flexGrow: stats.feat }" />
            </div>
            <div class="type-legend">
              <span><i class="dot bug" />Bug <b class="mono">{{ stats.bug }}</b></span>
              <span><i class="dot feat" />Feat <b class="mono">{{ stats.feat }}</b></span>
            </div>
            <div class="sync-list">
              <span><i class="dot synced" />已同步 <b class="mono">{{ syncStats.synced }}</b></span>
              <span><i class="dot local" />仅本地 <b class="mono">{{ syncStats.local }}</b></span>
              <span><i class="dot failed" />失败 <b class="mono">{{ syncStats.failed }}</b></span>
            </div>
          </div>
        </div>

        <!-- 行动：优先处理 + 风险项 -->
        <div class="board-grid wide">
          <div class="bpanel focus-panel">
            <div class="bpanel-head">
              <span class="bpanel-title">优先处理</span>
              <span class="bpanel-hint">未关闭任务按优先级排序</span>
            </div>
            <div v-if="!focusTasks.length" class="board-empty">暂无未关闭任务</div>
            <button
              v-for="row in focusTasks" :key="row.id" type="button"
              class="focus-row" @click="openFromBoard(row)"
            >
              <span class="prio-pill" :class="(row.priority ?? 'P2').toLowerCase()">
                {{ row.priority ?? 'P2' }}
              </span>
              <span class="focus-title">{{ row.title }}</span>
              <span class="focus-meta">{{ kindLabel(row.kind) }} · {{ formatTime(row.updatedAt) }}</span>
              <span class="focus-arrow">→</span>
            </button>
          </div>

          <div class="bpanel">
            <div class="bpanel-head">
              <span class="bpanel-title">风险项</span>
              <span class="bpanel-hint">同步失败需重试</span>
            </div>
            <div v-if="!riskTasks.length" class="risk-ok">
              <span class="risk-ok-dot" />
              所有任务同步正常
            </div>
            <button
              v-for="row in riskTasks" :key="row.id" type="button"
              class="focus-row" @click="openFromBoard(row)"
            >
              <span class="risk-badge">同步失败</span>
              <span class="focus-title">{{ row.title }}</span>
              <span class="focus-arrow">→</span>
            </button>
          </div>
        </div>
      </section>

      <!-- 自动修复策略：页内卡片，说明清楚“PR 必须人工合并” -->
      <section v-if="policyOpen" class="policy-card">
        <div class="editor-head">
          <div>
            <h2 class="editor-title">Bug 自动修复</h2>
            <p class="editor-desc">
              Bug 任务创建后自动：分析版本 → 部署测试环境复现 → 修复 → 重新部署验证 → 提交 PR。
              平台只创建 PR，最终合并必须由人工完成。
            </p>
          </div>
          <el-button text size="small" @click="policyOpen = false">收起</el-button>
        </div>
        <div v-if="policyLoading" class="block-empty">加载策略…</div>
        <div v-else class="policy-grid">
          <label class="policy-switch">
            <el-switch v-model="policyForm.enabled" />
            <span>启用自动修复（新建 Bug 自动入队）</span>
          </label>
          <label class="policy-switch">
            <el-switch v-model="policyForm.autoDeploy" />
            <span>允许运维 Agent 自动部署测试环境</span>
          </label>
          <label class="policy-switch">
            <el-switch v-model="policyForm.autoCreatePr" />
            <span>验证通过后自动推送分支并创建 PR</span>
          </label>
          <div class="policy-field">
            <span class="policy-label">测试环境</span>
            <el-input v-model="policyForm.testEnv" size="small" placeholder="test" />
          </div>
          <div class="policy-field">
            <span class="policy-label">环境绑定 ID</span>
            <el-input
              v-model="policyForm.bindingId" size="small"
              placeholder="已人工确认的 EnvBinding id"
            />
          </div>
          <div class="policy-field">
            <span class="policy-label">最大尝试次数</span>
            <el-input-number v-model="policyForm.maxAttempts" :min="1" :max="5" size="small" />
          </div>
          <div class="policy-field">
            <span class="policy-label">测试环境地址</span>
            <el-input
              v-model="policyForm.baseUrl" size="small"
              placeholder="https://test.example.com（用于确定性冒烟检查）"
            />
          </div>
          <div class="policy-field">
            <span class="policy-label">构建命令</span>
            <el-input
              v-model="policyForm.buildCommand" size="small"
              placeholder="mvn -q -DskipTests package（修复后必须通过）"
            />
          </div>
          <div class="policy-field">
            <span class="policy-label">测试命令</span>
            <el-input
              v-model="policyForm.testCommand" size="small"
              placeholder="可选，例如 mvn -q test"
            />
          </div>
        </div>
        <div class="editor-foot">
          <span class="policy-hint">
            {{ policy?.enabled ? '当前已启用，新建 Bug 会自动执行。' : '当前未启用，仅同步不自动执行。' }}
          </span>
          <el-button size="small" type="primary" :loading="policySaving" @click="submitPolicy">
            保存策略
          </el-button>
        </div>
      </section>

      <div v-if="taskView === 'list'" class="issues-body">
        <!-- 左列：搜索 / 过滤 / 任务列表 -->
        <aside class="issues-side">
          <div class="side-toolbar">
            <el-input
              v-model="keyword" size="default" clearable class="search"
              placeholder="搜索标题 / 描述"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <div class="filter-row">
              <div class="state-tabs">
                <button
                  v-for="opt in stateOptions" :key="opt.value" type="button"
                  class="state-tab" :class="{ active: stateFilter === opt.value }"
                  @click="stateFilter = opt.value"
                >
                  {{ opt.label }}
                </button>
              </div>
              <el-select v-model="kindFilter" size="small" class="kind-select">
                <el-option
                  v-for="opt in kindOptions" :key="opt.value"
                  :label="opt.label" :value="opt.value"
                />
              </el-select>
            </div>
            <div class="filter-row">
              <div class="prio-tabs">
                <button
                  v-for="opt in priorityTabs" :key="opt.value" type="button"
                  class="prio-tab" :class="[opt.value.toLowerCase() || 'all', { active: priorityFilter === opt.value }]"
                  @click="priorityFilter = opt.value"
                >
                  {{ opt.label }}
                </button>
              </div>
              <el-select v-model="sortBy" size="small" class="sort-select">
                <el-option label="最近更新" value="updated" />
                <el-option label="优先级" value="priority" />
                <el-option label="最新创建" value="created" />
                <el-option label="按标题" value="title" />
              </el-select>
            </div>
            <div class="toolbar-count-row">
              <span class="toolbar-count mono">{{ visibleItems.length }} 个任务</span>
              <span class="toolbar-hint">点击任务查看详情；支持粘贴 / 拖拽图片</span>
            </div>
          </div>

          <div class="side-list">
            <div v-if="loading && !items.length" class="list-empty">加载中…</div>
            <div v-else-if="error" class="list-empty error">{{ error }}</div>
            <div v-else-if="!visibleItems.length" class="list-empty">
              {{ items.length
                ? '当前分类下没有任务，切换分类看看。'
                : '还没有任务。新建一个，或在 Git 上创建 Issue 自动同步过来。' }}
            </div>

            <button
              v-for="row in visibleItems" :key="row.id" type="button" class="task-item"
              :class="{ active: selectedId === row.id, closed: row.state === 'CLOSED' }"
              @click="select(row)"
            >
              <span class="item-line">
                <span class="state-dot" :class="row.state.toLowerCase()" />
                <span class="prio-chip" :class="(row.priority ?? 'P2').toLowerCase()">
                  {{ row.priority ?? 'P2' }}
                </span>
                <span class="kind-chip" :class="row.kind === 'FEAT' ? 'feat' : 'bug'">
                  {{ kindLabel(row.kind) }}
                </span>
                <span class="task-title">{{ row.title }}</span>
              </span>
              <span class="item-meta">
                <span class="task-remote mono" :class="{ local: !isBound(row) }">
                  {{ remoteText(row) }}
                </span>
                <span
                  v-if="isBound(row) || row.syncStatus === 'FAILED'"
                  class="sync-pill" :class="row.syncStatus.toLowerCase()"
                >
                  {{ syncLabel(row) }}
                </span>
                <span class="item-labels">
                  <span v-for="label in labelList(row.labels).slice(0, 2)" :key="label" class="mini-chip">
                    {{ label }}
                  </span>
                </span>
                <span class="task-time mono">{{ formatTime(row.updatedAt) }}</span>
              </span>
            </button>
          </div>
        </aside>

        <!-- 右列：详情 / 编辑 / 空态 -->
        <section class="issues-detail">
          <!-- 新建 / 编辑：占据右列，不遮挡列表 -->
          <div v-if="editorOpen" class="pane-scroll">
            <div class="pane-head">
              <div>
                <h2 class="pane-title">{{ editingId ? '编辑任务' : '新建任务' }}</h2>
                <p class="pane-desc">
                  {{ editingId
                    ? '修改后会同步到已绑定的 Git Issue；Agent 也能读取并操作这条任务。'
                    : '保存后自动同步到项目托管的 Git 仓库；Agent 也能读取并操作这条任务。' }}
                </p>
              </div>
              <button type="button" class="pane-close" @click="closeEditor">取消</button>
            </div>
            <div class="editor-body">
              <div class="editor-field">
                <span class="editor-label">标题</span>
                <el-input
                  v-model="form.title" maxlength="200" show-word-limit
                  placeholder="一句话描述问题"
                />
              </div>
              <div class="editor-field">
                <div class="editor-label-row">
                  <span class="editor-label">描述</span>
                  <button
                    type="button" class="editor-upload" :disabled="uploading" @click="pickImage"
                  >
                    <el-icon><Picture /></el-icon>{{ uploading ? '上传中…' : '插入图片' }}
                  </button>
                </div>
                <div
                  class="desc-wrap"
                  @paste="onPasteImage($event, 'body')"
                  @dragover.prevent
                  @drop="onDropImage($event, 'body')"
                >
                  <el-input
                    v-model="form.body" type="textarea" :rows="6" resize="none"
                    placeholder="补充背景、复现步骤或期望结果（支持 Markdown，可直接粘贴 / 拖入截图）"
                  />
                  <p class="desc-hint">可直接粘贴截图或拖拽图片到这里，图片会插入到描述末尾。</p>
                </div>
              </div>
              <div class="editor-row">
                <div class="editor-field kind-field">
                  <span class="editor-label">类型</span>
                  <div class="kind-picker">
                    <button
                      type="button" class="kind-option bug"
                      :class="{ active: form.kind === 'BUG' }" @click="form.kind = 'BUG'"
                    >
                      Bug
                    </button>
                    <button
                      type="button" class="kind-option feat"
                      :class="{ active: form.kind === 'FEAT' }" @click="form.kind = 'FEAT'"
                    >
                      Feat
                    </button>
                  </div>
                </div>
                <div class="editor-field priority-field">
                  <span class="editor-label">优先级</span>
                  <div class="prio-picker">
                    <button
                      v-for="p in PRIORITY_VALUES" :key="p" type="button"
                      class="prio-option" :class="[p.toLowerCase(), { active: form.priority === p }]"
                      @click="form.priority = p"
                    >
                      {{ p }}
                    </button>
                  </div>
                </div>
                <div class="editor-field grow">
                  <span class="editor-label">标签</span>
                  <el-input v-model="form.labels" placeholder="bug, 待排查（逗号分隔）" />
                </div>
              </div>
              <div class="editor-target">
                <span class="target-icon"><el-icon><Link /></el-icon></span>
                <span v-if="editorTarget.repo" class="target-text">
                  同步到 {{ providerLabel(editorTarget.provider) }}
                  <span class="mono target-repo">{{ editorTarget.repo }}</span>
                </span>
                <span v-else class="target-text muted">
                  项目未关联 Git 仓库，任务仅保存在平台
                </span>
              </div>
            </div>
            <div class="editor-foot">
              <el-button size="small" @click="closeEditor">取消</el-button>
              <el-button size="small" type="primary" :loading="saving" @click="submit">
                {{ editingId ? '保存' : '创建任务' }}
              </el-button>
            </div>
          </div>

          <template v-else-if="selectedRow">
            <header class="detail-head">
              <div class="detail-head-main">
                <div class="detail-kicker">
                  <span class="prio-pill" :class="(selectedRow.priority ?? 'P2').toLowerCase()">
                    {{ selectedRow.priority ?? 'P2' }}
                  </span>
                  <span class="kind-chip" :class="selectedRow.kind === 'FEAT' ? 'feat' : 'bug'">
                    {{ kindLabel(selectedRow.kind) }}
                  </span>
                  <span class="state-pill" :class="selectedRow.state.toLowerCase()">
                    {{ selectedRow.state === 'OPEN' ? '待处理' : '已完成' }}
                  </span>
                  <span
                    v-if="isBound(selectedRow) || selectedRow.syncStatus === 'FAILED'"
                    class="sync-pill" :class="selectedRow.syncStatus.toLowerCase()"
                  >
                    {{ syncLabel(selectedRow) }}
                  </span>
                </div>
                <h2 class="detail-title">{{ selectedRow.title }}</h2>
                <div class="detail-meta">
                  <a
                    v-if="isBound(selectedRow)" class="block-link mono"
                    :href="selectedRow.url" target="_blank" rel="noreferrer"
                  >
                    <el-icon><Link /></el-icon> {{ selectedRow.repo }}#{{ selectedRow.number }}
                  </a>
                  <span v-else class="meta-muted mono">仅本地</span>
                  <span class="meta-sep">·</span>
                  <span class="mono">{{ selectedRow.id }}</span>
                  <span class="meta-sep">·</span>
                  <span class="mono">更新 {{ formatTime(selectedRow.updatedAt) }}</span>
                </div>
              </div>
              <div class="detail-actions">
                <el-button size="small" @click="openEdit(selectedRow)">
                  <el-icon><Edit /></el-icon> 编辑
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'state:' + selectedRow.id"
                  @click="toggleState(selectedRow)"
                >
                  <el-icon><component :is="selectedRow.state === 'OPEN' ? Close : RefreshRight" /></el-icon>
                  {{ selectedRow.state === 'OPEN' ? '关闭' : '重开' }}
                </el-button>
                <el-button
                  size="small" :loading="actionBusy === 'sync:' + selectedRow.id"
                  :disabled="!isBound(selectedRow)" @click="resync(selectedRow)"
                >
                  <el-icon><Refresh /></el-icon> 同步
                </el-button>
                <el-button
                  size="small" type="danger" plain
                  :loading="actionBusy === 'delete:' + selectedRow.id" @click="removeIssue(selectedRow)"
                >
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </div>
            </header>

            <div class="pane-scroll">
              <div v-if="selectedRow.syncError" class="sync-error">{{ selectedRow.syncError }}</div>

              <section class="detail-block">
                <div class="block-head">
                  <span class="block-title">描述</span>
                  <span class="block-sub mono">{{ selectedRow.id }}</span>
                </div>
                <div v-if="!selectedRow.body" class="block-empty">（无描述）</div>
                <div v-else class="markdown-body">
                  <MarkdownView :content="selectedRow.body" />
                </div>
              </section>

              <!-- Bug 自动修复：阶段时间线 + 证据 + PR -->
              <section v-if="selectedRow.kind === 'BUG'" class="fix-block">
                <div class="block-head">
                  <span class="block-title">自动修复</span>
                  <span v-if="fixRun" class="fix-status" :class="fixRun.status.toLowerCase()">
                    {{ fixStatusLabel(fixRun.status) }}
                  </span>
                  <span v-if="fixRun" class="block-sub mono">
                    阶段 {{ fixStageNo }} · {{ fixStageLabel }}
                  </span>
                  <span v-else class="block-sub">尚未运行</span>
                </div>

                <div v-if="fixLoading" class="block-empty">加载自动修复状态…</div>
                <div v-else-if="!fixRun" class="fix-empty">
                  <div class="fix-idle">
                    <span>可按平台策略自动执行，也可以立即手动启动一次。</span>
                    <el-button
                      size="small" type="primary"
                      :loading="fixBusy === 'start'" @click="startFix(selectedRow)"
                    >
                      立即自动修复
                    </el-button>
                  </div>
                  <ol class="fix-timeline fix-timeline-idle">
                    <li
                      v-for="stage in FIX_STAGES" :key="stage.key"
                      class="fix-step pending"
                    >
                      <span class="fix-dot" />
                      <div class="fix-step-body">
                        <div class="fix-step-title">{{ stage.label }}</div>
                        <div class="fix-step-hint">{{ stage.hint }}</div>
                      </div>
                    </li>
                  </ol>
                </div>
                <template v-else>
                  <div class="fix-meta mono">
                    <span v-if="fixRun.branch">{{ fixRun.branch }}</span>
                    <button
                      v-if="fixRun.branch" type="button" class="fix-copy"
                      title="复制修复分支名" @click="copyBranch"
                    >
                      复制
                    </button>
                    <span v-if="fixRun.artifacts.baseSha">base {{ fixRun.artifacts.baseSha.slice(0, 8) }}</span>
                    <span v-if="fixRun.artifacts.fixSha">fix {{ fixRun.artifacts.fixSha.slice(0, 8) }}</span>
                    <span v-if="fixRun.artifacts.env">env {{ fixRun.artifacts.env }}</span>
                    <span v-if="fixRun.attempt">第 {{ fixRun.attempt + 1 }} 次尝试</span>
                  </div>

                  <ol class="fix-timeline">
                    <li
                      v-for="(stage, index) in FIX_STAGES" :key="stage.key"
                      class="fix-step" :class="stageState(stage.key, index)"
                    >
                      <span class="fix-dot" />
                      <div class="fix-step-body">
                        <div class="fix-step-title">
                          {{ index + 1 }}. {{ stage.label }}
                        </div>
                        <div class="fix-step-hint">{{ stage.hint }}</div>
                      </div>
                    </li>
                  </ol>

                  <div v-if="fixRun.message" class="fix-message">{{ fixRun.message }}</div>
                  <div v-if="fixHint" class="fix-hint">{{ fixHint }}</div>

                  <details v-if="fixRun.artifacts.analysis" class="fix-fold">
                    <summary>分析结论</summary>
                    <pre class="fix-pre">{{ fixRun.artifacts.analysis }}</pre>
                  </details>
                  <details v-if="fixRun.artifacts.evidence" class="fix-fold">
                    <summary>部署 / 验证证据</summary>
                    <pre class="fix-pre">{{ fixRun.artifacts.evidence }}</pre>
                  </details>

                  <div v-if="fixStageRuns.length" class="fix-stages">
                    <div class="fix-stages-head">阶段记录（尝试次数 / 耗时 / 证据）</div>
                    <details v-for="sr in fixStageRuns" :key="sr.id" class="fix-fold">
                      <summary>
                        <span class="mono">{{ stageIndex(sr.stage) + 1 }}/7</span>
                        {{ stageLabel(sr.stage) }} · 第 {{ sr.attempt }} 次 ·
                        {{ formatDuration(sr.durationMs) }} ·
                        <span class="mono fix-stage-run" :class="sr.status.toLowerCase()">
                          {{ sr.status }}
                        </span>
                      </summary>
                      <pre v-if="sr.message" class="fix-pre">{{ sr.message }}</pre>
                      <pre v-if="sr.artifact" class="fix-pre">{{ sr.artifact }}</pre>
                    </details>
                  </div>

                  <div v-if="fixEvents.length" class="fix-events">
                    <div v-for="ev in fixEvents.slice(-8)" :key="ev.id" class="fix-event">
                      <span class="fix-event-time mono">{{ formatTime(ev.createdAt) }}</span>
                      <span class="fix-event-stage mono">{{ ev.stage }}</span>
                      <span class="fix-event-msg" :class="ev.level.toLowerCase()">
                        {{ ev.message }}
                      </span>
                    </div>
                  </div>

                  <div class="fix-actions">
                    <a
                      v-if="fixRun.artifacts.prUrl" class="block-link"
                      :href="fixRun.artifacts.prUrl" target="_blank" rel="noreferrer"
                    >
                      <el-icon><Link /></el-icon> 查看 PR（等待人工合并）
                    </a>
                    <span v-else class="fix-pr-hint">修复分支不会自动合并，PR 创建后等待人工确认。</span>
                    <el-button
                      v-if="fixActive" size="small"
                      :loading="fixBusy === 'cancel'" @click="confirmCancelFix(selectedRow)"
                    >
                      取消
                    </el-button>
                    <el-button
                      v-else-if="['BLOCKED', 'FAILED', 'CANCELLED'].includes(fixRun.status)"
                      size="small" type="primary"
                      :loading="fixBusy === 'retry'" @click="retryFix(selectedRow)"
                    >
                      重试 / 继续
                    </el-button>
                  </div>
                </template>
              </section>

              <section class="comment-block">
                <div class="block-head">
                  <span class="block-title">评论</span>
                  <span class="block-sub">
                    {{ comments.length }} 条 · 平台与 Git 双向同步
                  </span>
                </div>

                <div v-if="commentsLoading" class="block-empty">加载评论…</div>
                <div v-else-if="!comments.length" class="block-empty">还没有评论</div>
                <div v-else class="comment-list">
                  <div v-for="c in comments" :key="c.id" class="comment-item">
                    <div class="comment-head">
                      <span class="comment-author">
                        <el-icon><ChatDotRound /></el-icon>
                        {{ c.author }}
                      </span>
                      <span class="comment-source" :class="c.source.toLowerCase()">
                        {{ c.source === 'GIT' ? 'Git' : c.source === 'AGENT' ? 'Agent' : '平台' }}
                      </span>
                      <span class="comment-time mono">{{ formatTime(c.createdAt) }}</span>
                      <button
                        v-if="c.source === 'PLATFORM'" type="button" class="comment-del"
                        @click="removeComment(c)"
                      >
                        删除
                      </button>
                    </div>
                    <div class="comment-body">{{ c.body }}</div>
                  </div>
                </div>

                <div
                  class="comment-compose"
                  @paste="onPasteImage($event, 'comment')"
                  @dragover.prevent
                  @drop="onDropImage($event, 'comment')"
                >
                  <el-input
                    v-model="commentText" type="textarea" :rows="2" resize="none"
                    placeholder="写评论；已绑定 Git 的任务会同步为远端评论（可粘贴图片）"
                  />
                  <div class="compose-foot">
                    <span class="compose-hint">Agent 也可以直接调用工具评论 / 关闭任务</span>
                    <button
                      type="button" class="editor-upload" :disabled="uploading" @click="pickImage"
                    >
                      <el-icon><Picture /></el-icon>{{ uploading ? '上传中…' : '图片' }}
                    </button>
                    <el-button
                      size="small" type="primary" :loading="commenting"
                      :disabled="!commentText.trim()" @click="submitComment"
                    >
                      发表评论
                    </el-button>
                  </div>
                </div>
              </section>
            </div>
          </template>

          <div v-else class="detail-empty">
            <span class="empty-icon"><el-icon><Tickets /></el-icon></span>
            <h3 class="empty-title">选择一个任务查看详情</h3>
            <p class="empty-desc">
              左侧任务与 Git 仓库双向同步；也可以新建任务，或让 Agent 在对话里直接创建。
            </p>
            <el-button size="small" type="primary" @click="openCreate">
              <el-icon><Plus /></el-icon> 新建任务
            </el-button>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.issues-view {
  display: flex;
  flex: 1 1 0;
  min-height: 0;
  padding: 18px 22px 22px;
}
.issues-shell {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  min-height: 0;
}

/* ---- 页头 ---- */
.issues-head {
  display: flex;
  flex: none;
  align-items: flex-start;
  gap: 16px;
}
.issues-head-main {
  flex: 1;
  min-width: 0;
}
.issues-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.issues-title {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.015em;
  color: var(--ink);
}
/* 列表 / 看板切换 */
.view-switch {
  display: flex;
  gap: 2px;
  padding: 2px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.view-switch-btn {
  padding: 3px 12px;
  font-family: inherit;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
  transition: background-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}
.view-switch-btn:hover {
  color: var(--ink);
}
.view-switch-btn.active {
  color: var(--accent-ink, #4a6cf7);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.issues-sub {
  font-size: 11px;
  color: var(--ink-faint);
}
.issues-desc {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.issues-head-side {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
}
.repo-chip {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 4px 10px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.repo-chip .mono {
  color: var(--ink);
}
.repo-dot {
  width: 6px;
  height: 6px;
  background: var(--accent);
  border-radius: 50%;
}

/* ---- 量化看板视图 ---- */
.board-view {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding-bottom: 4px;
  overflow-y: auto;
}

/* 主视觉：完成率大环 + 关键指标 */
.board-hero {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 18px;
  padding: 20px 22px;
  background:
    radial-gradient(120% 140% at 0% 0%, rgb(77 130 247 / 10%), transparent 55%),
    radial-gradient(120% 140% at 100% 0%, rgb(46 163 107 / 9%), transparent 55%),
    linear-gradient(160deg, var(--surface-raised), var(--surface-sunken));
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg, 16px);
}
.hero-ring {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 224px;
}
.hero-ring svg {
  width: 224px;
  height: 224px;
  transform: rotate(-90deg);
}
.ring-track {
  fill: none;
  stroke: var(--line-soft);
  stroke-width: 14;
  opacity: 0.55;
}
.ring-value {
  fill: none;
  stroke: url(#ringGrad);
  stroke-width: 14;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.8s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.ring-center {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
}
.ring-center b {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.03em;
  color: var(--ink);
}
.ring-center span {
  font-size: 11px;
  color: var(--ink-muted);
}
.ring-center em {
  margin-top: 3px;
  padding: 1px 8px;
  font-size: 9.5px;
  font-style: normal;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 999px;
}
.hero-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
  justify-content: center;
  min-width: 0;
}
.hero-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ink);
}
.hero-head p {
  margin: 3px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.hero-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 1px;
  align-items: flex-start;
  padding: 10px 12px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 12px;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}
.metric:hover {
  transform: translateY(-2px);
  border-color: var(--accent);
  box-shadow: 0 8px 20px rgb(77 130 247 / 12%);
}
.metric-label {
  font-size: 10px;
  color: var(--ink-faint);
}
.metric-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.02em;
  color: var(--ink);
}
.metric-foot {
  font-size: 9px;
  color: var(--ink-faint);
}
.metric.ok .metric-value { color: var(--ok); }
.metric.p0 .metric-value { color: var(--danger); }
.metric.fail .metric-value { color: var(--warn); }

/* 三块量化面板 */
.board-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.bpanel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-lg, 16px);
}
.bpanel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.bpanel-title {
  font-size: 12px;
  font-weight: 650;
  color: var(--ink);
}
.bpanel-hint {
  font-size: 9.5px;
  color: var(--ink-faint);
}

/* 优先级分布 */
.pbar-row {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 2px 0;
  font-family: inherit;
  cursor: pointer;
  background: none;
  border: 0;
}
.pbar-key {
  flex: none;
  width: 26px;
  height: 20px;
  font-size: 10px;
  font-weight: 700;
  line-height: 20px;
  text-align: center;
  border-radius: 6px;
}
.pbar-key.p0 { color: #c23a3a; background: rgb(229 77 77 / 14%); }
.pbar-key.p1 { color: #96660f; background: rgb(184 130 27 / 16%); }
.pbar-key.p2 { color: var(--accent-ink, #4a6cf7); background: rgb(77 130 247 / 13%); }
.pbar-track {
  flex: 1;
  height: 8px;
  overflow: hidden;
  background: var(--surface-sunken);
  border-radius: 999px;
}
.pbar-track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  transition: width 0.6s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.pbar-track i.p0 { background: linear-gradient(90deg, #ff9d9d, var(--danger)); }
.pbar-track i.p1 { background: linear-gradient(90deg, #e8c46a, var(--warn)); }
.pbar-track i.p2 { background: linear-gradient(90deg, #8fb2ff, var(--accent)); }
.pbar-num {
  flex: none;
  width: 22px;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink);
  text-align: right;
}
.pbar-pct {
  flex: none;
  width: 34px;
  font-size: 10px;
  color: var(--ink-faint);
  text-align: right;
}

/* 类型构成 + 同步状态 */
.type-split {
  display: flex;
  gap: 3px;
  height: 10px;
  overflow: hidden;
  background: var(--surface-sunken);
  border-radius: 999px;
}
.type-bar {
  flex-basis: 0;
  min-width: 0;
}
.type-bar.bug { background: linear-gradient(90deg, #d47272, #b54a4a); }
.type-bar.feat { background: linear-gradient(90deg, #7e9cff, var(--accent)); }
.type-legend,
.sync-list {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 10px;
  color: var(--ink-muted);
}
.sync-list {
  padding-top: 10px;
  margin-top: auto;
  border-top: 1px dashed var(--line-soft);
}
.type-legend b,
.sync-list b {
  margin-left: 2px;
  color: var(--ink);
}
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 4px;
  border-radius: 50%;
}
.dot.bug { background: #b54a4a; }
.dot.feat { background: var(--accent); }
.dot.synced { background: var(--ok); }
.dot.local { background: var(--line); }
.dot.failed { background: var(--danger); }

/* 近 7 天活动 */
.trend {
  display: flex;
  flex: 1;
  gap: 8px;
  align-items: stretch;
  height: 112px;
}
.trend-col {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  min-width: 0;
}
.trend-count {
  font-size: 9.5px;
  color: var(--ink-faint);
}
.trend-bar-wrap {
  display: flex;
  flex: 1;
  align-items: flex-end;
  justify-content: center;
  width: 100%;
  min-height: 0;
}
.trend-bar {
  width: 100%;
  max-width: 20px;
  min-height: 4px;
  background: linear-gradient(180deg, #6e8cff, rgb(77 130 247 / 18%));
  border-radius: 5px 5px 2px 2px;
  box-shadow: 0 0 12px rgb(77 130 247 / 18%);
  transition: height 0.6s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.trend-label {
  font-size: 9px;
  color: var(--ink-faint);
  white-space: nowrap;
}

/* 优先处理 */
.focus-panel {
  gap: 2px;
}
.focus-panel .bpanel-head {
  margin-bottom: 6px;
}
.focus-row {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 9px 8px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 8px;
  transition: background-color 0.15s ease;
}
.focus-row:hover {
  background: var(--surface-sunken);
}
.focus-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.focus-meta {
  flex: none;
  font-size: 10px;
  color: var(--ink-faint);
}
.focus-arrow {
  flex: none;
  font-size: 12px;
  color: var(--ink-faint);
  transition: transform 0.15s ease, color 0.15s ease;
}
.focus-row:hover .focus-arrow {
  color: var(--accent);
  transform: translateX(2px);
}
.board-empty {
  padding: 18px 6px;
  font-size: 11px;
  color: var(--ink-faint);
  text-align: center;
}

@media (max-width: 1000px) {
  .board-hero {
    grid-template-columns: 1fr;
  }
  .hero-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .board-grid {
    grid-template-columns: 1fr;
  }
}

/* ---- 左右分栏 ---- */
.issues-body {
  display: flex;
  flex: 1;
  gap: 14px;
  min-height: 0;
}
.issues-side {
  display: flex;
  flex: none;
  flex-direction: column;
  width: 340px;
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}
.side-toolbar {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 9px;
  padding: 12px;
  background: var(--sidebar-background-color);
  border-bottom: 1px solid var(--line-soft);
}
.search {
  width: 100%;
}
/* 一行筛选：状态分段 + 类型 + 排序，避免两排按钮占满左栏 */
.filter-row {
  display: flex;
  gap: 6px;
  align-items: center;
}
.filter-row .state-tabs {
  flex: 1 1 auto;
  min-width: 0;
}
.kind-select { flex: none; width: 92px; }
.sort-select { flex: none; width: 96px; }
.filter-row :deep(.el-select__wrapper) {
  padding: 2px 8px;
  font-size: 11px;
  min-height: 26px;
}
.toolbar-count-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}
.toolbar-hint {
  overflow: hidden;
  font-size: 10px;
  color: var(--ink-faint);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.state-tabs {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.state-tab {
  flex: 1;
  padding: 5px 10px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
  transition: background-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}
.state-tab:hover {
  color: var(--ink);
}
.state-tab.active {
  font-weight: 600;
  color: var(--ink);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
/* 优先级快捷筛选：P0 / P1 / P2 各自配色，选中时整颗点亮 */
.prio-tabs {
  display: flex;
  flex: 1;
  gap: 3px;
  min-width: 0;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.prio-tab {
  flex: 1;
  padding: 4px 6px;
  font-family: inherit;
  font-size: 10px;
  font-weight: 600;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
  transition: background-color var(--dur-fast) var(--ease-out),
    color var(--dur-fast) var(--ease-out);
}
.prio-tab:hover {
  color: var(--ink);
}
.prio-tab.active {
  color: var(--ink);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.prio-tab.active.p0 { color: var(--danger); }
.prio-tab.active.p1 { color: var(--warn); }
.prio-tab.active.p2 { color: var(--accent); }
.toolbar-count {
  font-size: 10px;
  color: var(--ink-faint);
}
.side-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-height: 0;
  padding: 6px;
  overflow-y: auto;
}
.list-empty {
  padding: 28px 16px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--ink-faint);
  text-align: center;
}
.list-empty.error {
  color: var(--danger, #c45656);
}

/* 任务行：两行式列表项，选中后整行浅蓝底 */
.task-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  padding: 10px 11px;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 9px;
  transition: background-color var(--dur-fast) var(--ease-out);
}
.task-item:hover {
  background: var(--surface-hover);
}
.task-item.active {
  background: var(--accent-soft);
}
.task-item.active .task-title {
  font-weight: 600;
}
.item-line {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}
.state-dot {
  flex: none;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.state-dot.open {
  background: var(--warn, #d19a00);
}
.state-dot.closed {
  background: var(--ok);
}
.task-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.task-item.closed .task-title {
  color: var(--ink-muted);
}
.item-meta {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
  padding-left: 13px;
  font-size: 10px;
  color: var(--ink-faint);
}
.item-labels {
  display: flex;
  gap: 4px;
  overflow: hidden;
}
.kind-chip {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  font-weight: 600;
  line-height: 16px;
  letter-spacing: 0.02em;
  border-radius: 4px;
}
/* 优先级徽标：P0 红 / P1 琥珀 / P2 蓝，列表与详情共用 */
.prio-chip,
.prio-pill {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  font-weight: 700;
  line-height: 16px;
  letter-spacing: 0.03em;
  border-radius: 4px;
}
.prio-pill {
  line-height: 17px;
  border-radius: var(--radius-pill);
}
.prio-chip.p0,
.prio-pill.p0 {
  color: #c23a3a;
  background: rgb(229 77 77 / 14%);
}
.prio-chip.p1,
.prio-pill.p1 {
  color: #96660f;
  background: rgb(184 130 27 / 16%);
}
.prio-chip.p2,
.prio-pill.p2 {
  color: var(--accent-ink, #4a6cf7);
  background: rgb(77 130 247 / 13%);
}
.kind-chip.bug {
  color: #b54a4a;
  background: rgb(181 74 74 / 10%);
}
.kind-chip.feat {
  color: var(--accent-ink, #4a6cf7);
  background: rgb(74 108 247 / 12%);
}
.mini-chip {
  overflow: hidden;
  padding: 0 6px;
  font-size: 9px;
  line-height: 15px;
  color: var(--ink-muted);
  white-space: nowrap;
  text-overflow: ellipsis;
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.task-remote {
  flex: none;
  font-size: 10px;
  color: var(--ink-muted);
  white-space: nowrap;
}
.task-remote.local {
  color: var(--ink-faint);
}
.sync-pill {
  flex: none;
  padding: 0 6px;
  font-size: 9px;
  line-height: 16px;
  white-space: nowrap;
  border-radius: 4px;
}
.sync-pill.synced {
  color: var(--ok-ink, #2f7d4f);
  background: var(--ok-soft, #e8f5ec);
}
.sync-pill.failed {
  color: var(--danger, #c45656);
  background: var(--danger-soft, #fdecec);
}
.sync-pill.local,
.sync-pill.pending {
  color: var(--ink-faint);
  background: var(--surface-sunken);
}
.task-time {
  flex: none;
  margin-left: auto;
  font-size: 9.5px;
  color: var(--ink-faint);
  white-space: nowrap;
}

/* ---- 右列详情 ---- */
.issues-detail {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}
.detail-head {
  display: flex;
  flex: none;
  gap: 16px;
  align-items: flex-start;
  padding: 16px 20px 14px;
  border-bottom: 1px solid var(--line-soft);
}
.detail-head-main {
  flex: 1;
  min-width: 0;
}
.detail-kicker {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.state-pill {
  padding: 0 7px;
  font-size: 9.5px;
  line-height: 17px;
  border-radius: var(--radius-pill);
}
.state-pill.open {
  color: var(--warn-ink, #8a5a12);
  background: var(--warn-soft);
}
.state-pill.closed {
  color: var(--ok-ink, #1b7a4f);
  background: var(--ok-soft);
}
.detail-title {
  margin: 7px 0 0;
  font-size: 16px;
  font-weight: 650;
  line-height: 1.45;
  letter-spacing: -0.01em;
  color: var(--ink);
  overflow-wrap: anywhere;
}
.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 7px;
  font-size: 10px;
  color: var(--ink-faint);
}
.meta-sep {
  color: var(--ink-faint);
}
.meta-muted {
  color: var(--ink-faint);
}
.detail-actions {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}
.pane-scroll {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
  padding: 16px 20px 24px;
  overflow-y: auto;
}
.sync-error {
  font-size: 11px;
  line-height: 1.6;
  color: var(--danger, #c45656);
  overflow-wrap: anywhere;
}
.detail-block,
.comment-block {
  display: flex;
  flex: none;
  flex-direction: column;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}
.block-head {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: baseline;
  padding: 9px 13px;
  background: var(--code-bg);
  border-bottom: 1px solid var(--line-soft);
}
.block-title {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink);
}
.block-sub {
  overflow: hidden;
  margin-left: auto;
  font-size: 10px;
  color: var(--ink-faint);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.block-link {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 10.5px;
  color: var(--accent-ink);
  text-decoration: none;
}
.block-link:hover {
  text-decoration: underline;
}
.block-empty {
  padding: 14px 13px;
  font-size: 11px;
  color: var(--ink-faint);
}
.markdown-body {
  padding: 12px 14px;
}
.markdown-body :deep(.markdown-view) {
  font-size: 12.5px;
  line-height: 1.75;
}

/* ---- 空态 ---- */
.detail-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  text-align: center;
}
.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  margin-bottom: 4px;
  font-size: 20px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: var(--radius-lg);
}
.empty-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-soft);
}
.empty-desc {
  max-width: 26rem;
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--ink-muted);
}

/* ---- 编辑面板 ---- */
.pane-head {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line-soft);
}
.pane-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--ink);
}
.pane-desc {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.pane-close {
  flex: none;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-sm);
}
.pane-close:hover {
  color: var(--ink);
  background: var(--surface-hover);
}
.editor-head {
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--line-soft);
}
.editor-title {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ink);
}
.editor-desc {
  margin: 4px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
}
.editor-body {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 12px;
}
.editor-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.kind-field {
  flex: none;
}
.kind-picker {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.kind-option {
  padding: 4px 12px;
  font-family: inherit;
  font-size: 11px;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.kind-option.bug.active {
  font-weight: 600;
  color: #b54a4a;
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.kind-option.feat.active {
  font-weight: 600;
  color: var(--accent-ink, #4a6cf7);
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.priority-field {
  flex: none;
}
.prio-picker {
  display: flex;
  gap: 3px;
  padding: 3px;
  background: var(--surface-sunken);
  border-radius: 8px;
}
.prio-option {
  padding: 4px 10px;
  font-family: inherit;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: 6px;
}
.prio-option.active {
  background: var(--surface-raised);
  box-shadow: var(--shadow-1);
}
.prio-option.p0.active { color: var(--danger); }
.prio-option.p1.active { color: var(--warn); }
.prio-option.p2.active { color: var(--accent); }
.editor-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--ink-muted);
}
.editor-label-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}
.editor-upload {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: var(--radius-pill);
}
.editor-upload:hover:not(:disabled) {
  color: var(--accent-ink);
  border-color: var(--accent);
}
.editor-upload:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.desc-wrap {
  position: relative;
}
.desc-hint {
  margin: 4px 0 0;
  font-size: 10px;
  color: var(--ink-faint);
}
.hidden-file {
  display: none;
}
.editor-row {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}
.grow {
  flex: 1;
  min-width: 0;
}
.editor-target {
  display: flex;
  flex: none;
  gap: 7px;
  align-items: center;
  padding: 8px 11px;
  font-size: 10.5px;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 8px;
}
.target-icon {
  display: inline-flex;
  flex: none;
  color: var(--ink-faint);
}
.target-text {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  min-width: 0;
}
.target-text.muted {
  color: var(--ink-faint);
}
.target-repo {
  padding: 1px 5px;
  font-size: 10px;
  color: var(--ink);
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 4px;
}
.editor-foot {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
}
.policy-card .editor-foot {
  padding: 10px 16px;
  background: var(--surface-sunken);
  border-radius: 0 0 var(--radius-lg) var(--radius-lg);
}

.comment-list {
  display: flex;
  flex-direction: column;
}
.comment-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 13px;
}
.comment-item + .comment-item {
  border-top: 1px solid var(--line-soft);
}
.comment-head {
  display: flex;
  gap: 8px;
  align-items: center;
}
.comment-author {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 11px;
  font-weight: 600;
  color: var(--ink);
}
.comment-source {
  padding: 0 5px;
  font-size: 9px;
  line-height: 15px;
  color: var(--ink-faint);
  background: var(--surface-sunken);
  border-radius: 4px;
}
.comment-source.git {
  color: var(--accent-ink);
  background: var(--accent-soft);
}
.comment-time {
  flex: 1;
  font-size: 10px;
  color: var(--ink-faint);
}
.comment-del {
  padding: 1px 6px;
  font-size: 10px;
  color: var(--ink-faint);
  cursor: pointer;
  background: none;
  border: 0;
  border-radius: var(--radius-xs);
}
.comment-del:hover {
  color: var(--danger, #c45656);
  background: var(--surface-hover);
}
.comment-body {
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--ink-soft);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.comment-compose {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 11px 13px;
  border-top: 1px solid var(--line-soft);
}
.compose-foot {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}
.compose-hint {
  font-size: 10.5px;
  color: var(--ink-faint);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* ---- 自动修复策略 ---- */
.policy-card {
  flex: none;
  overflow: hidden;
  background: var(--surface-raised);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-1);
}
.policy-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px 18px;
  padding: 14px 16px;
}
.policy-switch {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 11.5px;
  color: var(--ink);
  cursor: pointer;
}
.policy-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.policy-label {
  font-size: 10.5px;
  color: var(--ink-muted);
}
.policy-hint {
  margin-right: auto;
  font-size: 10.5px;
  color: var(--ink-faint);
}

/* ---- 自动修复时间线 ---- */
.fix-block {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 10px;
  padding: 12px 13px;
  background: var(--surface-raised);
  border: 1px solid var(--line-soft);
  border-radius: 10px;
}
.fix-block .block-head {
  padding: 0 0 8px;
  background: none;
  border-bottom: 1px solid var(--line-soft);
}
.fix-status {
  margin-left: auto;
  padding: 2px 8px;
  font-size: 10px;
  border-radius: var(--radius-pill);
}
.fix-status.queued,
.fix-status.running {
  color: var(--accent-ink, #4a6cf7);
  background: var(--accent-soft, rgba(74, 108, 247, 0.12));
}
.fix-status.succeeded,
.fix-status.waiting_merge {
  color: var(--ok);
  background: rgba(46, 160, 67, 0.12);
}
.fix-status.failed {
  color: var(--danger, #c45656);
  background: rgba(196, 86, 86, 0.12);
}
.fix-status.blocked {
  color: var(--warn, #d19a00);
  background: rgba(209, 154, 0, 0.12);
}
.fix-status.cancelled {
  color: var(--ink-muted);
  background: var(--surface-sunken);
}
.fix-empty {
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 11.5px;
  color: var(--ink-muted);
}
.fix-idle {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}
.fix-timeline-idle {
  opacity: 0.72;
}
.fix-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 10px;
  color: var(--ink-faint);
}
.fix-timeline {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding: 0;
  margin: 0;
  list-style: none;
}
.fix-step {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 5px 0 5px 2px;
}
.fix-step:not(:last-child)::before {
  position: absolute;
  top: 16px;
  left: 6px;
  width: 1px;
  height: calc(100% - 10px);
  content: '';
  background: var(--line-soft);
}
.fix-dot {
  z-index: 1;
  flex: none;
  width: 9px;
  height: 9px;
  margin-top: 4px;
  background: var(--surface-sunken);
  border: 1.5px solid var(--line);
  border-radius: 50%;
}
.fix-step.done .fix-dot {
  background: var(--ok);
  border-color: var(--ok);
}
.fix-step.active .fix-dot {
  background: var(--accent);
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft, rgba(74, 108, 247, 0.15));
}
.fix-step.error .fix-dot {
  background: var(--danger, #c45656);
  border-color: var(--danger, #c45656);
}
.fix-step.muted .fix-dot {
  background: var(--ink-faint);
  border-color: var(--ink-faint);
}
.fix-step-body {
  min-width: 0;
}
.fix-step-title {
  font-size: 11.5px;
  color: var(--ink);
}
.fix-step.pending .fix-step-title {
  color: var(--ink-faint);
}
.fix-step.active .fix-step-title {
  font-weight: 600;
}
.fix-step-hint {
  margin-top: 1px;
  font-size: 10px;
  line-height: 1.45;
  color: var(--ink-faint);
}
.fix-message {
  padding: 8px 10px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ink-muted);
  background: var(--surface-sunken);
  border-radius: 7px;
}
.fix-hint {
  padding: 7px 10px;
  font-size: 10.5px;
  line-height: 1.55;
  color: var(--warn, #d19a00);
  background: rgba(209, 154, 0, 0.08);
  border-radius: 7px;
}
.fix-copy {
  padding: 1px 6px;
  font-family: inherit;
  font-size: 10px;
  line-height: 1.4;
  color: var(--ink-muted);
  cursor: pointer;
  background: none;
  border: 1px solid var(--line-soft);
  border-radius: 5px;
}
.fix-copy:hover {
  color: var(--ink);
  border-color: var(--line);
}
.fix-fold {
  font-size: 11px;
  color: var(--ink-muted);
}
.fix-fold summary {
  font-size: 10.5px;
  color: var(--ink-muted);
  cursor: pointer;
}
.fix-pre {
  max-height: 220px;
  padding: 8px 10px;
  margin: 6px 0 0;
  overflow: auto;
  font-family: var(--font-mono, monospace);
  font-size: 10.5px;
  line-height: 1.55;
  color: var(--ink);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--code-bg);
  border-radius: 7px;
}
.fix-events {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.fix-stages {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.fix-stages-head {
  font-size: 10.5px;
  color: var(--ink-muted);
}
.fix-stage-run.continue { color: #2f9e6e; }
.fix-stage-run.failed { color: #d9534f; }
.fix-stage-run.blocked,
.fix-stage-run.cancelled { color: #b8860b; }
.fix-stage-run.running { color: var(--ink); }
.fix-event {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 10px;
}
.fix-event-time {
  flex: none;
  color: var(--ink-faint);
}
.fix-event-stage {
  flex: none;
  color: var(--ink-muted);
}
.fix-event-msg {
  min-width: 0;
  color: var(--ink-muted);
}
.fix-event-msg.warn {
  color: var(--warn, #d19a00);
}
.fix-event-msg.error {
  color: var(--danger, #c45656);
}
.fix-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
}
.fix-pr-hint {
  font-size: 10px;
  color: var(--ink-faint);
}

/* ---- 响应式 ---- */
@media (max-width: 1080px) {
  .issues-side {
    width: 300px;
  }
}
@media (max-width: 900px) {
  .issues-view {
    padding: 14px;
    overflow-y: auto;
  }
  .issues-shell {
    flex: none;
    min-height: auto;
  }
  .issues-head {
    flex-direction: column;
    gap: 10px;
  }
  .issues-head-side {
    justify-content: flex-start;
  }
  .issues-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .issues-body {
    flex: none;
    flex-direction: column;
    min-height: auto;
  }
  .issues-side {
    width: auto;
    max-height: 360px;
  }
  .side-toolbar .state-tabs {
    max-width: 380px;
  }
  .issues-detail {
    flex: none;
    min-height: 520px;
    max-height: 80vh;
  }
  .detail-head {
    flex-direction: column;
    gap: 10px;
  }
  .detail-actions {
    justify-content: flex-start;
  }
  .editor-row {
    flex-direction: column;
    align-items: stretch;
  }
  .pane-scroll {
    padding: 14px 16px 20px;
  }
}
@media (max-width: 600px) {
  .item-labels {
    display: none;
  }
  .detail-meta {
    font-size: 9.5px;
  }
}
</style>
